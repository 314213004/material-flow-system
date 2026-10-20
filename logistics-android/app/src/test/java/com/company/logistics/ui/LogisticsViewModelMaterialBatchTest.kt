package com.company.logistics.ui

import com.company.logistics.data.LogisticsRepository
import com.company.logistics.data.OfflineOperationDao
import com.company.logistics.data.OfflineOperationEntity
import com.company.logistics.data.SubmitResult
import com.company.logistics.data.remote.MaterialFlowApi
import com.company.logistics.model.AssemblyTaskPage
import com.company.logistics.model.Inventory
import com.company.logistics.model.MachineProgressPage
import com.company.logistics.model.WorkshopProgressSummary
import com.company.logistics.model.LocationStock
import com.company.logistics.model.Material
import com.company.logistics.model.MaterialBatchAction
import com.company.logistics.model.MaterialBatchLine
import com.company.logistics.model.MaterialInventory
import com.company.logistics.model.MaterialStatusCode
import com.company.logistics.model.OrderMaterialItem
import com.company.logistics.model.RoleSummary
import com.company.logistics.model.User
import com.company.logistics.model.UserRole
import com.company.logistics.model.WorkspaceMaterialItemsPage
import com.company.logistics.model.WorkspaceSummary
import com.company.logistics.model.WorkspaceViewRole
import com.company.logistics.model.batchKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.5.39 订单页 / 机台页物料勾选批量操作：出库申请 / 退料 / 厂内调拨 / 异常提报。 */
class LogisticsViewModelMaterialBatchTest {

    @Test
    fun outboundBatchReportsPerItemPartialFailureAndKeepsSuccesses() {
        val (scope, repo, vm) = setup(UserRole.OPERATOR)
        vm.submitMaterialBatch(
            action = MaterialBatchAction.OUTBOUND,
            lines = listOf(MaterialBatchLine(item("M-1"), 3), MaterialBatchLine(item("M-MISSING"), 2), MaterialBatchLine(item("M-2"), 1)),
            orderNo = "WO-1",
            remark = "急用",
        )
        assertEquals(listOf("M-1:3", "M-2:1"), repo.outboundCalls)
        val state = vm.state.value
        assertTrue(state.message!!.contains("已提交 2 条出库申请"))
        assertTrue(state.error!!.contains("M-MISSING：查无库存"))
        assertFalse(state.batchSubmitting)
        assertTrue(repo.remarks.all { it!!.startsWith("机台：DEV-NO；") && it.endsWith("急用") })
        scope.cancel()
    }

    @Test
    fun legacyDeviceBatchStillCreatesOutboundWithRequiredQuantity() {
        val (scope, repo, vm) = setup(UserRole.MATERIAL)
        vm.submitDeviceMaterialRequestBatch(listOf(item("M-1", required = 7)), "WO-1", "")
        assertEquals(listOf("M-1:7"), repo.outboundCalls)
        scope.cancel()
    }

    @Test
    fun relocationRequiresTargetAndPicksSourceWithEnoughStock() {
        val (scope, repo, vm) = setup(UserRole.WAREHOUSE_ADMIN)
        vm.submitMaterialBatch(MaterialBatchAction.RELOCATION, listOf(MaterialBatchLine(item("M-1"), 5)), "WO-1", "")
        assertTrue(vm.state.value.error!!.contains("目标库位"))
        assertTrue(repo.relocationCalls.isEmpty())

        vm.submitMaterialBatch(MaterialBatchAction.RELOCATION, listOf(MaterialBatchLine(item("M-1"), 5)), "WO-1", "", targetLocation = "LOC-T")
        // LOC-A 只有 2，LOC-B 有 10：自动选 LOC-B
        assertEquals(listOf("M-1:5:LOC-B->LOC-T"), repo.relocationCalls)
        assertTrue(vm.state.value.message!!.contains("已提交 1 条厂内调拨"))
        scope.cancel()
    }

    @Test
    fun returnPassesOptionalTargetAndCountsQueuedAsSubmitted() {
        val (scope, repo, vm) = setup(UserRole.OPERATOR)
        repo.queueReturns = true
        vm.submitMaterialBatch(MaterialBatchAction.RETURN, listOf(MaterialBatchLine(item("M-1"), 4)), "WO-1", "", targetLocation = " ")
        assertEquals(listOf("M-1:4:null"), repo.returnCalls)
        val message = vm.state.value.message!!
        assertTrue(message.contains("已提交 1 条退料"))
        assertTrue(message.contains("入队"))
        assertNull(vm.state.value.error)
        scope.cancel()
    }

