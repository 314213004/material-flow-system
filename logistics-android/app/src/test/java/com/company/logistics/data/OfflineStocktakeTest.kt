package com.company.logistics.data

import com.company.logistics.data.remote.ApiConfig
import com.company.logistics.data.remote.MaterialFlowApi
import com.company.logistics.model.OfflineOpType
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
 * 盘点离线队列（0.5.45）：断网提交的盘点单进队列（STOCKTAKE），联网后按幂等键重放，
 * 实盘数量与库位原样带上。
 */
class OfflineStocktakeTest {

    @Test
    fun offlineStocktakeIsQueuedAndReplayedWithSamePayload() = runBlocking {
        val dao = RecordingDao()
        val repo = LogisticsRepository(MaterialFlowApi().also { it.updateToken("token") }, dao)

        val old = ApiConfig.baseUrl
        val result = try {
            ApiConfig.baseUrl = "http://127.0.0.1:1"
            repo.submitStocktakeQueued("MAT-1", "A-01", 37)
        } finally {
            ApiConfig.baseUrl = old
        }
        assertTrue("应离线入队，实际 $result", result is SubmitResult.Queued)
        val row = dao.rows.single()
        assertEquals(OfflineOpType.STOCKTAKE.name, row.operationType)
        assertEquals(37, row.quantity)
        assertEquals("A-01", row.targetLocation)

        val body = replayOne(repo, dao)
        assertEquals("MAT-1", body.getString("materialCode"))
        assertEquals(37, body.getInt("actualQuantity"))
        assertEquals("A-01", body.getString("locationCode"))
        assertTrue("幂等键必须随重放带上", body.getString("clientOperationId").isNotBlank())
        assertEquals(SyncStatus.SYNCED.name, dao.rows.single().status)
    }

    @Test
    fun stocktakeWithoutLocationSendsNullLocation() = runBlocking {
        val dao = RecordingDao()
        val repo = LogisticsRepository(MaterialFlowApi().also { it.updateToken("token") }, dao)
        val old = ApiConfig.baseUrl
        try {
            ApiConfig.baseUrl = "http://127.0.0.1:1"
            repo.submitStocktakeQueued("MAT-2", null, 0)
        } finally {
            ApiConfig.baseUrl = old
        }
        val body = replayOne(repo, dao)
        assertEquals("MAT-2", body.getString("materialCode"))
        assertTrue("留空库位应为 null", body.isNull("locationCode"))
        assertEquals(0, body.getInt("actualQuantity"))
    }

    // ---------------- 辅助 ----------------

    private suspend fun replayOne(repo: LogisticsRepository, dao: RecordingDao): JSONObject {
        val body = ServerSocket(0).use { server ->
            var captured = ""
            val worker = Thread {
                server.accept().use { socket ->
                    captured = readRequest(socket)
                    respond(socket)
                }
            }.apply { isDaemon = true }
            worker.start()
            val old = ApiConfig.baseUrl
            try {
                ApiConfig.baseUrl = "http://127.0.0.1:${server.localPort}"
                val report = repo.syncPending()
                assertEquals("重放应成功：$report", 1, report.success)
            } finally {
                ApiConfig.baseUrl = old
            }
            worker.join(5_000)
            captured
        }
        return JSONObject(body)
    }

    private fun readRequest(socket: Socket): String {
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
        readLine()
        var length = 0
        while (true) {
            val line = readLine()
            if (line.isEmpty()) break
            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                length = line.substringAfter(':').trim().toIntOrNull() ?: 0
            }
        }
        val body = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(body, offset, length - offset)
            if (read < 0) break
            offset += read
        }
        return String(body, 0, offset, Charsets.UTF_8)
    }

    private fun respond(socket: Socket) {
        val body = """{"stocktakeId":"st_1","status":"PENDING_CONFIRM","difference":3,"serverTime":"2026-10-20T00:00:00Z"}"""
            .toByteArray(Charsets.UTF_8)
        socket.getOutputStream().apply {
            write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n".toByteArray())
            write("Content-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(body)
            flush()
        }
    }

    private class RecordingDao : OfflineOperationDao {
        private val state = MutableStateFlow<List<OfflineOperationEntity>>(emptyList())
        val rows: List<OfflineOperationEntity> get() = state.value

        override fun observeAll(): Flow<List<OfflineOperationEntity>> = state
        override suspend fun pending() = state.value
            .filter { it.status == SyncStatus.PENDING.name }
            .sortedBy { it.createdAt }
            .take(20)
        override fun observePendingCount() = flowOf(state.value.count {
            it.status in setOf(SyncStatus.PENDING.name, SyncStatus.FAILED.name, SyncStatus.SYNCING.name)
        })
        override fun observeAttentionCount() = flowOf(state.value.count {
            it.status in setOf(SyncStatus.FAILED.name, SyncStatus.CONFLICT.name)
        })
        override suspend fun insert(operation: OfflineOperationEntity): Long {
            if (state.value.any { it.id == operation.id }) return -1L
            state.value = state.value + operation
            return 1L
        }
        override suspend fun updateStatus(id: String, status: String, error: String?) {
            state.value = state.value.map { if (it.id == id) it.copy(status = status, errorMessage = error) else it }
        }
        override suspend fun scheduleRetry(
            id: String, status: String, error: String?, retryCount: Int, nextRetryAt: Long?
        ) {
            state.value = state.value.map {
                if (it.id == id) it.copy(status = status, errorMessage = error,
                    retryCount = retryCount, nextRetryAt = nextRetryAt) else it
            }
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
        override suspend fun resetStuckSyncing(): Int = 0
        override suspend fun resetToPending(id: String) {
            state.value = state.value.map {
                if (it.id == id) it.copy(status = SyncStatus.PENDING.name, errorMessage = null) else it
            }
        }
        override suspend fun deleteById(id: String) {
            state.value = state.value.filterNot { it.id == id }
        }
    }
}
