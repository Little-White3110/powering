@file:OptIn(ExperimentalScrollBarApi::class)

package com.powerring.hole.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.powerring.hole.BuildConfig
import com.powerring.hole.R
import com.powerring.hole.ring.RingConfig
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.ColorPicker
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.VerticalScrollBar
import top.yukonga.miuix.kmp.basic.rememberScrollBarAdapter
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Theme
import top.yukonga.miuix.kmp.interfaces.ExperimentalScrollBarApi
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

/**
 * 模块配置主框架：开关 / 外观 / 关于 三个 Tab，
 * HorizontalPager 左右滑动与底部 NavigationBar 点击双向联动。
 *
 * 配置态与色盘态全部持有在页面根级：HorizontalPager 会回收离屏页的组合，
 * 状态放在 Tab 内会随滑动丢失；OverlayDialog 也依赖 Scaffold 的 popup 层，
 * 必须渲染在 Pager 之外。
 *
 * @param pagerState 由 Activity 层提升注入，保持 Tab 位置跨重组存活
 */
@Composable
fun SettingsScreen(pagerState: PagerState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf(PrefsStore.load(ctx)) }
    var showColorPicker by remember { mutableStateOf(false) }
    // 色盘内的临时编辑色，点确定才落盘
    var editingColor by remember { mutableStateOf(Color(config.customColor)) }

    data class TabInfo(val label: String, val icon: ImageVector)
    val tabs = listOf(
        TabInfo("开关", MiuixIcons.Settings),
        TabInfo("外观", MiuixIcons.Theme),
        TabInfo("关于", MiuixIcons.Info),
    )

    Scaffold(
        topBar = {
            SmallTopAppBar(title = ctx.getString(R.string.app_name))
        },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, tab ->
                    NavigationBarItem(
                        selected = pagerState.currentPage == i,
                        onClick = { scope.launch { pagerState.animateScrollToPage(i) } },
                        icon = tab.icon,
                        label = tab.label,
                    )
                }
            }
        },
    ) { innerPadding ->
        val contentPadding = PaddingValues(
            top = innerPadding.calculateTopPadding() + 12.dp,
            bottom = innerPadding.calculateBottomPadding() + 12.dp,
        )

        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            when (page) {
                0 -> SwitchTabContent(ctx, config, { config = it }, contentPadding)
                1 -> AppearanceTabContent(
                    ctx = ctx,
                    config = config,
                    update = { config = it },
                    contentPadding = contentPadding,
                    onOpenColorPicker = {
                        editingColor = Color(config.customColor)
                        showColorPicker = true
                    },
                )
                2 -> AboutTabContent(ctx, contentPadding)
            }
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
                        config = config.copy(customColor = argb)
                        showColorPicker = false
                    },
                ) {
                    Text(text = "确定")
                }
            }
        }
    }
}

// ====================================================================
// Tab 0：开关
// ====================================================================

