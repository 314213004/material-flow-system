package com.company.logistics.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.company.logistics.model.StocktakeRecord
import com.company.logistics.ui.WorkspaceLoadState
import com.company.logistics.ui.components.AppCard
import com.company.logistics.ui.components.EmptyState
import com.company.logistics.ui.components.SecondaryButton
import com.company.logistics.ui.components.StatusTag
import com.company.logistics.ui.components.VSpace
import com.company.logistics.ui.theme.Dimens
import com.company.logistics.ui.theme.LogisticsColors
import com.company.logistics.ui.theme.LogisticsTheme
import com.company.logistics.ui.theme.LogisticsType
import com.company.logistics.ui.theme.Spacing

/**
 * 盘点（0.5.45）：物料员/仓管/管理员可查与提报；确认由仓管/管理员在 App 或管理台完成。
 *
 * 账面数量由服务端取，客户端只提交实盘数量与（可选）库位；差异 = 实盘 - 账面。
 */
@Composable
fun StocktakeScreen(
    records: List<StocktakeRecord>,
    state: WorkspaceLoadState,
    error: String?,
    submitting: Boolean,
    canConfirm: Boolean,
    onSubmit: (materialCode: String, locationCode: String?, actualQuantity: Int) -> Unit,
    onConfirm: (stocktakeId: String) -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Dimens.PagePadding),
    ) {
        Spacer(Modifier.height(Spacing.sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("盘点", fontSize = 19.sp, fontWeight = FontWeight.Bold,
                    color = LogisticsTheme.colors.textPrimary)
                VSpace(4.dp)
                Text(
                    "实盘数量提交后生成待确认盘点单，由仓管/管理员确认",
                    fontSize = 12.sp,
                    color = LogisticsTheme.colors.textSecondary,
                )
            }
            SecondaryButton(text = "刷新", onClick = onRefresh)
        }
        VSpace(Spacing.md)

        SecondaryButton(
            text = if (submitting) "提交中…" else "新建盘点",
            onClick = { if (!submitting) showDialog = true },
            modifier = Modifier.fillMaxWidth(),
        )
        VSpace(Spacing.md)

        when (state) {
            WorkspaceLoadState.LOADING -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator()
            }
            WorkspaceLoadState.ERROR -> {
                EmptyState(title = "加载失败", description = error ?: "请重试")
                SecondaryButton(text = "重试", onClick = onRefresh)
            }
            WorkspaceLoadState.EMPTY, WorkspaceLoadState.IDLE ->
                EmptyState(title = "暂无盘点单", description = "新建盘点后会在这里显示，等待仓管确认。")
            WorkspaceLoadState.CONTENT -> records.forEach { record ->
                StocktakeCard(record, canConfirm = canConfirm && record.status == "PENDING_CONFIRM",
                    enabled = !submitting, onConfirm = onConfirm)
                VSpace(Spacing.sm)
            }
        }

        VSpace(Spacing.md)
        SecondaryButton(text = "返回", onClick = onBack, modifier = Modifier.fillMaxWidth())
        VSpace(Spacing.xxl)
    }

    if (showDialog) {
        StocktakeCreateDialog(
            submitting = submitting,
            onDismiss = { showDialog = false },
            onConfirm = { code, location, actual ->
                onSubmit(code, location, actual)
                showDialog = false
            },
        )
    }
}

@Composable
private fun StocktakeCard(
    record: StocktakeRecord,
    canConfirm: Boolean,
    enabled: Boolean,
    onConfirm: (String) -> Unit,
) {
    AppCard(accentColor = LogisticsTheme.colors.border) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(record.materialCode, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    fontFamily = LogisticsType.MonoFamily, color = LogisticsTheme.colors.textPrimary)
                VSpace(2.dp)
                Text(
                    "库位 ${record.locationCode ?: "全部"} · 账面 ${record.bookQuantity} / 实盘 ${record.actualQuantity}",
                    fontSize = 13.sp,
                    color = LogisticsTheme.colors.textSecondary,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                val diff = record.difference
                Text(
                    if (diff == 0) "无差异" else "${if (diff > 0) "+" else ""}$diff",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = when {
                        diff == 0 -> LogisticsColors.Success
                        diff > 0 -> LogisticsColors.Warning
                        else -> LogisticsColors.Danger
                    },
                )
                VSpace(4.dp)
                StatusTag(
                    label = if (record.status == "CONFIRMED") "已确认" else "待确认",
                    color = if (record.status == "CONFIRMED") LogisticsColors.Success else LogisticsColors.Warning,
                    containerColor = LogisticsTheme.colors.cardBackground,
                )
            }
        }
        if (canConfirm) {
            VSpace(Spacing.sm)
            SecondaryButton(
                text = "确认盘点",
                onClick = { if (enabled) onConfirm(record.id) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun StocktakeCreateDialog(
    submitting: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (materialCode: String, locationCode: String?, actualQuantity: Int) -> Unit,
) {
    var code by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }
    var actual by remember { mutableStateOf("") }
    val canSubmit = code.isNotBlank() && (actual.toIntOrNull() ?: -1) >= 0 && !submitting

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建盘点", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("物料编码") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                VSpace(8.dp)
                OutlinedTextField(
                    value = location,
                    onValueChange = { location = it },
                    label = { Text("库位编码（可选，留空=按物料整体盘）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                VSpace(8.dp)
                OutlinedTextField(
                    value = actual,
                    onValueChange = { actual = it.filter { c -> c.isDigit() } },
                    label = { Text("实盘数量") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(code.trim(), location.trim().takeIf { it.isNotBlank() }, actual.toIntOrNull() ?: 0)
                },
                enabled = canSubmit,
            ) { Text("提交盘点") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
