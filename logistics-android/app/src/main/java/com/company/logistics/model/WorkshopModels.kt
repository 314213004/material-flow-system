package com.company.logistics.model

/** Assembly workflow models. Server timestamps remain the source of labor truth. */
enum class AssemblyTaskStatus(val code: String, val label: String) {
    WAITING_MATERIAL("WAITING_MATERIAL", "待接受物料"),
    MATERIAL_ACCEPTED("MATERIAL_ACCEPTED", "物料已接受"),
    IN_PROGRESS("IN_PROGRESS", "装配中"),
    PAUSED_FOR_TEMPORARY_TRANSFER("PAUSED_FOR_TEMPORARY_TRANSFER", "临时调拨暂停"),
    COMPLETED("COMPLETED", "已完工");
    companion object { fun from(value: String?) = entries.firstOrNull { it.code.equals(value, true) } ?: WAITING_MATERIAL }
}

enum class LaborType { ASSEMBLY, TEMPORARY_TRANSFER }

enum class AssemblyAction(val label: String) {
    ACCEPT_MATERIAL("接受物料"),
    START_WORK("开工"),
    PROGRESS("提交进度"),
    COMPLETE_WORK("完工"),
    START_STAGE("阶段开工"),
    COMPLETE_STAGE("阶段完工"),
    REWORK_STAGE("返工"),
}

enum class AssemblyStageStatus(val code: String, val label: String) {
    NOT_STARTED("NOT_STARTED", "未开始"), IN_PROGRESS("IN_PROGRESS", "进行中"),
    COMPLETED("COMPLETED", "已完成"), REWORK_REQUIRED("REWORK_REQUIRED", "待返工");
    companion object { fun from(value: String?) = entries.firstOrNull { it.code.equals(value, true) } ?: NOT_STARTED }
}

data class AssemblyStage(
    val stageNo: Int, val status: AssemblyStageStatus, val version: Int = 1,
    val startedAt: String? = null, val completedAt: String? = null, val reworkReason: String? = null,
)

data class AssemblyStageOperationResult(
    val taskId: String, val stageNo: Int, val status: AssemblyStageStatus, val version: Int,
    val startedAt: String?, val completedAt: String?, val reworkReason: String?,
    val serverTime: String?, val traceId: String?, val idempotent: Boolean = false,
)

data class AssemblyTask(
    val id: String, val orderNo: String = "", val deviceId: String, val deviceNo: String,
    val materialSummary: String = "", val status: AssemblyTaskStatus, val progressStage: Int,
    val taskVersion: Int, val currentLaborRecordId: String? = null, val currentLaborStartedAt: String? = null,
    val accumulatedLaborMinutes: Int? = null, val assignedAssemblerId: String? = null, val assignedAssemblerName: String? = null,
    val serverTime: String? = null, val stages: List<AssemblyStage> = listOf(1, 2, 3).map { AssemblyStage(it, AssemblyStageStatus.NOT_STARTED) },
    val members: List<AssemblyMember> = emptyList(),
)

data class AssemblyMember(
    val assemblerId: String,
    val assemblerName: String? = null,
    val assignmentRole: String,
    val assignedBy: String? = null,
    val assignedAt: String? = null,
    val removedAt: String? = null,
)

data class AssemblyAssignmentResponse(
    val taskId: String,
    val members: List<AssemblyMember>,
    val traceId: String? = null,
    val idempotent: Boolean = false,
    val serverTime: String? = null,
)

data class AssemblyTaskPage(
    val items: List<AssemblyTask>,
    val page: Int,
    val pageSize: Int,
    val total: Int,
    val totalPages: Int,
    val serverTime: String? = null,
)

data class LaborRecord(
    val id: String, val taskId: String?, val type: LaborType, val startedAt: String,
    val endedAt: String?, val durationMinutes: Int?, val remark: String?,
    val orderNo: String? = null,
    val deviceId: String? = null,
    val deviceNo: String? = null,
    val laborRecordId: String? = null,
    val temporaryTransferId: String? = null,
    val status: String? = null,
    val taskVersion: Int? = null,
    val serverTime: String? = null,
)

data class MachineProgress(
    val deviceId: String, val deviceNo: String, val taskCount: Int, val completedTaskCount: Int,
    val progressPercent: Int, val laborMinutes: Int?,
    val assemblyLaborMinutes: Int? = null,
    val temporaryTransferLaborMinutes: Int? = null,
    val totalLaborMinutes: Int? = null,
)

data class MachineProgressPage(
    val items: List<MachineProgress>,
    val page: Int,
    val pageSize: Int,
)

