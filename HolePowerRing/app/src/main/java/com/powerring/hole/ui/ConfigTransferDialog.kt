package com.powerring.hole.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.powerring.hole.ring.RingConfig
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 外观配置导入/导出弹窗的种类。 */
enum class TransferKind { Export, Import }

/**
 * 外观配置 JSON 传输弹窗。与色盘弹窗同一模式：`kind == null` 即两窗皆关，
 * 状态由 SettingsScreen 根级持有。
 */
@Composable
fun ConfigTransferDialog(
    kind: TransferKind?,
    config: RingConfig,
    onDismiss: () -> Unit,
    onApply: (RingConfig) -> Unit,
) {
    ExportConfigDialog(show = kind == TransferKind.Export, config = config, onDismiss = onDismiss)
    ImportConfigDialog(
        show = kind == TransferKind.Import,
        config = config,
        onDismiss = onDismiss,
        onApply = onApply,
    )
}

@Composable
private fun ExportConfigDialog(show: Boolean, config: RingConfig, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    // 每次打开重新生成：打开期间 config 不可能被其他入口改动（写入只发生在本页）
    var text by remember(show) { mutableStateOf(ConfigJson.encode(config)) }

    OverlayDialog(show = show, title = "导出外观配置", onDismissRequest = onDismiss) {
        Text(
            text = "把下面的 JSON 复制发给他人；对方在「导入外观配置」粘贴即可覆盖外观设置。不含「开关」页的功能开关。",
            fontSize = MiuixTheme.textStyles.body2.fontSize,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 300.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            TextField(value = text, onValueChange = { text = it }, label = "配置 JSON")
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(modifier = Modifier.weight(1f))
            Button(onClick = onDismiss) {
                Text(text = "关闭")
            }
            Spacer(modifier = Modifier.width(12.dp))
            Button(
                onClick = {
                    copyToClipboard(ctx, "HolePowerRing 外观配置", text)
                    Toast.makeText(ctx, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                    onDismiss()
                },
                enabled = text.isNotBlank(),
            ) {
                Text(text = "复制")
            }
        }
    }
}

@Composable
private fun ImportConfigDialog(
    show: Boolean,
    config: RingConfig,
    onDismiss: () -> Unit,
    onApply: (RingConfig) -> Unit,
) {
    val ctx = LocalContext.current
    var input by remember(show) { mutableStateOf("") }
    val patch = remember(input, config) {
        if (input.isBlank()) null else ConfigJson.runCatchingPatch(input, config)
    }

    OverlayDialog(show = show, title = "导入外观配置", onDismissRequest = onDismiss) {
        Text(
            text = "粘贴他人导出的 JSON。缺失的项保持你当前的值；有任何一项非法则整次导入失败、不做修改。",
            fontSize = MiuixTheme.textStyles.body2.fontSize,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 300.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            TextField(value = input, onValueChange = { input = it }, label = "配置 JSON")
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = when {
                input.isBlank() -> "等待粘贴…"
                patch?.isSuccess == true -> "配置有效，应用后将覆盖当前外观设置"
                else -> "✕ ${patch?.exceptionOrNull()?.message ?: "解析失败"}"
            },
            modifier = Modifier.fillMaxWidth(),
            fontSize = MiuixTheme.textStyles.body2.fontSize,
            color = when {
                input.isNotBlank() && patch?.isSuccess != true -> MiuixTheme.colorScheme.error
                else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
            },
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = {
                    readClipboard(ctx)?.let { input = it }
                        ?: Toast.makeText(ctx, "剪贴板为空", Toast.LENGTH_SHORT).show()
                },
            ) {
                Text(text = "从剪贴板粘贴")
            }
            Spacer(modifier = Modifier.weight(1f))
            Button(onClick = onDismiss) {
                Text(text = "取消")
            }
            Spacer(modifier = Modifier.width(12.dp))
            Button(
                onClick = { patch?.getOrNull()?.let(onApply) },
                enabled = patch?.isSuccess == true,
            ) {
                Text(text = "应用")
            }
        }
    }
}

private fun copyToClipboard(ctx: Context, label: String, text: String) {
    runCatching {
        ctx.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText(label, text))
    }
}

private fun readClipboard(ctx: Context): String? = runCatching {
    ctx.getSystemService(ClipboardManager::class.java)
        ?.primaryClip?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)?.text?.toString()?.takeIf { it.isNotBlank() }
}.getOrNull()
