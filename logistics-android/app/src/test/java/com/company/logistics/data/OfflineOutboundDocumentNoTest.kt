package com.company.logistics.data

import com.company.logistics.data.remote.ApiConfig
import com.company.logistics.data.remote.MaterialFlowApi
import com.company.logistics.model.Inventory
import com.company.logistics.model.LocationStock
import com.company.logistics.model.Material
import com.company.logistics.model.MaterialInventory
import com.company.logistics.model.MaterialStatusCode
import com.company.logistics.model.OfflineOpType
import com.company.logistics.model.OrderMaterialItem
import com.company.logistics.model.SyncStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket

/**
 * 批次一 · 项 7：离线 OUTBOUND 重放必须还原 documentNo。
 *
 * 六条覆盖：
 *  1. 离线入队（remark 打包 documentNo 且带 v=1 版本标记）→ 联网重放：请求体 documentNo 还原、remark 还原为原始备注；
 *  2. 兼容旧队列数据（remark 是裸文本、无 documentNo）：重放不报错，documentNo 保持 null，remark 原样；
 *  3. 在线路径不回归：在线 submitOutbound 的请求体 documentNo 仍是订单号，remark 仍是原始备注（未被打包）；
 *  4. 用户备注本身就是形如 {"documentNo":"OM-9"} 的合法 JSON：不得被当成打包体（documentNo 不被替换、remark 原样传）；
 *  5. 打包体被写坏（以 { 开头、含 documentNo 键但解析失败）：判 FAILED("队列数据损坏")，不得静默标 SYNCED；
 *  6. 升级前入队的旧打包体（无 v 字段、三键齐全）：仍能还原 documentNo 与原始备注。
 */
class OfflineOutboundDocumentNoTest {

    private val documentNo = "OM-1"

    @Test
    fun offlineReplayRestoresDocumentNoFromQueuedRemark() = runBlocking {
        val dao = InMemoryOfflineDao()
        // 断网：指向必然拒连的端口，让 submitOutbound 走离线入队。
        val previousBaseUrl = ApiConfig.baseUrl
        ApiConfig.baseUrl = "http://127.0.0.1:1"
        val repository = LogisticsRepository(MaterialFlowApi().also { it.updateToken("token") }, dao)
        val queued = try {
            repository.submitOutbound(orderItem(), inventory(), documentNo, 5, "领料备注")
        } finally {
            ApiConfig.baseUrl = previousBaseUrl
        }
        assertTrue("离线提交应入队，实际 $queued", queued is SubmitResult.Queued)
        assertEquals(1, dao.rows.size)
        assertEquals(OfflineOpType.OUTBOUND.name, dao.rows.single().operationType)

        val captured = captureRequests(1, TRANSFER_RESPONSE) { port ->
            val old = ApiConfig.baseUrl
            try {
                ApiConfig.baseUrl = "http://127.0.0.1:$port"
                val report = repository.syncPending()
                assertEquals(1, report.success)
            } finally {
                ApiConfig.baseUrl = old
            }
        }.single()

        val body = JSONObject(captured.body)
        assertEquals("OUTBOUND", body.getString("type"))
        assertEquals("documentNo 应从打包的 remark 还原", documentNo, body.optString("documentNo", ""))
        assertEquals("领料备注", body.getString("remark"))
        assertEquals(SyncStatus.SYNCED.name, dao.rows.single().status)
    }