data class WorkshopProgressSummary(
    val workshopId: String?, val totalTasks: Int, val completedTasks: Int, val overallProgressPercent: Int,
    val totalLaborMinutes: Int, val assemblyLaborMinutes: Int, val temporaryTransferLaborMinutes: Int,
    val machines: List<MachineProgress>, val generatedAt: String
)

/**
 * 订单维度进度（后端 0.21.6 `GET /workshop/order-progress`，契约见 main.py:4119）。
 *
 * 服务端按订单聚合机台装配任务阶段取平均，字段名与契约严格一致（orderProgressPercent）。
 * [orderProgressPercent] 为 null 表示服务端本次没给该字段 —— UI 必须显示「—」，
 * 绝不能用 0% 冒充真实进度（那会让主管误判交付风险）。
 */
data class OrderProgress(
    val orderNo: String,
    val orderProgressPercent: Int? = null,
    /** 该订单的装配任务数（一机台一任务） */
    val totalTasks: Int = 0,
    val completedTasks: Int = 0,
    val waitingMaterialTasks: Int = 0,
    val inProgressTasks: Int = 0,
)

/** `/workshop/order-progress` 分页响应。 */
data class OrderProgressPage(
    val items: List<OrderProgress> = emptyList(),
    val page: Int = 1,
    val pageSize: Int = 50,
    /** 服务端收敛后的车间（null = 全厂范围）；App 不自行拼装越权参数 */
    val workshop: String? = null,
    val scope: String? = null,
    val serverTime: String? = null,
)

/**
 * 看板展示值：只反映该订单的进度。
 *
 * 服务端没给 orderProgressPercent 时返回「—」，而不是 0%（0% 会被当成真实进度）。
 * 抽成纯函数便于单测直接验证「看板显示的是订单维度值」。
 */
val OrderProgress.displayProgressPercent: String
    get() = orderProgressPercent?.let { "$it%" } ?: "—"

data class OrderDetailTimelineEvent(
    val type: String, val entityId: String, val status: String?, val serverTime: String?, val actorId: String?,
    val actorName: String? = null
)

data class OrderDetailLaborSummary(
    val assemblyLaborMinutes: Int, val temporaryTransferLaborMinutes: Int, val totalLaborMinutes: Int
)

/** C14 server-provided labor facts. Nullable minutes preserve missing optional fields. */
data class LaborSummaryItem(
    val taskId: String?,
    val orderNo: String?,
    val deviceId: String?,
    val deviceNo: String?,
    val assemblerId: String?,
    val assemblerName: String?,
    val assemblyLaborMinutes: Int?,
    val temporaryTransferLaborMinutes: Int?,
    val totalLaborMinutes: Int?,
)

data class LaborSummaryPage(
    val items: List<LaborSummaryItem>,
    val page: Int = 1,
    val pageSize: Int = 20,
    val total: Int? = null,
    val task: LaborSummaryItem? = null,
)

data class OrderDetailMaterialSummaryItem(
    val materialId: String,
    val materialCode: String,
    val materialName: String,
    val unit: String,
    val requiredQuantity: Int,
    val arrivedQuantity: Int,
    val inStockQuantity: Int,
    val shortageQuantity: Int,
    val statusCode: String,
    val statusLabel: String,
)

data class OrderDetailMaterialSummary(
    val items: List<OrderDetailMaterialSummaryItem> = emptyList(),
    val totalMaterialTypes: Int = 0,
    val totalRequiredQuantity: Int = 0,
    val totalArrivedQuantity: Int = 0,
    val totalInStockQuantity: Int = 0,
    val totalShortageQuantity: Int = 0,
)

data class OrderDetail(
    val orderId: String, val orderNo: String, val productName: String?, val orderStatus: String?,
    val materials: List<OrderMaterialItem>, val assemblyTasks: List<AssemblyTask>,
    val laborSummary: OrderDetailLaborSummary, val timeline: List<OrderDetailTimelineEvent>,
    val page: Int, val pageSize: Int, val total: Int,
    val materialSummary: OrderDetailMaterialSummary = OrderDetailMaterialSummary(),
    // 0.5.48：物料清单支持分页（大订单一次上万行会返回几 MB JSON）
    val materialsTotal: Int = 0,
    val materialsPage: Int = 1,
    val materialsPageSize: Int = 0,
    val materialsHasMore: Boolean = false,
    // 0.5.51：订单详情自带订单维度进度（后端 main.py:4689 的 progress.orderProgressPercent）。
    // null 表示服务端未返回该段 —— UI 隐藏该项，不显示 0%。
    val orderProgress: OrderProgress? = null,
)

data class AssemblyActionRequest(val clientOperationId: String, val expectedVersion: Int? = null, val stage: Int? = null, val remark: String? = null)
