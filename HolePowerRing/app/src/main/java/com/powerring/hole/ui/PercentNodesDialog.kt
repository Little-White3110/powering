package com.powerring.hole.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.powerring.hole.ring.PercentNodes
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「电量节点」编辑弹窗：逗号分隔输入，实时校验。
 *
 * 与色盘弹窗同一套约定：编辑态只在弹窗内部，落盘只发生在「确定」；
 * [show] 由页面侧持有，关闭后不保留草稿。
 */
@Composable
fun PercentNodesDialog(
    show: Boolean,
    initial: List<Int>,
    onDismiss: () -> Unit,
    onConfirm: (List<Int>) -> Unit,
) {
    var text by remember(show, initial) { mutableStateOf(initial.joinToString(",")) }
    val parsed = PercentNodes.decodeStrict(text)

    OverlayDialog(
        show = show,
        title = "电量节点",
        onDismissRequest = onDismiss,
    ) {
        TextField(
            value = text,
            onValueChange = { text = it },
            label = "节点百分比",
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = when {
                parsed == null ->
                    "格式不对：请用逗号分隔 1–100 的整数，最多 ${PercentNodes.MAX_NODES} 个（如 20,80,100）"

                parsed.isEmpty() -> "留空 = 不在任何节点显示电量"
                else -> "共 ${parsed.size} 个节点：${parsed.joinToString(" / ")}%"
            },
            modifier = Modifier.fillMaxWidth(),
            fontSize = MiuixTheme.textStyles.body2.fontSize,
            color = if (parsed == null) {
                MiuixTheme.colorScheme.error
            } else {
                MiuixTheme.colorScheme.onSurfaceVariantSummary
            },
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(modifier = Modifier.weight(1f))
            Button(onClick = onDismiss) {
                Text(text = "取消")
            }
            Spacer(modifier = Modifier.width(12.dp))
            Button(
                onClick = {
                    parsed?.let(onConfirm)
                    onDismiss()
                },
                enabled = parsed != null,
            ) {
                Text(text = "确定")
            }
        }
    }
}
