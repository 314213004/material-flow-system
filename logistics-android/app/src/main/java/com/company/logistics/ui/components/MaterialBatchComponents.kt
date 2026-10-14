package com.company.logistics.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.company.logistics.model.EXCEPTION_TYPE_OPTIONS
import com.company.logistics.model.MaterialBatchAction
import com.company.logistics.model.MaterialBatchLine
import com.company.logistics.model.OrderMaterialItem
import com.company.logistics.model.batchKey
import com.company.logistics.ui.theme.LogisticsColors
import com.company.logistics.ui.theme.LogisticsTheme
import com.company.logistics.ui.theme.LogisticsType

/**
 * 物料勾选批量操作的共享组件（订单页 / 机台页共用）：
 *  - [MaterialBatchCheckBox]：紧凑勾选指示器（16dp，整行 clickable 负责点击）；
 *  - [MaterialBatchActionBar]：全选 / 已选计数 / 四个批量动作按钮（按角色裁剪，测试预览只读时禁用）；
 *  - [MaterialBatchDialog]：选择关联订单、逐条确认数量、填写库位 / 异常类型 / 备注后提交。
 */

/** 批量对话框确认后的提交内容，交给 ViewModel.submitMaterialBatch。 */
data class MaterialBatchSubmission(
    val action: MaterialBatchAction,
    val orderNo: String,
    val lines: List<MaterialBatchLine>,
    val remark: String,
    val targetLocation: String?,
    val sourceLocation: String?,
    val exceptionType: String,
)

/** 紧凑勾选指示器：16dp 圆角方块，选中实心打勾；仅展示，点击由外层行处理。 */
@Composable
fun MaterialBatchCheckBox(checked: Boolean, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(3.dp)
    Box(
        modifier = modifier
            .padding(end = 8.dp)
            .size(16.dp)
            .clip(shape)
            .then(
                if (checked) {
                    Modifier.background(MaterialTheme.colorScheme.primary, shape)
                } else {
                    Modifier.border(1.5.dp, LogisticsTheme.colors.border, shape)
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Image(
                imageVector = LogisticsIcons.Check,
                contentDescription = null,
                colorFilter = ColorFilter.tint(Color.White),
                modifier = Modifier.size(11.dp),
            )
        }
    }
}

/**
 * 批量操作栏：全选（当前筛选结果）/ 已选计数 / 动作按钮。
 * [actions] 为空（如装配工、计划员）时只显示提示，不出现勾选能力。
 */
@Composable
fun MaterialBatchActionBar(
    visibleCount: Int,
    selectedCount: Int,
    allChecked: Boolean,
    actions: List<MaterialBatchAction>,
    readOnly: Boolean,
    submitting: Boolean,
    onToggleAll: () -> Unit,
    onClear: () -> Unit,
    onAction: (MaterialBatchAction) -> Unit,
) {
    if (actions.isEmpty()) return
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onToggleAll, enabled = visibleCount > 0) {
                Text(if (allChecked) "取消全选" else "全选", fontSize = 12.sp)
            }
            Text(
                if (submitting) "提交中…" else "已选 $selectedCount 项",
                modifier = Modifier.weight(1f),
                fontSize = 12.sp,
                color = LogisticsTheme.colors.textSecondary,
            )
            if (selectedCount > 0) {
                TextButton(onClick = onClear) { Text("清空", fontSize = 12.sp) }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            actions.forEach { action ->
                OutlinedButton(
                    onClick = { onAction(action) },
                    enabled = selectedCount > 0 && !readOnly && !submitting,
                ) {
                    Text(
                        action.label,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (selectedCount > 0 && !readOnly) LogisticsColors.PrimaryDark else LogisticsTheme.colors.textTertiary,
                    )
                }
            }
        }
        if (readOnly) {
            Text(
                "测试预览只读，批量操作不可用",
                fontSize = 11.sp,
                color = LogisticsTheme.colors.textTertiary,
            )
        }
    }
}

