package com.company.logistics.data

import com.company.logistics.data.remote.ApiConfig
import com.company.logistics.data.remote.MaterialFlowApi
import com.company.logistics.model.MaterialStatusCode
import com.company.logistics.model.OfflineOpType
import com.company.logistics.model.OrderMaterialItem
import com.company.logistics.model.SyncStatus
import com.company.logistics.model.Inventory
import com.company.logistics.model.LocationStock
import com.company.logistics.model.Material
import com.company.logistics.model.MaterialInventory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket

/**
 * 离线队列自动重试策略（0.5.45）。
 *
 * 旧行为：FAILED 记录也塞进每轮 pending()，断网时无限撞服务端，且失败原因永远是"网络不可用"。
 * 新行为：失败按 30s/1min/2min/4min 退避，累计 5 次转 FAILED 等人工处理，退避未到期不重放。
 */
class OfflineRetryPolicyTest {

    private val documentNo = "OM-1"

    @Test
    fun firstFailureSchedulesBackoffInsteadOfTerminalFailed() = runBlocking {
        val dao = RecordingDao()
        val repo = LogisticsRepository(MaterialFlowApi().also { it.updateToken("token") }, dao)
        offline(queue(repo, dao))

        val report = withOffline { repo.syncPending() }
        assertEquals("第一次失败应排入自动重试", 1, report.retrying)
        assertEquals(0, report.failed)
        val row = dao.rows.single()
        assertEquals(SyncStatus.PENDING.name, row.status)
        assertEquals(1, row.retryCount)
        assertNotNull("必须写入下次重试时间", row.nextRetryAt)
        assertTrue("重试时间应在未来：${row.nextRetryAt}", (row.nextRetryAt ?: 0) > System.currentTimeMillis())
        assertTrue("提示要说明会自动重试：${row.errorMessage}",
            row.errorMessage.orEmpty().contains("自动重试"))
    }

    @Test
    fun notDueYetIsSkippedWithoutTouchingServer() = runBlocking {
        val dao = RecordingDao()
        val repo = LogisticsRepository(MaterialFlowApi().also { it.updateToken("token") }, dao)
        offline(queue(repo, dao))
        withOffline { repo.syncPending() }
        val afterFirst = dao.rows.single()

        // 退避未到期：这一轮不应发起任何请求，重试计数也不变
        val hits = withServer(0) { repo.syncPending() }
        assertEquals(0, hits)
        assertEquals(afterFirst.retryCount, dao.rows.single().retryCount)
        assertEquals(SyncStatus.PENDING.name, dao.rows.single().status)
    }

    @Test
    fun dueRecordIsRetriedAndCountsUp() = runBlocking {
        val dao = RecordingDao()
        val repo = LogisticsRepository(MaterialFlowApi().also { it.updateToken("token") }, dao)
        offline(queue(repo, dao))
        withOffline { repo.syncPending() }

        // 把下次重试时间拨到过去，模拟退避到期
        val row = dao.rows.single()
        dao.forceDue(row.id)
        val report = withOffline { repo.syncPending() }
        assertEquals(1, report.retrying)
        assertEquals(2, dao.rows.single().retryCount)
    }

    @Test
    fun fifthFailureBecomesTerminalAndLeavesTheQueue() = runBlocking {
        val dao = RecordingDao()
        val repo = LogisticsRepository(MaterialFlowApi().also { it.updateToken("token") }, dao)
        offline(queue(repo, dao))
        withOffline { repo.syncPending() }
        // 连撞到第 5 次
        repeat(3) {
            dao.forceDue(dao.rows.single().id)
            withOffline { repo.syncPending() }
        }
        assertEquals(4, dao.rows.single().retryCount)

        dao.forceDue(dao.rows.single().id)
        val report = withOffline { repo.syncPending() }
        assertEquals("第 5 次失败转终态", 1, report.failed)
        assertEquals(0, report.retrying)
        val row = dao.rows.single()
        assertEquals(SyncStatus.FAILED.name, row.status)
        assertTrue("应说明自动重试已用尽：${row.errorMessage}",
            row.errorMessage.orEmpty().contains("已自动重试 5 次"))
        assertNull("终态不再排队", dao.pending().firstOrNull())
    }

    @Test
    fun failedRecordsAreNotRetriedByPending() = runBlocking {
        val dao = RecordingDao()
        dao.insert(entity(id = "terminal", status = SyncStatus.FAILED.name, remark = "旧备注"))
        assertTrue("FAILED 是终态，不该再被 pending() 取到", dao.pending().isEmpty())
    }

    // ---------------- 辅助 ----------------

    private suspend fun offline(queue: SubmitResult) {
        assertTrue("应离线入队，实际 $queue", queue is SubmitResult.Queued)
    }

    private suspend fun queue(repo: LogisticsRepository, dao: RecordingDao): SubmitResult {
        val old = ApiConfig.baseUrl
        return try {
            ApiConfig.baseUrl = "http://127.0.0.1:1"
            repo.submitOutbound(orderItem(), inventory(), documentNo, 5, "领料备注")
        } finally {
            ApiConfig.baseUrl = old
        }
    }

    private suspend fun <T> withOffline(block: suspend () -> T): T {
        val old = ApiConfig.baseUrl
        return try {
            ApiConfig.baseUrl = "http://127.0.0.1:1"
            block()
        } finally {
            ApiConfig.baseUrl = old
        }
    }

    private suspend fun withServer(expected: Int, block: suspend () -> SyncReport): Int {
        ServerSocket(0).use { server ->
            var hits = 0
            val worker = Thread {
                while (hits < expected) {
                    server.accept().use { socket -> readAndRespond(socket) }
                    hits++
                }
            }.apply { isDaemon = true }
            worker.start()
            val old = ApiConfig.baseUrl
            try {
                ApiConfig.baseUrl = "http://127.0.0.1:${server.localPort}"
                block()
            } finally {
                ApiConfig.baseUrl = old
            }
            worker.join(1_000)
            return hits
        }
    }

    private fun readAndRespond(socket: Socket) {
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
        var length = 0
        readLine()  // request line
        while (true) {
            val line = readLine()
            if (line.isEmpty()) break
            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                length = line.substringAfter(':').trim().toIntOrNull() ?: 0
            }
        }
        repeat(length) { input.read() }
        val body = """{"requestId":"tr-1","status":"PENDING","serverTime":"2026-10-20T00:00:00Z"}"""
            .toByteArray(Charsets.UTF_8)
        socket.getOutputStream().apply {
            write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n".toByteArray())
            write("Content-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(body)
            flush()
        }
    }

    private fun entity(id: String, status: String, remark: String) = OfflineOperationEntity(
        id = id,
        clientOperationId = java.util.UUID.nameUUIDFromBytes(id.toByteArray()).toString(),
        materialCode = "MAT-1",
        operationType = OfflineOpType.OUTBOUND.name,
        status = status,
        createdAt = 1L,
        quantity = 3,
        materialId = "mat-1",
        expectedInventoryVersion = 1,
        remark = remark,
    )

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

    /** 记录型 DAO：实现与 Room 查询同语义，额外支持把 nextRetryAt 拨到过去。 */
    private class RecordingDao : OfflineOperationDao {
        private val state = MutableStateFlow<List<OfflineOperationEntity>>(emptyList())
        val rows: List<OfflineOperationEntity> get() = state.value

        fun forceDue(id: String) {
            state.value = state.value.map { if (it.id == id) it.copy(nextRetryAt = 1L) else it }
        }

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
}