    @Test
    fun exceptionBatchNeedsDescriptionAndUsesRowDeviceThenFallback() {
        val (scope, repo, vm) = setup(UserRole.WORKSHOP_SUPERVISOR)
        val withDevice = item("M-1", deviceId = "od-1", inStock = 9)
        val withoutDevice = item("M-2", deviceId = null, inStock = 4)
        vm.submitMaterialBatch(MaterialBatchAction.EXCEPTION, listOf(MaterialBatchLine(withDevice, 8)), "WO-1", "  ")
        assertTrue(vm.state.value.error!!.contains("描述"))
        assertTrue(repo.exceptionCalls.isEmpty())

        vm.submitMaterialBatch(
            MaterialBatchAction.EXCEPTION,
            listOf(MaterialBatchLine(withDevice, 8), MaterialBatchLine(withoutDevice, 0)),
            "WO-1", "外箱破损", exceptionType = "DAMAGE", fallbackDeviceId = "dev-1",
        )
        assertEquals(listOf("M-1:od-1:DAMAGE:9->8", "M-2:dev-1:DAMAGE:4->0"), repo.exceptionCalls)
        assertTrue(vm.state.value.message!!.contains("已提交 2 条异常提报"))
        scope.cancel()
    }

    @Test
    fun roleWithoutPermissionAndInvalidQuantityNeverReachRepository() {
        val (scope, repo, vm) = setup(UserRole.ASSEMBLER)
        vm.submitMaterialBatch(MaterialBatchAction.OUTBOUND, listOf(MaterialBatchLine(item("M-1"), 1)), "WO-1", "")
        assertTrue(vm.state.value.error!!.contains("当前角色不能发起出库申请"))
        scope.cancel()

        val (scope2, repo2, vm2) = setup(UserRole.OPERATOR)
        vm2.submitMaterialBatch(MaterialBatchAction.OUTBOUND, listOf(MaterialBatchLine(item("M-1"), 0)), "WO-1", "")
        assertTrue(vm2.state.value.error!!.contains("正整数"))
        vm2.submitMaterialBatch(MaterialBatchAction.OUTBOUND, emptyList(), "WO-1", "")
        assertTrue(vm2.state.value.error!!.contains("请勾选物料"))
        assertEquals(0, repo.totalCalls + repo2.totalCalls)
        scope2.cancel()
    }

    @Test
    fun adminRolePreviewIsReadOnlyForBatchActions() {
        val (scope, repo, vm) = setup(UserRole.ADMIN)
        vm.enterRolePreview(WorkspaceViewRole.MATERIAL)
        vm.submitMaterialBatch(MaterialBatchAction.OUTBOUND, listOf(MaterialBatchLine(item("M-1"), 1)), "WO-1", "")
        assertTrue(vm.state.value.error!!.contains("只读"))
        assertEquals(0, repo.totalCalls)
        scope.cancel()
    }

    @Test
    fun batchKeySeparatesSameMaterialOnDifferentDevices() {
        assertNotEquals(item("M-1", deviceId = "od-1").batchKey, item("M-1", deviceId = "od-2").batchKey)
        assertTrue(MaterialBatchAction.availableFor(UserRole.ASSEMBLER).isEmpty())
        assertEquals(listOf(MaterialBatchAction.EXCEPTION), MaterialBatchAction.availableFor(UserRole.WORKSHOP_SUPERVISOR))
        assertEquals("TRANSFER", MaterialBatchAction.RELOCATION.code)
    }

    private data class Setup(val scope: CoroutineScope, val repo: FakeBatchRepository, val vm: LogisticsViewModel)

    private fun setup(role: UserRole): Setup {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repo = FakeBatchRepository(role)
        val vm = LogisticsViewModel(repo, scope)
        vm.login("user", "password", "device", remember = false)
        return Setup(scope, repo, vm)
    }

    private fun item(
        code: String,
        required: Int = 1,
        deviceId: String? = "od-1",
        inStock: Int = 1,
    ) = OrderMaterialItem(
        deviceId = deviceId,
        deviceType = "机台",
        deviceNo = "DEV-NO",
        materialId = "id-$code",
        materialCode = code,
        name = "物料$code",
        specification = null,
        requiredQuantity = required,
        arrivedQuantity = required,
        inStockQuantity = inStock,
        statusCode = MaterialStatusCode.IN_STOCK,
        label = "在库",
        colorToken = "STATUS_GREEN",
    )

    private class FakeBatchRepository(private val role: UserRole) : LogisticsRepository(MaterialFlowApi(), NoOpDao()) {
        val outboundCalls = mutableListOf<String>()
        val returnCalls = mutableListOf<String>()
        val relocationCalls = mutableListOf<String>()
        val exceptionCalls = mutableListOf<String>()
        val remarks = mutableListOf<String?>()
        var queueReturns = false
        val totalCalls: Int get() = outboundCalls.size + returnCalls.size + relocationCalls.size + exceptionCalls.size