/**
 * 批量操作对话框。
 * @param orders 可选关联订单（订单号 to 展示文案）；仅一个时自动选中。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaterialBatchDialog(
    action: MaterialBatchAction,
    items: List<OrderMaterialItem>,
    orders: List<Pair<String, String>>,
    submitting: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (MaterialBatchSubmission) -> Unit,
) {
    val isException = action == MaterialBatchAction.EXCEPTION
    var selectedOrder by remember(orders) { mutableStateOf(orders.singleOrNull()?.first ?: "") }
    var orderExpanded by remember { mutableStateOf(false) }
    var exceptionType by remember { mutableStateOf(EXCEPTION_TYPE_OPTIONS.first().first) }
    var typeExpanded by remember { mutableStateOf(false) }
    var targetLocation by remember { mutableStateOf("") }
    var sourceLocation by remember { mutableStateOf("") }
    var remark by remember { mutableStateOf("") }
    // 数量按勾选键记录文本；出库/退料/调拨默认需求数量，异常默认账面在库数
    val quantities = remember(items, action) {
        mutableStateMapOf<String, String>().apply {
            items.forEach { put(it.batchKey, (if (isException) it.inStockQuantity else it.requiredQuantity.coerceAtLeast(1)).toString()) }
        }
    }
    val minQty = if (isException) 0 else 1
    val parsed = items.map { it to (quantities[it.batchKey]?.trim()?.toIntOrNull()) }
    val quantitiesValid = parsed.all { (_, q) -> q != null && q >= minQty }
    val canConfirm = selectedOrder.isNotBlank() && items.isNotEmpty() && quantitiesValid && !submitting &&
        (action != MaterialBatchAction.RELOCATION || targetLocation.isNotBlank()) &&
        (!isException || remark.isNotBlank())

    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        properties = DialogProperties(decorFitsSystemWindows = false),
        // 键盘弹出时对话框整体上移，提交按钮始终可见
        modifier = Modifier.imePadding(),
        title = { Text("批量${action.label}", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Text(
                    "已勾选 ${items.size} 项物料，" + when (action) {
                        MaterialBatchAction.OUTBOUND -> "将分别创建出库流转申请（待审批）。"
                        MaterialBatchAction.RETURN -> "将分别创建退料申请，按入库审批规则处理。"
                        MaterialBatchAction.RELOCATION -> "将分别创建厂内调拨申请：来源库位扣减、目标库位增加。"
                        MaterialBatchAction.EXCEPTION -> "将分别提报异常，数量填写实际清点数。"
                    },
                    fontSize = 13.sp,
                    color = LogisticsTheme.colors.textSecondary,
                )
                Spacer(Modifier.height(10.dp))
                ExposedDropdownMenuBox(expanded = orderExpanded, onExpandedChange = { orderExpanded = it }) {
                    OutlinedTextField(
                        value = selectedOrder,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("关联订单") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(orderExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                    )
                    ExposedDropdownMenu(expanded = orderExpanded, onDismissRequest = { orderExpanded = false }) {
                        orders.forEach { (orderNo, label) ->
                            DropdownMenuItem(
                                text = { Text(label, fontSize = 13.sp) },
                                onClick = { selectedOrder = orderNo; orderExpanded = false },
                            )
                        }
                    }
                }
                if (action == MaterialBatchAction.RELOCATION) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = sourceLocation,
                        onValueChange = { sourceLocation = it.trim() },
                        label = { Text("来源库位（可选，留空自动选库存足量库位）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (action == MaterialBatchAction.RELOCATION || action == MaterialBatchAction.RETURN) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = targetLocation,
                        onValueChange = { targetLocation = it.trim() },
                        label = { Text(if (action == MaterialBatchAction.RELOCATION) "目标库位（必填）" else "目标库位（可选，留空沿用主库位）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (isException) {
                    Spacer(Modifier.height(8.dp))
                    ExposedDropdownMenuBox(expanded = typeExpanded, onExpandedChange = { typeExpanded = it }) {
                        OutlinedTextField(
                            value = EXCEPTION_TYPE_OPTIONS.first { it.first == exceptionType }.second,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("异常类型") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(typeExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                        )
                        ExposedDropdownMenu(expanded = typeExpanded, onDismissRequest = { typeExpanded = false }) {
                            EXCEPTION_TYPE_OPTIONS.forEach { (code, label) ->
                                DropdownMenuItem(
                                    text = { Text(label, fontSize = 13.sp) },
                                    onClick = { exceptionType = code; typeExpanded = false },
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = remark,
                    onValueChange = { if (it.length <= 400) remark = it },
                    label = { Text(if (isException) "异常描述（必填）" else "备注（可选）") },
                    singleLine = !isException,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    if (isException) "逐条确认实际数量（账面数见右侧）" else "逐条确认数量",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = LogisticsTheme.colors.textSecondary,
                )
                parsed.forEach { (item, qty) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                item.materialCode,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = LogisticsType.MonoFamily,
                                color = LogisticsTheme.colors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                listOfNotNull(item.name.takeIf { it.isNotBlank() }, item.deviceNo?.takeIf { it.isNotBlank() }?.let { "机台 $it" })
                                    .joinToString(" · ") + if (isException) " · 账面 ${item.inStockQuantity}" else " · 需求 ${item.requiredQuantity}",
                                fontSize = 11.sp,
                                color = LogisticsTheme.colors.textTertiary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(
                            value = quantities[item.batchKey].orEmpty(),
                            onValueChange = { v -> quantities[item.batchKey] = v.filter { it.isDigit() }.take(9) },
                            singleLine = true,
                            isError = qty == null || qty < minQty,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.width(88.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        MaterialBatchSubmission(
                            action = action,
                            orderNo = selectedOrder,
                            lines = parsed.map { (item, q) -> MaterialBatchLine(item, q ?: 0) },
                            remark = remark.trim(),
                            targetLocation = targetLocation.takeIf { it.isNotBlank() },
                            sourceLocation = sourceLocation.takeIf { it.isNotBlank() },
                            exceptionType = exceptionType,
                        )
                    )
                },
                enabled = canConfirm,
            ) { Text("提交${action.label}") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !submitting) { Text("取消") } },
    )
}