    @Test
    fun legacyQueueRowsWithoutDocumentNoReplayWithNullDocumentNo() = runBlocking {
        val dao = InMemoryOfflineDao()
        // 旧版本客户端入队的 OUTBOUND：remark 是裸文本，没有打包 documentNo。
        dao.insert(
            OfflineOperationEntity(
                id = "legacy-op",
                clientOperationId = "11111111-1111-1111-1111-111111111111",
                materialCode = "MAT-1",
                operationType = OfflineOpType.OUTBOUND.name,
                status = SyncStatus.PENDING.name,
                createdAt = 1L,
                quantity = 3,
                materialId = "mat-1",
                expectedInventoryVersion = 1,
                remark = "旧备注文本",
            )
        )
        val repository = LogisticsRepository(MaterialFlowApi().also { it.updateToken("token") }, dao)
        val captured = captureRequests(1, TRANSFER_RESPONSE) { port ->
            val old = ApiConfig.baseUrl
            try {
                ApiConfig.baseUrl = "http://127.0.0.1:$port"
                val report = repository.syncPending()
                assertEquals(1, report.success)
            } finally {
                ApiConfig.baseUrl = old
            }
        }.single()

        val body = JSONObject(captured.body)
        assertEquals("OUTBOUND", body.getString("type"))
        assertTrue("旧队列数据不应伪造 documentNo：$body", body.isNull("documentNo"))
        assertEquals("旧备注文本", body.getString("remark"))
        assertEquals(SyncStatus.SYNCED.name, dao.rows.single().status)
    }

    @Test
    fun onlineSubmitStillSendsDocumentNoAndRawRemark() = runBlocking {
        val dao = InMemoryOfflineDao()
        val repository = LogisticsRepository(MaterialFlowApi().also { it.updateToken("token") }, dao)
        val captured = captureRequests(1, TRANSFER_RESPONSE) { port ->
            val old = ApiConfig.baseUrl
            try {
                ApiConfig.baseUrl = "http://127.0.0.1:$port"
                val result = repository.submitOutbound(orderItem(), inventory(), documentNo, 5, "领料备注")
                assertTrue("在线提交应成功，实际 $result", result is SubmitResult.Success)
            } finally {
                ApiConfig.baseUrl = old
            }
        }.single()

        val body = JSONObject(captured.body)
        assertEquals("OUTBOUND", body.getString("type"))
        assertEquals(documentNo, body.getString("documentNo"))
        // 在线路径的 remark 是业务备注原文，不能变成打包 JSON。
        assertEquals("领料备注", body.getString("remark"))
        assertEquals(0, dao.rows.size)
    }

    // ---------------- 辅助 ----------------

    /**
     * P2-1：用户手输备注恰为合法 JSON（带 documentNo 键但无 v=1、也不是旧打包体三键形态），
     * 不能被当成打包体重放，否则单据会挂到错误的订单上。
     */
    @Test
    fun userRemarkThatLooksLikeJsonIsNotTreatedAsPackedBody() = runBlocking {
        val dao = InMemoryOfflineDao()
        val userRemark = """{"documentNo":"OM-9"}"""
        dao.insert(legacyOutbound(id = "user-json-op", remark = userRemark))
        val repository = LogisticsRepository(MaterialFlowApi().also { it.updateToken("token") }, dao)
        val captured = captureRequests(1, TRANSFER_RESPONSE) { port ->
            val old = ApiConfig.baseUrl
            try {
                ApiConfig.baseUrl = "http://127.0.0.1:$port"
                val report = repository.syncPending()
                assertEquals(1, report.success)
            } finally {
                ApiConfig.baseUrl = old
            }
        }.single()

        val body = JSONObject(captured.body)
        assertEquals("OUTBOUND", body.getString("type"))
        assertTrue("用户备注不是打包体，documentNo 不得被替换：$body", body.isNull("documentNo"))
        assertEquals("用户备注必须原样透传", userRemark, body.getString("remark"))
        assertEquals(SyncStatus.SYNCED.name, dao.rows.single().status)
    }