        override suspend fun login(username: String, password: String, deviceId: String, remember: Boolean) =
            com.company.logistics.model.LoginResult(
                accessToken = "access",
                refreshToken = null,
                expiresAt = null,
                mustChangePassword = false,
                user = User("u-${role.name.lowercase()}", username, role.label, role),
            )

        override suspend fun workspaceSummary(): Result<WorkspaceSummary> = Result.success(WorkspaceSummary(role = role))

        override suspend fun workspaceMaterialItems(status: String?, orderNo: String?, page: Int, pageSize: Int): Result<WorkspaceMaterialItemsPage> =
            Result.success(emptyPage(page, pageSize))

        override suspend fun loadRoleSummary(viewRole: WorkspaceViewRole?, requestId: String): Result<RoleSummary> =
            Result.success(WorkspaceSummary(role = viewRole?.toUserRole() ?: role, preview = viewRole != null, authenticatedRole = role))

        override suspend fun listWorkItems(
            viewRole: WorkspaceViewRole?,
            status: String?,
            orderNo: String?,
            page: Int,
            pageSize: Int,
            requestId: String,
        ): Result<WorkspaceMaterialItemsPage> = Result.success(emptyPage(page, pageSize))

        // 装配工 / 车间主管登录后的工作台加载：返回非 401 失败，避免回落真实网络
        override suspend fun assemblyTaskPage(page: Int, pageSize: Int, deviceNo: String?, deviceId: String?): Result<AssemblyTaskPage> =
            Result.failure(RuntimeException("offline"))

        override suspend fun workshopProgressSummary(from: String?, to: String?): Result<WorkshopProgressSummary> =
            Result.failure(RuntimeException("offline"))

        override suspend fun workshopMachineProgress(page: Int, pageSize: Int): Result<MachineProgressPage> =
            Result.failure(RuntimeException("offline"))

        override suspend fun materialInventory(code: String): Result<MaterialInventory> =
            if (code == "M-MISSING") Result.failure(RuntimeException("404"))
            else Result.success(
                MaterialInventory(
                    material = Material("id-$code", code, "物料$code"),
                    inventory = Inventory(12, 12, 0, listOf(LocationStock("LOC-A", 2), LocationStock("LOC-B", 10))),
                    version = 3,
                )
            )

        override suspend fun submitOutbound(
            deviceMaterial: OrderMaterialItem,
            inventory: MaterialInventory,
            documentNo: String?,
            quantity: Int,
            remark: String?,
            clientOperationId: String?,
            evidenceIds: List<String>,
        ): SubmitResult {
            outboundCalls += "${deviceMaterial.materialCode}:$quantity"
            remarks += remark
            return SubmitResult.Success(null, null)
        }

        override suspend fun submitReturn(
            deviceMaterial: OrderMaterialItem,
            inventory: MaterialInventory,
            documentNo: String?,
            quantity: Int,
            targetLocation: String?,
            remark: String?,
            clientOperationId: String?,
            evidenceIds: List<String>,
        ): SubmitResult {
            returnCalls += "${deviceMaterial.materialCode}:$quantity:$targetLocation"
            return if (queueReturns) SubmitResult.Queued("op-1", "网络不可用") else SubmitResult.Success(null, null)
        }

        override suspend fun submitRelocation(
            deviceMaterial: OrderMaterialItem,
            inventory: MaterialInventory,
            documentNo: String?,
            quantity: Int,
            sourceLocation: String,
            targetLocation: String,
            remark: String?,
            clientOperationId: String?,
            evidenceIds: List<String>,
        ): SubmitResult {
            relocationCalls += "${deviceMaterial.materialCode}:$quantity:$sourceLocation->$targetLocation"
            return SubmitResult.Success(null, null)
        }

        override suspend fun submitExceptionQueued(
            materialCode: String,
            materialId: String,
            orderNo: String,
            deviceId: String,
            type: String,
            bookQuantity: Int,
            actualQuantity: Int,
            description: String,
            clientOperationId: String?,
            evidenceIds: List<String>,
        ): SubmitResult {
            exceptionCalls += "$materialCode:$deviceId:$type:$bookQuantity->$actualQuantity"
            return SubmitResult.Success(null, null)
        }

        private fun emptyPage(page: Int, pageSize: Int) = WorkspaceMaterialItemsPage(
            items = emptyList(), page = page, pageSize = pageSize, total = 0, totalPages = 0,
            serverTime = null, traceId = null,
        )
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
