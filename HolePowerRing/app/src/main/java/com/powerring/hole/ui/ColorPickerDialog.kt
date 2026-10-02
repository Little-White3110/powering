package com.powerring.hole.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.powerring.hole.ring.RingConfig
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ColorPicker
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 一次颜色编辑会话：标题、初值、确认回调。
 * 由页面侧持有（每次打开都新建实例），弹窗自身不携带业务语义。
 */
data class ColorEditing(
    val label: String,
    val initialArgb: Int,
    val onConfirm: (Int) -> Unit,
)

/**
 * 全站共用的色盘弹窗：miuix `ColorPicker` + 十六进制输入框，两者双向同步。
 *
 * 同步不会成环：滑色盘 → 重写 [hexText]，输入十六进制 → 重写 [color]，
 * 两条路径写的是各自的对端状态，不是彼此监听（无 `LaunchedEffect` 回环）。
 * 落盘只发生在「确定」，与改造前的行为一致。
 *
 * [editing] 为 null 时弹窗关闭；传入新对象会重置内部编辑态。
 */
@Composable
fun ColorPickerDialog(editing: ColorEditing?, onDismiss: () -> Unit) {
    val initial = editing?.initialArgb ?: RingConfig.DEFAULT.customColor
    var color by remember(editing) { mutableStateOf(Color(initial)) }
    var hexText by remember(editing) { mutableStateOf(HexColor.format(initial)) }
    val hexInvalid = hexText.isNotEmpty() && HexColor.parse(hexText) == null

    OverlayDialog(
        show = editing != null,
        title = editing?.label ?: "选择颜色",
        onDismissRequest = onDismiss,
    ) {
        ColorPicker(
            color = color,
            onColorChanged = {
                color = it
                hexText = HexColor.format(it.toArgb())
            },
            showPreview = true,
        )
        Spacer(modifier = Modifier.height(12.dp))
        TextField(
            value = hexText,
            onValueChange = { raw ->
                val normalized = HexColor.normalizeInput(raw)
                hexText = normalized
                HexColor.parse(normalized)?.let { color = Color(it) }
            },
            label = "十六进制色值",
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            trailingIcon = {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(color),
                )
            },
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = if (hexInvalid) {
                "长度只能是 3 / 6 / 8 位"
            } else {
                "支持 RGB、RRGGBB、AARRGGBB；前两位是透明度（00 为全透明）"
            },
            modifier = Modifier.fillMaxWidth(),
            fontSize = MiuixTheme.textStyles.body2.fontSize,
            color = if (hexInvalid) {
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
                    val argb = color.toArgb()
                    editing?.onConfirm?.invoke(argb)
                    onDismiss()
                },
            ) {
                Text(text = "确定")
            }
        }
    }
}