    /**
     * P2-2：打包体被写坏（DB 半写/人工改表）时，必须与 RETURN/RELOCATION 一致判 FAILED，
     * 不能降级成"旧裸文本"重放——那会丢 documentNo 且标 SYNCED，静默丢齐套数据。
     */
    @Test
    fun corruptedPackedRemarkIsMarkedFailedNotSynced() = runBlocking {
        val dao = InMemoryOfflineDao()
        // 以 { 开头、含 "documentNo" 键，但 JSON 截断（缺右花括号）无法解析。
        val broken = "{\"v\":1,\"documentNo\":\"OM-1\",\"remark\":\"领料备注\""
        dao.insert(legacyOutbound(id = "broken-op", remark = broken))
        val repository = LogisticsRepository(MaterialFlowApi().also { it.updateToken("token") }, dao)
        val old = ApiConfig.baseUrl
        try {
            // 指向拒连端口：若实现错误地走了"降级重放"，这里会变成 failed 但错误文案不是数据损坏；
            // 若实现正确，压根不会发起请求。
            ApiConfig.baseUrl = "http://127.0.0.1:1"
            val report = repository.syncPending()
            assertEquals(0, report.success)
            assertEquals(1, report.failed)
        } finally {
            ApiConfig.baseUrl = old
        }
        val row = dao.rows.single()
        assertEquals("损坏的打包体不得标 SYNCED", SyncStatus.FAILED.name, row.status)
        assertTrue("应给出队列数据损坏原因，实际 ${row.errorMessage}", row.errorMessage.orEmpty().contains("队列数据损坏"))
    }

    /** 升级前入队的旧打包体（无 v 字段，但 documentNo/sourceLocation/remark 三键齐全）仍要还原。 */
    @Test
    fun legacyPackedBodyWithoutVersionStillRestoresDocumentNo() = runBlocking {
        val dao = InMemoryOfflineDao()
        dao.insert(
            legacyOutbound(
                id = "legacy-packed-op",
                remark = """{"documentNo":"OM-3","sourceLocation":null,"remark":"旧打包备注"}""",
            )
        )
        val repository = LogisticsRepository(MaterialFlowApi().also { it.updateToken("token") }, dao)
        val captured = captureRequests(1, TRANSFER_RESPONSE) { port ->
            val old = ApiConfig.baseUrl
            try {
                ApiConfig.baseUrl = "http://127.0.0.1:$port"
                val report = repository.syncPending()
                assertEquals(1, report.success)
            } finally {
                ApiConfig.baseUrl = old
            }
        }.single()

        val body = JSONObject(captured.body)
        assertEquals("OM-3", body.optString("documentNo", ""))
        assertEquals("旧打包备注", body.getString("remark"))
        assertEquals(SyncStatus.SYNCED.name, dao.rows.single().status)
    }

    /** 队列实体：clientOperationId 需为合法 UUID（服务端按幂等键校验），故由 id 派生。 */
    private fun legacyOutbound(id: String, remark: String) = OfflineOperationEntity(
        id = id,
        clientOperationId = java.util.UUID.nameUUIDFromBytes(id.toByteArray()).toString(),
        materialCode = "MAT-1",
        operationType = OfflineOpType.OUTBOUND.name,
        status = SyncStatus.PENDING.name,
        createdAt = 2L,
        quantity = 3,
        materialId = "mat-1",
        expectedInventoryVersion = 1,
        remark = remark,
    )

    private fun orderItem() = OrderMaterialItem(
        deviceId = "dev-1",
        deviceType = "MACHINE",
        deviceNo = "OM-1-M1",
        materialId = "mat-1",
        materialCode = "MAT-1",
        name = "螺栓",
        specification = null,
        requiredQuantity = 10,
        arrivedQuantity = 10,
        inStockQuantity = 10,
        statusCode = MaterialStatusCode.IN_STOCK,
        label = "已入库",
        colorToken = "success",
    )

    private fun inventory() = MaterialInventory(
        material = Material(id = "mat-1", code = "MAT-1", name = "螺栓"),
        inventory = Inventory(
            totalQuantity = 100,
            availableQuantity = 100,
            locations = listOf(LocationStock("A-01", 100)),
        ),
        version = 1,
    )

    private data class CapturedRequest(val requestLine: String, val body: String)