@Composable
private fun SwitchTabContent(
    ctx: Context,
    config: RingConfig,
    update: (RingConfig) -> Unit,
    contentPadding: PaddingValues,
) {
    val listState = rememberLazyListState()
    Box {
        LazyColumn(state = listState, contentPadding = contentPadding) {
            item(key = "switchTitle") {
                SmallTitle("环形电量")
            }
            item(key = "switchCard") {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    SwitchPreference(
                        checked = config.ringEnabled,
                        onCheckedChange = { enabled ->
                            PrefsStore.setBoolean(ctx, RingConfig.KEY_RING_ENABLED, enabled)
                            update(config.copy(ringEnabled = enabled))
                        },
                        title = "挖孔环形电量",
                        summary = "在摄像头挖孔外圈以圆环显示剩余电量",
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
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
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    SwitchPreference(
                        checked = config.restoreBatteryOnLandscape,
                        onCheckedChange = { enabled ->
                            PrefsStore.setBoolean(
                                ctx, RingConfig.KEY_RESTORE_BATTERY_ON_LANDSCAPE, enabled,
                            )
                            update(config.copy(restoreBatteryOnLandscape = enabled))
                        },
                        title = "横屏时恢复电池图标",
                        summary = "横屏时挖孔换到侧边、圆环无法贴合，此时恢复显示原生电池图标，避免电量指示丢失；关闭后横屏同样保持隐藏",
                        enabled = config.ringEnabled && config.hideBattery,
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    SwitchPreference(
                        checked = config.collapseOnImmersive,
                        onCheckedChange = { enabled ->
                            PrefsStore.setBoolean(
                                ctx, RingConfig.KEY_COLLAPSE_ON_IMMERSIVE, enabled,
                            )
                            update(config.copy(collapseOnImmersive = enabled))
                        },
                        title = "状态栏收起时隐藏圆环",
                        summary = "观看视频/图片等状态栏自动收起的场景，圆环向内收缩并淡出；状态栏恢复时弹回",
                        enabled = config.ringEnabled,
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    SwitchPreference(
                        checked = config.collapseOnIsland,
                        onCheckedChange = { enabled ->
                            PrefsStore.setBoolean(ctx, RingConfig.KEY_COLLAPSE_ON_ISLAND, enabled)
                            update(config.copy(collapseOnIsland = enabled))
                        },
                        title = "有岛时隐藏圆环",
                        summary = "灵动岛（超级岛）显示时，圆环像全屏沉浸时一样向内收缩并淡出",
                        enabled = config.ringEnabled,
                    )
                }
            }
            item(key = "systemTitle") {
                SmallTitle("系统")
            }
            item(key = "systemCard") {
                var restarting by remember { mutableStateOf(false) }
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    ArrowPreference(
                        title = "重启系统界面",
                        summary = "以 root 权限终止并自动拉起 SystemUI，用于旧版本配置未热加载时",
                        enabled = !restarting,
                        onClick = {
                            restarting = true
                            restartSystemui { ok ->
                                restarting = false
                                Toast.makeText(
                                    ctx,
                                    if (ok) "SystemUI 正在重启" else "重启失败：未获得 root 权限",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                    )
                }
            }
            item(key = "bottomSpacer") {
                Spacer(modifier = Modifier.height(12.dp))
            }
        }

        VerticalScrollBar(
            adapter = rememberScrollBarAdapter(listState),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight(),
            trackPadding = contentPadding,
        )
    }
}

// ====================================================================
// Tab 1：外观（几何微调 + 自定义颜色）
// ====================================================================

@Composable
private fun AppearanceTabContent(
    ctx: Context,
    config: RingConfig,
    update: (RingConfig) -> Unit,
    contentPadding: PaddingValues,
    onOpenColorPicker: () -> Unit,
) {
    val listState = rememberLazyListState()
    Box {
        LazyColumn(state = listState, contentPadding = contentPadding) {
            // ---------- 几何微调区 ----------
            // 注意：SliderPreference 首次组合可能触发一次 onValueChange，
            // 因此拖动中只更新内存态，松手（onValueChangeFinished）才落盘，
            // 避免页面首次打开就把默认值/范围起点写入配置。
            item(key = "geometryTitle") {
                SmallTitle("几何微调")
            }
            item(key = "geometryCard") {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
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
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
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
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ConfigSlider(
                        title = "水平偏移",
                        summary = "正值向右，负值向左（约 1dp≈3px）",
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
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ConfigSlider(
                        title = "垂直偏移",
                        summary = "正值向下，负值向上；向上幅度受屏幕顶限制",
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
                }
            }

            // ---------- 颜色区 ----------
            item(key = "colorTitle") {
                SmallTitle("颜色")
            }
            item(key = "colorCard") {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    SwitchPreference(
                        checked = config.useCustomColor,
                        onCheckedChange = { enabled ->
                            PrefsStore.setBoolean(ctx, RingConfig.KEY_USE_CUSTOM_COLOR, enabled)
                            update(config.copy(useCustomColor = enabled))
                        },
                        title = "自定义环颜色",
                        summary = "关闭时跟随系统电池图标颜色（普通/低电/省电/性能/充电）",
                        enabled = config.ringEnabled,
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ArrowPreference(
                        title = "选择颜色",
                        summary = "点击打开色盘自定义电量环颜色",
                        enabled = config.ringEnabled && config.useCustomColor,
                        onClick = onOpenColorPicker,
                        endActions = {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(Color(config.customColor)),
                            )
                        },
                    )
                }
            }

            item(key = "bottomSpacer") {
                Spacer(modifier = Modifier.height(12.dp))
            }
        }

        VerticalScrollBar(
            adapter = rememberScrollBarAdapter(listState),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight(),
            trackPadding = contentPadding,
        )
    }
}

// ====================================================================
// Tab 2：关于
// ====================================================================

@Composable
private fun AboutTabContent(
    ctx: Context,
    contentPadding: PaddingValues,
) {
    val listState = rememberLazyListState()
    Box {
        LazyColumn(state = listState, contentPadding = contentPadding) {
            item(key = "aboutTitle") {
                SmallTitle("关于")
            }
            item(key = "aboutCard") {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    Text(
                        text = ctx.getString(R.string.module_description),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        fontSize = MiuixTheme.textStyles.body2.fontSize,
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    Text(
                        text = "版本 v${BuildConfig.VERSION_NAME} · 仅供学习与研究",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        fontSize = MiuixTheme.textStyles.body2.fontSize,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }

            item(key = "tipTitle") {
                SmallTitle("提示")
            }
            item(key = "tipCard") {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    Text(
                        text = "提示：所有调节即时生效；息屏/AOD 期间自动隐藏圆环以防烧屏，" +
                            "状态栏自动收起（沉浸模式）时圆环同步收缩隐藏。" +
                            "横屏时圆环无法贴合挖孔，默认会自动恢复原生电池图标。" +
                            "若调节后环与挖孔有偏差，优先用「缩放」对齐半径，再用偏移微调中心。",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        fontSize = MiuixTheme.textStyles.body2.fontSize,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }

            item(key = "useTitle") {
                SmallTitle("使用方式")
            }
            item(key = "useCard") {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    Text(
                        text = "在 LSPosed 管理器中启用本模块并勾选系统界面（com.android.systemui）作用域后，重启系统界面生效。",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        fontSize = MiuixTheme.textStyles.body2.fontSize,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }

            item(key = "bottomSpacer") {
                Spacer(modifier = Modifier.height(12.dp))
            }
        }

        VerticalScrollBar(
            adapter = rememberScrollBarAdapter(listState),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight(),
            trackPadding = contentPadding,
        )
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
@Composable
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

/** 以 root 重启 SystemUI；结果回调切回主线程。su 不存在/被拒时回调 false。 */
private fun restartSystemui(onResult: (Boolean) -> Unit) {
    Thread {
        val ok = runCatching {
            Runtime.getRuntime()
                .exec(arrayOf("su", "-c", "killall com.android.systemui"))
                .waitFor() == 0
        }.getOrDefault(false)
        Handler(Looper.getMainLooper()).post { onResult(ok) }
    }.start()
}
