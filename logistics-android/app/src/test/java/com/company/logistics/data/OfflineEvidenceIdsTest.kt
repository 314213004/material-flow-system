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
 * 图片凭证随离线队列一起保存并重放（0.5.45）。
 *
 * 之前 uploadFile 全仓零调用、evidenceIds 恒为空：现场提交的出入库/异常单据没有凭证可查。
 * 这里锁定：离线入队带凭证 → 联网重放时请求体 evidenceIds 原样还原；老队列（无该键）不报错。
 */
class OfflineEvidenceIdsTest {

    @Test
    fun offlineOutboundReplayCarriesEvidenceIds() = runBlocking {
        val dao = RecordingDao()
        val repo = LogisticsRepository(MaterialFlowApi().also { it.updateToken("token") }, dao)
        val queued = offline { repo.submitOutbound(orderItem(), inventory(), "OM-1", 5, "领料备注",
            evidenceIds = listOf("file-1", "file-2")) }
        assertTrue("应离线入队，实际 $queued", queued is SubmitResult.Queued)
        val packed = JSONObject(dao.rows.single().remark!!)
        assertEquals(2, packed.getJSONArray("evidenceIds").length())

        val body = replayOne(repo, dao)
        val ids = body.getJSONArray("evidenceIds")
        assertEquals(listOf("file-1", "file-2"), (0 until ids.length()).map { ids.getString(it) })
        assertEquals(SyncStatus.SYNCED.name, dao.rows.single().status)
    }

    @Test
    fun offlineExceptionReplayCarriesEvidenceIds() = runBlocking {
        val dao = RecordingDao()
        val repo = LogisticsRepository(MaterialFlowApi().also { it.updateToken("token") }, dao)
        val queued = offline {
            repo.submitExceptionQueued(
                materialCode = "MAT-1", materialId = "mat-1", orderNo = "OM-1", deviceId = "dev-1",
                type = "SHORTAGE", bookQuantity = 10, actualQuantity = 8, description = "少 2 件",
                evidenceIds = listOf("file-9"),
            )
        }
        assertTrue("应离线入队，实际 $queued", queued is SubmitResult.Queued)

        val body = replayOne(repo, dao)
        val ids = body.getJSONArray("evidenceIds")
        assertEquals(listOf("file-9"), (0 until ids.length()).map { ids.getString(it) })
        assertEquals(SyncStatus.SYNCED.name, dao.rows.single().status)
    }

    @Test
    fun legacyQueuedBodyWithoutEvidenceIdsReplaysWithEmptyList() = runBlocking {
        val dao = RecordingDao()
        dao.insert(
            OfflineOperationEntity(
                id = "legacy-evidence",
                clientOperationId = "55555555-5555-5555-5555-555555555555",
                materialCode = "MAT-1",
                operationType = OfflineOpType.OUTBOUND.name,
                status = SyncStatus.PENDING.name,
                createdAt = 1L,
                quantity = 3,
                materialId = "mat-1",
                expectedInventoryVersion = 1,
                // 升级前的打包体：没有 evidenceIds 键
                remark = """{"v":1,"documentNo":"OM-1","sourceLocation":null,"remark":"旧备注"}""",
            )
        )
        val repo = LogisticsRepository(MaterialFlowApi().also { it.updateToken("token") }, dao)
        val body = replayOne(repo, dao)
        assertEquals(0, body.getJSONArray("evidenceIds").length())
        assertEquals("OM-1", body.optString("documentNo", ""))
    }

    // ---------------- 辅助 ----------------

    private suspend fun <T> offline(block: suspend () -> T): T {
        val old = ApiConfig.baseUrl
        return try {
            ApiConfig.baseUrl = "http://127.0.0.1:1"
            block()
        } finally {
            ApiConfig.baseUrl = old
        }
    }

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
        val body = """{"requestId":"r-1","status":"PENDING","serverTime":"2026-10-20T00:00:00Z"}"""
            .toByteArray(Charsets.UTF_8)
        socket.getOutputStream().apply {
            write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n".toByteArray())
            write("Content-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(body)
            flush()
        }
    }

    private fun orderItem() = OrderMaterialItem(
        deviceId = "dev-1", deviceType = "MACHINE", deviceNo = "OM-1-M1",
        materialId = "mat-1", materialCode = "MAT-1", name = "螺栓",
        specification = null, requiredQuantity = 10, arrivedQuantity = 10,
        inStockQuantity = 10, statusCode = MaterialStatusCode.IN_STOCK,
        label = "已入库", colorToken = "success",
    )

    private fun inventory() = MaterialInventory(
        material = Material(id = "mat-1", code = "MAT-1", name = "螺栓"),
        inventory = Inventory(totalQuantity = 100, availableQuantity = 100,
            locations = listOf(LocationStock("A-01", 100))),
        version = 1,
    )

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
