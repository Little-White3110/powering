package com.powerring.hole.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.powerring.hole.R
import com.powerring.hole.ring.RingConfig
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ColorPicker
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import kotlin.math.roundToInt

/**
 * 模块配置页：全部使用 miuix（HyperOS）Compose 组件。
 * 修改即时写入并通过广播通知 SystemUI 侧热加载，无需重启系统界面。
 */
class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val controller = remember { ThemeController(ColorSchemeMode.System) }
            MiuixTheme(controller = controller) {
                SettingsScreen()
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun SettingsScreen() {
        val ctx = this@SettingsActivity
        var config by remember { mutableStateOf(PrefsStore.load(ctx)) }
        var showColorPicker by remember { mutableStateOf(false) }
        // 色盘内的临时编辑色，点确定才落盘
        var editingColor by remember { mutableStateOf(Color(config.customColor)) }

        fun update(newConfig: RingConfig) {
            config = newConfig
        }

        Scaffold(
            topBar = {
                SmallTopAppBar(title = getString(R.string.app_name))
            },
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            ) {
                // ---------- 开关区 ----------
                SwitchPreference(
                    checked = config.ringEnabled,
                    onCheckedChange = { enabled ->
                        PrefsStore.setBoolean(ctx, RingConfig.KEY_RING_ENABLED, enabled)
                        update(config.copy(ringEnabled = enabled))
                    },
                    title = "挖孔环形电量",
                    summary = "在摄像头挖孔外圈以圆环显示剩余电量",
                )
                SwitchPreference(
                    checked = config.hideBattery,
                    onCheckedChange = { enabled ->
                        PrefsStore.setBoolean(ctx, RingConfig.KEY_HIDE_BATTERY, enabled)
                        update(config.copy(hideBattery = enabled))
                    },
                    title = "隐藏状态栏电池图标",
                    summary = "环形电量可用时隐藏原图标；环不可用时自动恢复",
                    enabled = config.ringEnabled,
                )
                SwitchPreference(
                    checked = config.levelAnim,
                    onCheckedChange = { enabled ->
                        PrefsStore.setBoolean(ctx, RingConfig.KEY_LEVEL_ANIM, enabled)
                        update(config.copy(levelAnim = enabled))
                    },
                    title = "电量变化动画",
                    summary = "电量增减时弧度平滑过渡",
                    enabled = config.ringEnabled,
                )
                SwitchPreference(
                    checked = config.chargingGlow,
                    onCheckedChange = { enabled ->
                        PrefsStore.setBoolean(ctx, RingConfig.KEY_CHARGING_GLOW, enabled)
                        update(config.copy(chargingGlow = enabled))
                    },
                    title = "充电高亮辉光",
                    summary = "充电时圆环显示品牌蓝与外扩光效",
                    enabled = config.ringEnabled,
                )

                // ---------- 几何微调区 ----------
                // 注意：SliderPreference 首次组合可能触发一次 onValueChange，
                // 因此拖动中只更新内存态，松手（onValueChangeFinished）才落盘，
                // 避免页面首次打开就把默认值/范围起点写入配置。
                ConfigSlider(
                    title = "环粗细",
                    summary = "调节电量环描边宽度",
                    value = config.strokeWidthDp,
                    valueRange = RingConfig.STROKE_MIN..RingConfig.STROKE_MAX,
                    valueText = String.format("%.1f dp", config.strokeWidthDp),
                    enabled = config.ringEnabled,
                    transform = { (it * 10).roundToInt() / 10f },
                    onChange = { update(config.copy(strokeWidthDp = it)) },
                    onCommit = {
                        PrefsStore.setFloat(ctx, RingConfig.KEY_STROKE_WIDTH, it)
                    },
                )
                ConfigSlider(
                    title = "环缩放",
                    summary = "整体缩放环的半径，用于完美覆盖挖孔",
                    value = config.scale,
                    valueRange = RingConfig.SCALE_MIN..RingConfig.SCALE_MAX,
                    valueText = String.format("%.2f×", config.scale),
                    enabled = config.ringEnabled,
                    transform = { (it * 100).roundToInt() / 100f },
                    onChange = { update(config.copy(scale = it)) },
                    onCommit = {
                        PrefsStore.setFloat(ctx, RingConfig.KEY_SCALE, it)
                    },
                )
                ConfigSlider(
                    title = "水平偏移",
                    summary = "正值向右，负值向左",
                    value = config.offsetXDp,
                    valueRange = RingConfig.OFFSET_MIN..RingConfig.OFFSET_MAX,
                    valueText = "${config.offsetXDp.roundToInt()} dp",
                    enabled = config.ringEnabled,
                    transform = { it.roundToInt().toFloat() },
                    onChange = { update(config.copy(offsetXDp = it)) },
                    onCommit = {
                        PrefsStore.setFloat(ctx, RingConfig.KEY_OFFSET_X, it)
                    },
                )
                ConfigSlider(
                    title = "垂直偏移",
                    summary = "正值向下，负值向上",
                    value = config.offsetYDp,
                    valueRange = RingConfig.OFFSET_MIN..RingConfig.OFFSET_MAX,
                    valueText = "${config.offsetYDp.roundToInt()} dp",
                    enabled = config.ringEnabled,
                    transform = { it.roundToInt().toFloat() },
                    onChange = { update(config.copy(offsetYDp = it)) },
                    onCommit = {
                        PrefsStore.setFloat(ctx, RingConfig.KEY_OFFSET_Y, it)
                    },
                )

                // ---------- 颜色区 ----------
                SwitchPreference(
                    checked = config.useCustomColor,
                    onCheckedChange = { enabled ->
                        PrefsStore.setBoolean(ctx, RingConfig.KEY_USE_CUSTOM_COLOR, enabled)
                        update(config.copy(useCustomColor = enabled))
                    },
                    title = "自定义环颜色",
                    summary = "关闭时使用系统语义色（充电蓝/低电红/省电琥珀）",
                    enabled = config.ringEnabled,
                )
                ArrowPreference(
                    title = "选择颜色",
                    summary = "点击打开色盘自定义电量环颜色",
                    enabled = config.ringEnabled && config.useCustomColor,
                    onClick = {
                        editingColor = Color(config.customColor)
                        showColorPicker = true
                    },
                    endActions = {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(Color(config.customColor)),
                        )
                    },
                )

                Text(
                    text = "提示：所有调节即时生效；息屏/AOD 期间自动隐藏圆环以防烧屏。" +
                        "若调节后环与挖孔有偏差，优先用「缩放」对齐半径，再用偏移微调中心。",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                )
            }

            // 色盘弹窗
            OverlayDialog(
                show = showColorPicker,
                title = "选择环颜色",
                onDismissRequest = { showColorPicker = false },
            ) {
                ColorPicker(
                    color = editingColor,
                    onColorChanged = { editingColor = it },
                    showPreview = true,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(modifier = Modifier.weight(1f))
                    Button(
                        onClick = { showColorPicker = false },
                    ) {
                        Text(text = "取消")
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Button(
                        onClick = {
                            val argb = editingColor.toArgb()
                            PrefsStore.setInt(ctx, RingConfig.KEY_CUSTOM_COLOR, argb)
                            update(config.copy(customColor = argb))
                            showColorPicker = false
                        },
                    ) {
                        Text(text = "确定")
                    }
                }
            }
        }
    }

    /**
     * 滑杆设置项：拖动中仅更新页面内存态（实时显示数值），
     * 松手时才持久化并通知 Hook 侧。
     *
     * 关键防御：miuix Slider 在首次组合时可能触发 onValueChange 与
     * onValueChangeFinished（实测会把默认值写成 1.3 之类的范围附近值），
     * 因此用真实指针按下（Press）作为"用户操作"门控，没有按下的回调一律不落盘。
     */
    @androidx.compose.runtime.Composable
    private fun ConfigSlider(
        title: String,
        summary: String,
        value: Float,
        valueRange: ClosedFloatingPointRange<Float>,
        valueText: String,
        enabled: Boolean,
        transform: (Float) -> Float,
        onChange: (Float) -> Unit,
        onCommit: (Float) -> Unit,
    ) {
        // 拖动过程中的临时值；null 表示未在拖动，显示外部传入的持久值
        var draggingValue by remember { mutableStateOf<Float?>(null) }
        // 本行是否发生过真实触摸按下
        var touched by remember { mutableStateOf(false) }
        val display = draggingValue ?: value

        val touchGate = Modifier.pointerInput(enabled) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.type == androidx.compose.ui.input.pointer.PointerEventType.Press) {
                        touched = true
                    }
                }
            }
        }

        SliderPreference(
            value = display,
            onValueChange = {
                val v = transform(it)
                draggingValue = v
                if (touched) onChange(v)
            },
            onValueChangeFinished = {
                if (touched) {
                    draggingValue?.let(onCommit)
                }
                touched = false
                draggingValue = null
            },
            title = title,
            summary = summary,
            valueText = valueText,
            valueRange = valueRange,
            enabled = enabled,
            modifier = touchGate,
        )
    }
}