    private suspend fun captureRequests(
        count: Int,
        response: String,
        call: suspend (Int) -> Unit,
    ): List<CapturedRequest> {
        ServerSocket(0).use { server ->
            val captured = mutableListOf<CapturedRequest>()
            val worker = Thread {
                repeat(count) {
                    server.accept().use { socket ->
                        captured += readRequest(socket)
                        val bytes = response.toByteArray(Charsets.UTF_8)
                        val output = socket.getOutputStream()
                        output.bufferedWriter().apply {
                            write("HTTP/1.1 200 OK\r\n")
                            write("Content-Type: application/json\r\n")
                            write("Content-Length: ${bytes.size}\r\n")
                            write("Connection: close\r\n\r\n")
                            flush()
                        }
                        output.write(bytes)
                        output.flush()
                    }
                }
            }.apply { isDaemon = true }
            worker.start()
            call(server.localPort)
            worker.join(5_000)
            check(captured.size == count) { "Expected $count HTTP requests, captured ${captured.size}" }
            return captured.toList()
        }
    }

    private fun readRequest(socket: Socket): CapturedRequest {
        val input = java.io.BufferedInputStream(socket.getInputStream())
        fun readLine(): String {
            val bytes = java.io.ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0 || b == '\n'.code) break
                if (b != '\r'.code) bytes.write(b)
            }
            return bytes.toString(Charsets.UTF_8.name())
        }
        val requestLine = readLine()
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine()
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator > 0) headers[line.substring(0, separator)] = line.substring(separator + 1).trim()
        }
        val length = headers["Content-Length"]?.toIntOrNull() ?: 0
        val body = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(body, offset, length - offset)
            if (read < 0) break
            offset += read
        }
        return CapturedRequest(requestLine, String(body, 0, offset, Charsets.UTF_8))
    }

    private class InMemoryOfflineDao : OfflineOperationDao {
        private val state = MutableStateFlow<List<OfflineOperationEntity>>(emptyList())
        val rows: List<OfflineOperationEntity> get() = state.value

        override fun observeAll(): Flow<List<OfflineOperationEntity>> = state
        override suspend fun pending() = state.value
            .filter { it.status == SyncStatus.PENDING.name }
            .sortedBy { it.createdAt }
            .take(20)
        override fun observePendingCount() = flowOf(state.value.count {
            it.status == SyncStatus.PENDING.name || it.status == SyncStatus.FAILED.name || it.status == SyncStatus.SYNCING.name
        })
        override fun observeAttentionCount() = flowOf(state.value.count {
            it.status == SyncStatus.FAILED.name || it.status == SyncStatus.CONFLICT.name
        })
        override suspend fun insert(operation: OfflineOperationEntity): Long {
            if (state.value.any { it.id == operation.id }) return -1L
            state.value = state.value + operation
            return 1L
        }
        override suspend fun updateStatus(id: String, status: String, error: String?) {
            state.value = state.value.map { if (it.id == id) it.copy(status = status, errorMessage = error) else it }
        }
        override suspend fun markSynced(id: String, status: String, serverTime: String?) {
            state.value = state.value.map {
                if (it.id == id) it.copy(status = status, serverTime = serverTime, errorMessage = null) else it
            }
        }
        override suspend fun clearSynced() {
            state.value = state.value.filter { it.status != SyncStatus.SYNCED.name }
        }
        override suspend fun findById(id: String) = state.value.firstOrNull { it.id == id }
        override suspend fun resetStuckSyncing(): Int {
            val stuck = state.value.count { it.status == SyncStatus.SYNCING.name }
            state.value = state.value.map {
                if (it.status == SyncStatus.SYNCING.name) it.copy(status = SyncStatus.PENDING.name, errorMessage = null) else it
            }
            return stuck
        }
        override suspend fun resetToPending(id: String) {
            state.value = state.value.map {
                if (it.id == id) it.copy(status = SyncStatus.PENDING.name, errorMessage = null) else it
            }
        }
        override suspend fun deleteById(id: String) {
            state.value = state.value.filterNot { it.id == id }
        }
    }

    private companion object {
        const val TRANSFER_RESPONSE = """{"requestId":"tr-1","status":"PENDING","serverTime":"2026-10-20T00:00:00Z"}"""
    }
}
