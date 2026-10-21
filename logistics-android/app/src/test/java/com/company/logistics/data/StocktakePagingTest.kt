package com.company.logistics.data

import com.company.logistics.data.remote.ApiConfig
import com.company.logistics.data.remote.ApiParser
import com.company.logistics.data.remote.MaterialFlowApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket

/**
 * 盘点分页（0.5.47）：盘点是长期累积数据，App 每页 50 条，靠 total 判断要不要"加载更多"。
 * 这里锁住两件事：请求确实带 page/pageSize；响应 total/page 能解析出来。
 */
class StocktakePagingTest {

    @Test
    fun parserReadsTotalAndPage() {
        val json = """
            {"items":[
              {"id":"s1","materialCode":"M-1","location_code":"A-01","book_quantity":10,
               "actual_quantity":9,"difference":-1,"status":"PENDING_CONFIRM","created_at":"2026-10-21T10:00:00"},
              {"id":"s2","materialCode":"M-2","location_code":null,"book_quantity":5,
               "actual_quantity":5,"difference":0,"status":"CONFIRMED","created_at":"2026-10-20T10:00:00"}
            ],"page":2,"pageSize":50,"total":120,"totalPages":3}
        """.trimIndent()
        val page = ApiParser.parseStocktakePage(json)
        assertEquals(120, page.total)
        assertEquals(2, page.page)
        assertEquals(2, page.items.size)
        assertEquals("A-01", page.items[0].locationCode)
        assertEquals(-1, page.items[0].difference)
    }

    @Test
    fun parserFallsBackToItemCountWhenTotalMissing() {
        val json = """{"items":[{"id":"s1","material_code":"M-1","book_quantity":1,"actual_quantity":1}]}"""
        val page = ApiParser.parseStocktakePage(json)
        assertEquals(1, page.total)
        assertEquals(1, page.page)
    }

    @Test
    fun repositoryRequestsRequestedPage() = runBlocking {
        var requestLine = ""
        val server = ServerSocket(0)
        val worker = Thread {
            server.accept().use { socket: Socket ->
                requestLine = readLineOf(socket)
                respond(socket)
            }
        }.apply { isDaemon = true }
        worker.start()
        val old = ApiConfig.baseUrl
        val page = try {
            ApiConfig.baseUrl = "http://127.0.0.1:${server.localPort}"
            val repo = LogisticsRepository(MaterialFlowApi().also { it.updateToken("token") }, NoOpDao())
            val r = repo.stocktakes(page = 3, pageSize = 20)
            assertTrue("分页请求应成功：$r", r.isSuccess)
            r.getOrThrow()
        } finally {
            ApiConfig.baseUrl = old
            server.close()
        }
        worker.join(5_000)
        assertTrue("请求应带分页参数，实际：$requestLine", requestLine.contains("/api/v1/stocktakes?page=3&pageSize=20"))
        assertEquals(41, page.total)
        assertEquals(3, page.page)
    }

    private fun readLineOf(socket: Socket): String {
        val input = java.io.BufferedInputStream(socket.getInputStream())
        val bytes = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0 || b == '\n'.code) break
            if (b != '\r'.code) bytes.write(b)
        }
        return bytes.toString(Charsets.UTF_8.name())
    }

    private fun respond(socket: Socket) {
        val body = """{"items":[{"id":"s1","material_code":"M-1","book_quantity":10,"actual_quantity":9,
            "difference":-1,"status":"PENDING_CONFIRM"}],"page":3,"pageSize":20,"total":41,"totalPages":3}"""
        val payload = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${payload.size}\r\n\r\n"
        socket.getOutputStream().apply {
            write(head.toByteArray()); write(payload); flush()
        }
    }

    private class NoOpDao : OfflineOperationDao {
        override fun observeAll(): Flow<List<OfflineOperationEntity>> = emptyFlow()
        override suspend fun pending(): List<OfflineOperationEntity> = emptyList()
        override fun observePendingCount(): Flow<Int> = emptyFlow()
        override fun observeAttentionCount(): Flow<Int> = emptyFlow()
        override suspend fun insert(operation: OfflineOperationEntity): Long = 1L
        override suspend fun updateStatus(id: String, status: String, error: String?) = Unit
        override suspend fun markSynced(id: String, status: String, serverTime: String?) = Unit
        override suspend fun clearSynced() = Unit
        override suspend fun findById(id: String): OfflineOperationEntity? = null
        override suspend fun resetStuckSyncing(): Int = 0
        override suspend fun resetToPending(id: String) = Unit
        override suspend fun deleteById(id: String) = Unit
    }
}
