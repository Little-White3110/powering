@file:OptIn(ExperimentalScrollBarApi::class)

package com.powerring.hole.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.powerring.hole.BuildConfig
import com.powerring.hole.R
import com.powerring.hole.core.MusicPlayback
import com.powerring.hole.data.AudioReactiveService
import com.powerring.hole.data.NotifStateBridge
import com.powerring.hole.data.RingNotificationListener
import com.powerring.hole.ring.ColorRange
import com.powerring.hole.ring.CustomColors
import com.powerring.hole.ring.MiuixPalette
import com.powerring.hole.ring.PercentNodes
import com.powerring.hole.ring.RingConfig
import com.powerring.hole.ring.StateColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
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
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.interfaces.ExperimentalScrollBarApi
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.RangeSliderPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

/**
 * 模块配置主框架：基础 / 动效 / 外观 / 关于 四个 Tab，
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
    // 当前正在编辑的颜色（标签 + 初值 + 确认回调）；null 表示弹窗关闭
    var colorEditing by remember { mutableStateOf<ColorEditing?>(null) }
    // 导入/导出弹窗种类；与 colorEditing 同理提升到根级，随 Tab 切换不丢
    var transferKind by remember { mutableStateOf<TransferKind?>(null) }
    // 电量节点编辑弹窗是否打开
    var nodesEditing by remember { mutableStateOf(false) }

    data class TabInfo(val label: String, val icon: ImageVector)
    val tabs = listOf(
        TabInfo("基础", MiuixIcons.Settings),
        TabInfo("动效", MiuixIcons.Tune),
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
                0 -> BasicTabContent(
                    ctx = ctx,
                    config = config,
                    update = { config = it },
                    contentPadding = contentPadding,
                    onEditNodes = { nodesEditing = true },
                    onEditColor = { colorEditing = it },
                )
                1 -> EffectsTabContent(
                    ctx = ctx,
                    config = config,
                    update = { config = it },
                    contentPadding = contentPadding,
                    onEditColor = { colorEditing = it },
                )
                2 -> AppearanceTabContent(
                    ctx = ctx,
                    config = config,
                    update = { config = it },
                    contentPadding = contentPadding,
                    onEditColor = { colorEditing = it },
                    onTransfer = { transferKind = it },
                )
                3 -> AboutTabContent(ctx, contentPadding)
            }
        }

        // 色盘弹窗
        ColorPickerDialog(editing = colorEditing, onDismiss = { colorEditing = null })

        // 外观配置导入/导出弹窗
        ConfigTransferDialog(
            kind = transferKind,
            config = config,
            onDismiss = { transferKind = null },
            onApply = { patched ->
                PrefsStore.applyAppearance(ctx, patched)
                config = patched
                transferKind = null
                Toast.makeText(ctx, "外观配置已导入", Toast.LENGTH_SHORT).show()
            },
        )

        // 电量节点编辑弹窗
        PercentNodesDialog(
            show = nodesEditing,
            initial = config.percentNodes,
            onDismiss = { nodesEditing = false },
            onConfirm = { nodes ->
                PrefsStore.setString(ctx, RingConfig.KEY_PERCENT_NODES, PercentNodes.encode(nodes))
                config = config.copy(percentNodes = nodes)
                nodesEditing = false
            },
        )
    }
}

// ====================================================================
// Tab 1：动效（充电动画 / 呼吸灯 / 消息提醒 / 音乐律动）
// ====================================================================

@Composable
private fun EffectsTabContent(
    ctx: Context,
    config: RingConfig,
    update: (RingConfig) -> Unit,
    contentPadding: PaddingValues,
    onEditColor: (ColorEditing) -> Unit,
) {
    // 打开共用色盘弹窗的简写（动画颜色可带「跟随环色」重置入口）
    fun editColor(
        label: String,
        argb: Int,
        onResult: (Int) -> Unit,
        onFollow: (() -> Unit)? = null,
    ) = buildColorEdit(onEditColor, label, argb, onResult, onFollow)

    val listState = rememberLazyListState()
    Box {
        LazyColumn(state = listState, contentPadding = contentPadding) {
            item(key = "animTitle") {
                SmallTitle("充电动画")
            }
            item(key = "chargingAnimCard") {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    OverlayDropdownPreference(
                        items = CHARGING_STYLE_ITEMS,
                        selectedIndex = config.chargingStyle
                            .coerceIn(0, CHARGING_STYLE_ITEMS.lastIndex),
                        title = "充电动画形式",
                        summary = "充电时环上的光效样式，灵感来自魅族环形呼吸灯",
                        enabled = config.ringEnabled,
                        onSelectedIndexChange = { index ->
                            PrefsStore.setInt(ctx, RingConfig.KEY_CHARGING_STYLE, index)
                            update(config.copy(chargingStyle = index))
                        },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    OverlayDropdownPreference(
                        items = CHARGING_ANIM_ITEMS,
                        selectedIndex = config.chargingAnimMode
                            .coerceIn(0, CHARGING_ANIM_ITEMS.lastIndex),
                        title = "动画覆盖范围",
                        summary = "智能：电量低时只在空白段流动，充到一定电量后整圈流动",
                        enabled = config.ringEnabled &&
                            config.chargingStyle != RingConfig.CHARGING_STYLE_PROGRESS,
                        onSelectedIndexChange = { index ->
                            PrefsStore.setInt(ctx, RingConfig.KEY_CHARGING_ANIM_MODE, index)
                            update(config.copy(chargingAnimMode = index))
                        },
                    )
                    if (config.chargingAnimMode == RingConfig.CHARGING_ANIM_SMART) {
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        ConfigSlider(
                            title = "整圈阈值",
                            summary = "电量达到这个值就由「空白段」切换为「整圈」流动（仅智能模式生效）",
                            value = config.chargingFullRingThreshold.toFloat(),
                            valueRange = RingConfig.THRESHOLD_MIN.toFloat()..
                                RingConfig.THRESHOLD_MAX.toFloat(),
                            valueText = "${config.chargingFullRingThreshold}%",
                            enabled = config.ringEnabled &&
                                config.chargingStyle != RingConfig.CHARGING_STYLE_PROGRESS,
                            transform = { it.roundToInt().toFloat() },
                            onChange = { v ->
                                update(config.copy(chargingFullRingThreshold = v.roundToInt()))
                            },
                            onCommit = { v ->
                                PrefsStore.setInt(
                                    ctx,
                                    RingConfig.KEY_CHARGING_FULL_THRESHOLD,
                                    v.roundToInt(),
                                )
                                update(config.copy(chargingFullRingThreshold = v.roundToInt()))
                            },
                        )
                    }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ArrowPreference(
                        title = "动画颜色",
                        summary = if (config.chargingColor == 0) {
                            "跟随环色"
                        } else {
                            "自定义 #${HexColor.format(config.chargingColor)}"
                        },
                        enabled = config.ringEnabled &&
                            config.chargingStyle != RingConfig.CHARGING_STYLE_PROGRESS,
                        onClick = editColor(
                            label = "充电动画颜色",
                            argb = if (config.chargingColor == 0) {
                                MiuixPalette.PRIMARY_DARK
                            } else {
                                config.chargingColor
                            },
                            onResult = { argb ->
                                PrefsStore.setInt(ctx, RingConfig.KEY_CHARGING_COLOR, argb)
                                update(config.copy(chargingColor = argb))
                            },
                            onFollow = {
                                PrefsStore.setInt(ctx, RingConfig.KEY_CHARGING_COLOR, 0)
                                update(config.copy(chargingColor = 0))
                            },
                        ),
                        endActions = { ColorSwatch(config.chargingColor) },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ConfigSlider(
                        title = "动画速度",
                        summary = "光点绕行快慢，50% 舒缓、200% 活泼",
                        value = config.chargingSpeedPercent.toFloat(),
                        valueRange = RingConfig.SPEED_MIN.toFloat()..RingConfig.SPEED_MAX.toFloat(),
                        valueText = "${config.chargingSpeedPercent}%",
                        enabled = config.ringEnabled &&
                            config.chargingStyle != RingConfig.CHARGING_STYLE_PROGRESS,
                        transform = { it.roundToInt().toFloat() },
                        onChange = { v ->
                            update(config.copy(chargingSpeedPercent = v.roundToInt()))
                        },
                        onCommit = { v ->
                            PrefsStore.setInt(ctx, RingConfig.KEY_CHARGING_SPEED, v.roundToInt())
                            update(config.copy(chargingSpeedPercent = v.roundToInt()))
                        },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ConfigSlider(
                        title = "动画幅度",
                        summary = "充电动画的起伏与光晕强弱（和音乐律动幅度同义）：0 只剩平静的" +
                            "进度弧、100 标准、越大越明显",
                        value = config.chargingStrengthPercent.toFloat(),
                        valueRange = RingConfig.STRENGTH_MIN.toFloat()..
                            RingConfig.STRENGTH_MAX.toFloat(),
                        valueText = "${config.chargingStrengthPercent}%",
                        enabled = config.ringEnabled &&
                            config.chargingStyle != RingConfig.CHARGING_STYLE_PROGRESS,
                        transform = { it.roundToInt().toFloat() },
                        onChange = { v ->
                            update(config.copy(chargingStrengthPercent = v.roundToInt()))
                        },
                        onCommit = { v ->
                            PrefsStore.setInt(
                                ctx, RingConfig.KEY_CHARGING_STRENGTH, v.roundToInt(),
                            )
                            update(config.copy(chargingStrengthPercent = v.roundToInt()))
                        },
                    )
                }
            }
            item(key = "breathingTitle") {
                SmallTitle("呼吸灯")
            }
            item(key = "breathingCard") {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    SwitchPreference(
                        checked = config.breathingEnabled,
                        onCheckedChange = { enabled ->
                            PrefsStore.setBoolean(ctx, RingConfig.KEY_BREATHING_ENABLED, enabled)
                            update(config.copy(breathingEnabled = enabled))
                        },
                        title = "呼吸灯效",
                        summary = "环按所选形式做呼吸/跑马光效，充电时更明显、节奏更快",
                        enabled = config.ringEnabled,
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    SwitchPreference(
                        checked = config.breathingOnIdle,
                        onCheckedChange = { enabled ->
                            PrefsStore.setBoolean(ctx, RingConfig.KEY_BREATHING_ON_IDLE, enabled)
                            update(config.copy(breathingOnIdle = enabled))
                        },
                        title = "平时也呼吸",
                        summary = "关闭后仅在充电（且充电形式为「进度弧」）时呼吸，平时保持静态",
                        enabled = config.ringEnabled && config.breathingEnabled,
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    OverlayDropdownPreference(
                        items = BREATHING_STYLE_ITEMS,
                        selectedIndex = config.breathingStyle
                            .coerceIn(0, BREATHING_STYLE_ITEMS.lastIndex),
                        title = "呼吸形式",
                        summary = "光晕呼吸 / 亮度脉动 / 跑马光点",
                        enabled = config.ringEnabled && config.breathingEnabled,
                        onSelectedIndexChange = { index ->
                            PrefsStore.setInt(ctx, RingConfig.KEY_BREATHING_STYLE, index)
                            update(config.copy(breathingStyle = index))
                        },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ArrowPreference(
                        title = "呼吸颜色",
                        summary = if (config.breathingColor == 0) {
                            "跟随环色"
                        } else {
                            "自定义 #${HexColor.format(config.breathingColor)}"
                        },
                        enabled = config.ringEnabled && config.breathingEnabled,
                        onClick = editColor(
                            label = "呼吸灯颜色",
                            argb = if (config.breathingColor == 0) {
                                MiuixPalette.PRIMARY_DARK
                            } else {
                                config.breathingColor
                            },
                            onResult = { argb ->
                                PrefsStore.setInt(ctx, RingConfig.KEY_BREATHING_COLOR, argb)
                                update(config.copy(breathingColor = argb))
                            },
                            onFollow = {
                                PrefsStore.setInt(ctx, RingConfig.KEY_BREATHING_COLOR, 0)
                                update(config.copy(breathingColor = 0))
                            },
                        ),
                        endActions = { ColorSwatch(config.breathingColor) },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ConfigSlider(
                        title = "呼吸速度",
                        summary = "呼吸/跑马的快慢，20% 很慢、100% 标准、200% 轻快",
                        value = config.breathingSpeedPercent.toFloat(),
                        valueRange = RingConfig.SPEED_MIN.toFloat()..RingConfig.SPEED_MAX.toFloat(),
                        valueText = "${config.breathingSpeedPercent}%",
                        enabled = config.ringEnabled && config.breathingEnabled,
                        transform = { it.roundToInt().toFloat() },
                        onChange = { v ->
                            update(config.copy(breathingSpeedPercent = v.roundToInt()))
                        },
                        onCommit = { v ->
                            PrefsStore.setInt(ctx, RingConfig.KEY_BREATHING_SPEED, v.roundToInt())
                            update(config.copy(breathingSpeedPercent = v.roundToInt()))
                        },
                    )
                }
            }
            item(key = "blinkTitle") {
                SmallTitle("消息提醒")
            }
            item(key = "blinkCard") {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    SwitchPreference(
                        checked = config.blinkOnNotification,
                        onCheckedChange = { enabled ->
                            PrefsStore.setBoolean(
                                ctx, RingConfig.KEY_BLINK_ON_NOTIFICATION, enabled,
                            )
                            update(config.copy(blinkOnNotification = enabled))
                        },
                        title = "消息提醒闪烁",
                        summary = "有未读通知时用所选颜色/形式提醒；常驻通知（如正在播放）不触发",
                        enabled = config.ringEnabled,
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    SwitchPreference(
                        checked = config.blinkOnScreen,
                        onCheckedChange = { enabled ->
                            PrefsStore.setBoolean(ctx, RingConfig.KEY_BLINK_ON_SCREEN, enabled)
                            update(config.copy(blinkOnScreen = enabled))
                        },
                        title = "亮屏时提醒",
                        summary = "正常使用（屏幕点亮）时播报未读提醒；息屏时圆环整体不绘制，" +
                            "不会在息屏页面留下亮斑",
                        enabled = config.ringEnabled && config.blinkOnNotification,
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    OverlayDropdownPreference(
                        items = BLINK_NOTIF_MODE_ITEMS,
                        selectedIndex = config.blinkNotifMode
                            .coerceIn(0, BLINK_NOTIF_MODE_ITEMS.lastIndex),
                        title = "提醒持续方式",
                        summary = "常驻：只要还有未读就一直淡淡呼吸；限时：通知到达后只播报一段时间",
                        enabled = config.ringEnabled && config.blinkOnNotification,
                        onSelectedIndexChange = { index ->
                            PrefsStore.setInt(ctx, RingConfig.KEY_BLINK_NOTIF_MODE, index)
                            update(config.copy(blinkNotifMode = index))
                        },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ConfigSlider(
                        title = "限时提醒时长",
                        summary = "仅「限时」模式生效：通知到达后播报多久（3–120 秒）",
                        value = config.blinkNotifDurationSeconds.toFloat(),
                        valueRange = RingConfig.NOTIF_DURATION_MIN.toFloat()..
                            RingConfig.NOTIF_DURATION_MAX.toFloat(),
                        valueText = "${config.blinkNotifDurationSeconds} 秒",
                        enabled = config.ringEnabled && config.blinkOnNotification &&
                            config.blinkNotifMode == RingConfig.BLINK_NOTIF_MODE_TIMED,
                        transform = { it.roundToInt().toFloat() },
                        onChange = { v ->
                            update(config.copy(blinkNotifDurationSeconds = v.roundToInt()))
                        },
                        onCommit = { v ->
                            PrefsStore.setInt(
                                ctx, RingConfig.KEY_BLINK_NOTIF_DURATION, v.roundToInt(),
                            )
                            update(config.copy(blinkNotifDurationSeconds = v.roundToInt()))
                        },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    NotificationAccessRow(
                        ctx = ctx,
                        enabled = config.ringEnabled && config.blinkOnNotification,
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    NotifDetectRow(
                        ctx = ctx,
                        enabled = config.ringEnabled && config.blinkOnNotification,
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ArrowPreference(
                        title = "提醒主色",
                        summary = "自定义 #${HexColor.format(config.blinkColorAlert)}",
                        enabled = config.ringEnabled && config.blinkOnNotification,
                        onClick = editColor(
                            label = "提醒主色",
                            argb = config.blinkColorAlert,
                            onResult = { argb ->
                                PrefsStore.setInt(ctx, RingConfig.KEY_BLINK_COLOR_ALERT, argb)
                                update(config.copy(blinkColorAlert = argb))
                            },
                        ),
                        endActions = { ColorSwatch(config.blinkColorAlert) },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ArrowPreference(
                        title = "提醒副色",
                        summary = "自定义 #${HexColor.format(config.blinkColorAlt)}（「呼吸脉冲」形式不使用）",
                        enabled = config.ringEnabled && config.blinkOnNotification,
                        onClick = editColor(
                            label = "提醒副色",
                            argb = config.blinkColorAlt,
                            onResult = { argb ->
                                PrefsStore.setInt(ctx, RingConfig.KEY_BLINK_COLOR_ALT, argb)
                                update(config.copy(blinkColorAlt = argb))
                            },
                        ),
                        endActions = { ColorSwatch(config.blinkColorAlt) },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    OverlayDropdownPreference(
                        items = BLINK_STYLE_ITEMS,
                        selectedIndex = config.blinkStyle.coerceIn(0, BLINK_STYLE_ITEMS.lastIndex),
                        title = "闪烁形式",
                        summary = "交替闪烁 / 呼吸脉冲 / 跑马警示",
                        enabled = config.ringEnabled && config.blinkOnNotification,
                        onSelectedIndexChange = { index ->
                            PrefsStore.setInt(ctx, RingConfig.KEY_BLINK_STYLE, index)
                            update(config.copy(blinkStyle = index))
                        },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ConfigSlider(
                        title = "提醒节奏",
                        summary = "常驻呼吸的快慢：20% 非常慢、100% 标准；进场快闪不受它影响，" +
                            "到达后固定快闪 1–2 下就转入这个慢呼吸",
                        value = config.blinkSpeedPercent.toFloat(),
                        valueRange = RingConfig.SPEED_MIN.toFloat()..RingConfig.SPEED_MAX.toFloat(),
                        valueText = "${config.blinkSpeedPercent}%",
                        enabled = config.ringEnabled && config.blinkOnNotification,
                        transform = { it.roundToInt().toFloat() },
                        onChange = { v ->
                            update(config.copy(blinkSpeedPercent = v.roundToInt()))
                        },
                        onCommit = { v ->
                            PrefsStore.setInt(ctx, RingConfig.KEY_BLINK_SPEED, v.roundToInt())
                            update(config.copy(blinkSpeedPercent = v.roundToInt()))
                        },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ConfigSlider(
                        title = "提醒幅度",
                        summary = "提醒闪烁的明暗深浅与光晕强弱（和音乐律动幅度同义）：0 几乎不摆、" +
                            "100 标准、越大越明显",
                        value = config.blinkStrengthPercent.toFloat(),
                        valueRange = RingConfig.STRENGTH_MIN.toFloat()..
                            RingConfig.STRENGTH_MAX.toFloat(),
                        valueText = "${config.blinkStrengthPercent}%",
                        enabled = config.ringEnabled && config.blinkOnNotification,
                        transform = { it.roundToInt().toFloat() },
                        onChange = { v ->
                            update(config.copy(blinkStrengthPercent = v.roundToInt()))
                        },
                        onCommit = { v ->
                            PrefsStore.setInt(
                                ctx, RingConfig.KEY_BLINK_STRENGTH, v.roundToInt(),
                            )
                            update(config.copy(blinkStrengthPercent = v.roundToInt()))
                        },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    SwitchPreference(
                        checked = config.blinkIntroEnabled,
                        onCheckedChange = { enabled ->
                            PrefsStore.setBoolean(
                                ctx, RingConfig.KEY_BLINK_INTRO_ENABLED, enabled,
                            )
                            update(config.copy(blinkIntroEnabled = enabled))
                        },
                        title = "新消息快速提示",
                        summary = "通知到达瞬间快闪 1–2 下（又快又鲜艳），之后转入很慢很淡的呼吸；" +
                            "关闭后直接慢呼吸",
                        enabled = config.ringEnabled && config.blinkOnNotification,
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ConfigSlider(
                        title = "提示时长",
                        summary = "快闪阶段持续多久（1–5 秒），结束后转入慢呼吸",
                        value = config.blinkIntroSeconds.toFloat(),
                        valueRange = RingConfig.INTRO_SECONDS_MIN.toFloat()..
                            RingConfig.INTRO_SECONDS_MAX.toFloat(),
                        valueText = "${config.blinkIntroSeconds} 秒",
                        enabled = config.ringEnabled && config.blinkOnNotification &&
                            config.blinkIntroEnabled,
                        transform = { it.roundToInt().toFloat() },
                        onChange = { v ->
                            update(config.copy(blinkIntroSeconds = v.roundToInt()))
                        },
                        onCommit = { v ->
                            PrefsStore.setInt(
                                ctx, RingConfig.KEY_BLINK_INTRO_SECONDS, v.roundToInt(),
                            )
                            update(config.copy(blinkIntroSeconds = v.roundToInt()))
                        },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ArrowPreference(
                        title = "预览提醒效果",
                        summary = "立即按当前样式播放一遍闪烁（约 4 秒），用来确认效果",
                        enabled = config.ringEnabled && config.blinkOnNotification,
                        onClick = { NotifStateBridge.requestPreview(ctx) },
                    )
                }
            }
            item(key = "musicTitle") {
                SmallTitle("音乐律动")
            }
            item(key = "musicCard") {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    SwitchPreference(
                        checked = config.musicPulseEnabled,
                        onCheckedChange = { enabled ->
                            PrefsStore.setBoolean(ctx, RingConfig.KEY_MUSIC_PULSE_ENABLED, enabled)
                            update(config.copy(musicPulseEnabled = enabled))
                        },
                        title = "音乐律动",
                        summary = "听歌时环像呼吸灯一样柔和地动；幅度克制，不抢屏幕主视觉",
                        enabled = config.ringEnabled,
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    OverlayDropdownPreference(
                        items = MUSIC_PULSE_ITEMS,
                        selectedIndex = config.musicPulseStyle
                            .coerceIn(0, MUSIC_PULSE_ITEMS.lastIndex),
                        title = "律动形式",
                        summary = "呼吸 / 跑马灯 / 节拍闪动 / 心跳 / 波浪 / 音量脉冲 / 人声·音乐。" +
                            "开了下方「音频驱动」后，呼吸与绕行的快慢、亮度都会跟着声音走",
                        enabled = config.ringEnabled && config.musicPulseEnabled,
                        onSelectedIndexChange = { index ->
                            PrefsStore.setInt(ctx, RingConfig.KEY_MUSIC_PULSE_STYLE, index)
                            update(config.copy(musicPulseStyle = index))
                        },
                    )
                    // v1.3.2 整理：「节奏」只对按周期呼吸/绕行的形式有意义；
                    // 「节拍闪动 / 心跳」由下面的 BPM 控制，这里就不再显示无关的滑杆。
                    if (config.musicPulseStyle != RingConfig.MUSIC_STYLE_BEAT &&
                        config.musicPulseStyle != RingConfig.MUSIC_STYLE_HEARTBEAT
                    ) {
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        ConfigSlider(
                            title = "节奏",
                            summary = "呼吸/绕行一个周期的时间，越大越慢越安静；开了音频驱动后，" +
                                "这是安静时的基准——声音一响呼吸会自动加快",
                            value = config.musicCycleSeconds.toFloat(),
                            valueRange = RingConfig.MUSIC_CYCLE_MIN.toFloat()..
                                RingConfig.MUSIC_CYCLE_MAX.toFloat(),
                            valueText = "${config.musicCycleSeconds} 秒",
                            enabled = config.ringEnabled && config.musicPulseEnabled,
                            transform = { it.roundToInt().toFloat() },
                            onChange = { v ->
                                update(config.copy(musicCycleSeconds = v.roundToInt()))
                            },
                            onCommit = { v ->
                                PrefsStore.setInt(
                                    ctx, RingConfig.KEY_MUSIC_CYCLE_SECONDS, v.roundToInt(),
                                )
                                update(config.copy(musicCycleSeconds = v.roundToInt()))
                            },
                        )
                    }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    SwitchPreference(
                        checked = config.musicColorCycleEnabled,
                        onCheckedChange = { enabled ->
                            PrefsStore.setBoolean(
                                ctx, RingConfig.KEY_MUSIC_COLOR_CYCLE, enabled,
                            )
                            update(config.copy(musicColorCycleEnabled = enabled))
                        },
                        title = "颜色缓慢渐变",
                        summary = "律动期间色相随时间慢慢转（一个周期转一圈），像 RGB 呼吸灯",
                        enabled = config.ringEnabled && config.musicPulseEnabled,
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ConfigSlider(
                        title = "律动幅度",
                        summary = "呼吸/光点的强弱，默认已很克制；几乎看不见再往上调",
                        value = config.musicPulseStrengthPercent.toFloat(),
                        valueRange = RingConfig.MUSIC_STRENGTH_MIN.toFloat()..
                            RingConfig.MUSIC_STRENGTH_MAX.toFloat(),
                        valueText = "${config.musicPulseStrengthPercent}%",
                        enabled = config.ringEnabled && config.musicPulseEnabled,
                        transform = { it.roundToInt().toFloat() },
                        onChange = { v ->
                            update(config.copy(musicPulseStrengthPercent = v.roundToInt()))
                        },
                        onCommit = { v ->
                            PrefsStore.setInt(
                                ctx, RingConfig.KEY_MUSIC_PULSE_STRENGTH, v.roundToInt(),
                            )
                            update(config.copy(musicPulseStrengthPercent = v.roundToInt()))
                        },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ConfigSlider(
                        title = "闪动范围",
                        summary = "一次脉动里明暗摆动的跨度：0 几乎不摆、越大越明显（默认 60%）。" +
                            "觉得律动看不出来就往上调",
                        value = config.musicFlashRangePercent.toFloat(),
                        valueRange = RingConfig.MUSIC_FLASH_MIN.toFloat()..
                            RingConfig.MUSIC_FLASH_MAX.toFloat(),
                        valueText = "${config.musicFlashRangePercent}%",
                        enabled = config.ringEnabled && config.musicPulseEnabled,
                        transform = { it.roundToInt().toFloat() },
                        onChange = { v ->
                            update(config.copy(musicFlashRangePercent = v.roundToInt()))
                        },
                        onCommit = { v ->
                            PrefsStore.setInt(
                                ctx, RingConfig.KEY_MUSIC_FLASH_RANGE, v.roundToInt(),
                            )
                            update(config.copy(musicFlashRangePercent = v.roundToInt()))
                        },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ConfigSlider(
                        title = "节拍速度",
                        summary = "「节拍闪动 / 心跳」生效：每分钟几下。默认 56（下限已放宽到 24）；" +
                            "觉得太快就往左调",
                        value = config.musicBeatBpm.toFloat(),
                        valueRange = RingConfig.MUSIC_BEAT_BPM_MIN.toFloat()..
                            RingConfig.MUSIC_BEAT_BPM_MAX.toFloat(),
                        valueText = "${config.musicBeatBpm} BPM",
                        enabled = config.ringEnabled && config.musicPulseEnabled &&
                            (config.musicPulseStyle == RingConfig.MUSIC_STYLE_BEAT ||
                                config.musicPulseStyle == RingConfig.MUSIC_STYLE_HEARTBEAT),
                        transform = { it.roundToInt().toFloat() },
                        onChange = { v ->
                            update(config.copy(musicBeatBpm = v.roundToInt()))
                        },
                        onCommit = { v ->
                            PrefsStore.setInt(
                                ctx, RingConfig.KEY_MUSIC_BEAT_BPM, v.roundToInt(),
                            )
                            update(config.copy(musicBeatBpm = v.roundToInt()))
                        },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    val audioPermLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission(),
                    ) { granted ->
                        PrefsStore.setBoolean(
                            ctx, RingConfig.KEY_MUSIC_AUDIO_REACTIVE, granted,
                        )
                        update(config.copy(musicAudioReactive = granted))
                        if (granted) AudioReactiveService.start(ctx)
                        else AudioReactiveService.stop(ctx)
                        Toast.makeText(
                            ctx,
                            if (granted) "已开启音频驱动" else "未授权录音，音频驱动已关闭",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    LaunchedEffect(config.musicAudioReactive) {
                        if (config.musicAudioReactive && hasRecordPermission(ctx)) {
                            AudioReactiveService.start(ctx)
                        } else if (config.musicAudioReactive) {
                            // 权限被撤销：自动关闭，避免开关看着开着、实际不生效
                            PrefsStore.setBoolean(
                                ctx, RingConfig.KEY_MUSIC_AUDIO_REACTIVE, false,
                            )
                            update(config.copy(musicAudioReactive = false))
                            AudioReactiveService.stop(ctx)
                        }
                    }
                    SwitchPreference(
                        checked = config.musicAudioReactive,
                        onCheckedChange = { enabled ->
                            if (!enabled) {
                                PrefsStore.setBoolean(
                                    ctx, RingConfig.KEY_MUSIC_AUDIO_REACTIVE, false,
                                )
                                update(config.copy(musicAudioReactive = false))
                                AudioReactiveService.stop(ctx)
                            } else if (hasRecordPermission(ctx)) {
                                PrefsStore.setBoolean(
                                    ctx, RingConfig.KEY_MUSIC_AUDIO_REACTIVE, true,
                                )
                                update(config.copy(musicAudioReactive = true))
                                AudioReactiveService.start(ctx)
                            } else {
                                audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        title = "音频驱动（需录音权限）",
                        summary = "用麦克风实时读取音量/内容，让律动真正跟着声音走；" +
                            "「音量脉冲 / 人声·音乐」靠它才准。首次开启会申请录音权限；" +
                            "机型不支持时可手动关掉",
                        enabled = config.ringEnabled && config.musicPulseEnabled,
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    OverlayDropdownPreference(
                        items = MUSIC_REACT_ITEMS,
                        selectedIndex = config.musicReactSource
                            .coerceIn(0, MUSIC_REACT_ITEMS.lastIndex),
                        title = "反应源",
                        summary = "音频驱动下：人声与音乐都反应 / 只随音乐 / 只随人声",
                        enabled = config.ringEnabled && config.musicPulseEnabled &&
                            config.musicAudioReactive,
                        onSelectedIndexChange = { index ->
                            PrefsStore.setInt(ctx, RingConfig.KEY_MUSIC_REACT_SOURCE, index)
                            update(config.copy(musicReactSource = index))
                        },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ConfigSlider(
                        title = "音频灵敏度",
                        summary = "越大越容易被声音点亮；觉得太容易亮或太迟钝就调它",
                        value = config.musicAudioSensitivityPercent.toFloat(),
                        valueRange = RingConfig.AUDIO_SENS_MIN.toFloat()..
                            RingConfig.AUDIO_SENS_MAX.toFloat(),
                        valueText = "${config.musicAudioSensitivityPercent}%",
                        enabled = config.ringEnabled && config.musicPulseEnabled &&
                            config.musicAudioReactive,
                        transform = { it.roundToInt().toFloat() },
                        onChange = { v ->
                            update(config.copy(musicAudioSensitivityPercent = v.roundToInt()))
                        },
                        onCommit = { v ->
                            PrefsStore.setInt(
                                ctx, RingConfig.KEY_MUSIC_AUDIO_SENS, v.roundToInt(),
                            )
                            update(config.copy(musicAudioSensitivityPercent = v.roundToInt()))
                        },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ArrowPreference(
                        title = "律动颜色",
                        summary = when {
                            config.musicColor == 0 && config.musicColorCycleEnabled ->
                                "跟随环色作渐变基准（白/灰会提为柔和彩色）"
                            config.musicColor == 0 -> "跟随环色"
                            else -> "自定义 #${HexColor.format(config.musicColor)}"
                        },
                        enabled = config.ringEnabled && config.musicPulseEnabled,
                        onClick = editColor(
                            label = "音乐律动颜色",
                            argb = if (config.musicColor == 0) {
                                MiuixPalette.PRIMARY_DARK
                            } else {
                                config.musicColor
                            },
                            onResult = { argb ->
                                PrefsStore.setInt(ctx, RingConfig.KEY_MUSIC_COLOR, argb)
                                update(config.copy(musicColor = argb))
                            },
                            onFollow = {
                                PrefsStore.setInt(ctx, RingConfig.KEY_MUSIC_COLOR, 0)
                                update(config.copy(musicColor = 0))
                            },
                        ),
                        endActions = { ColorSwatch(config.musicColor) },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    MusicDetectRow(
                        ctx = ctx,
                        enabled = config.ringEnabled && config.musicPulseEnabled,
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ArrowPreference(
                        title = "预览音乐律动",
                        summary = "不用真的放歌，立即按当前律动形式播放一遍（约 6 秒）",
                        enabled = config.ringEnabled && config.musicPulseEnabled,
                        onClick = { NotifStateBridge.requestMusicPreview(ctx) },
                    )
                }
            }
            item(key = "tigaTitle") {
                SmallTitle("低电量特效")
            }
            item(key = "tigaCard") {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    SwitchPreference(
                        checked = config.lowBatteryTigaEnabled,
                        onCheckedChange = { enabled ->
                            PrefsStore.setBoolean(ctx, RingConfig.KEY_LOW_BATTERY_TIGA, enabled)
                            update(config.copy(lowBatteryTigaEnabled = enabled))
                        },
                        title = "低电量迪迦计时器",
                        summary = "电量跌破阈值时，环像迪迦胸口的彩色计时器一样红色双闪，" +
                            "电量越低跳得越急；充电即停",
                        enabled = config.ringEnabled,
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ConfigSlider(
                        title = "触发电量",
                        summary = "低于这个电量开始告警（默认 20%，即最后一格电量）",
                        value = config.lowBatteryTigaThreshold.toFloat(),
                        valueRange = RingConfig.TIGA_THRESHOLD_MIN.toFloat()..
                            RingConfig.TIGA_THRESHOLD_MAX.toFloat(),
                        valueText = "${config.lowBatteryTigaThreshold}%",
                        enabled = config.ringEnabled && config.lowBatteryTigaEnabled,
                        transform = { it.roundToInt().toFloat() },
                        onChange = { v ->
                            update(config.copy(lowBatteryTigaThreshold = v.roundToInt()))
                        },
                        onCommit = { v ->
                            PrefsStore.setInt(
                                ctx, RingConfig.KEY_LOW_BATTERY_TIGA_THRESHOLD, v.roundToInt(),
                            )
                            update(config.copy(lowBatteryTigaThreshold = v.roundToInt()))
                        },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ConfigSlider(
                        title = "闪烁频率",
                        summary = "双闪节奏快慢：100% 为基准，往左更沉稳、往右更急促" +
                            "（电量越低本身就会越急，这里是在那条曲线上再整体调）",
                        value = config.lowBatteryTigaSpeedPercent.toFloat(),
                        valueRange = RingConfig.SPEED_MIN.toFloat()..RingConfig.SPEED_MAX.toFloat(),
                        valueText = "${config.lowBatteryTigaSpeedPercent}%",
                        enabled = config.ringEnabled && config.lowBatteryTigaEnabled,
                        transform = { it.roundToInt().toFloat() },
                        onChange = { v ->
                            update(config.copy(lowBatteryTigaSpeedPercent = v.roundToInt()))
                        },
                        onCommit = { v ->
                            PrefsStore.setInt(
                                ctx, RingConfig.KEY_LOW_BATTERY_TIGA_SPEED, v.roundToInt(),
                            )
                            update(config.copy(lowBatteryTigaSpeedPercent = v.roundToInt()))
                        },
                    )
                }
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
// Tab 0：基础（环形电量开关 / 点击与电量节点 / 屏幕保护 / 系统）
// ====================================================================

@Composable
private fun BasicTabContent(
    ctx: Context,
    config: RingConfig,
    update: (RingConfig) -> Unit,
    contentPadding: PaddingValues,
    onEditNodes: () -> Unit,
    onEditColor: (ColorEditing) -> Unit,
) {
    // 人脸识别「融合到环」的成功/失败结论色也在这里编辑，因此需要色盘回调
    fun editColor(label: String, argb: Int, onResult: (Int) -> Unit) =
        buildColorEdit(onEditColor, label, argb, onResult)

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
                }
            }
            // v1.3.2 整理：四条「什么时候把环收起来 / 交还电池」的开关原来分散在
            // 「环形电量」和「显示时机」两处，现在合并成独立的一组，一眼能找到。
            item(key = "displayTitle") {
                SmallTitle("显示与收起")
            }
            item(key = "displayCard") {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
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
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    SwitchPreference(
                        checked = config.collapseOnShade,
                        onCheckedChange = { enabled ->
                            PrefsStore.setBoolean(
                                ctx, RingConfig.KEY_COLLAPSE_ON_SHADE, enabled,
                            )
                            update(config.copy(collapseOnShade = enabled))
                        },
                        title = "下拉面板时隐藏圆环",
                        summary = "下拉通知栏或控制中心时，圆环向内收缩并淡出，面板收起后弹回；开启期间下拉状态的截屏同样不含圆环",
                        enabled = config.ringEnabled,
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    SwitchPreference(
                        checked = config.hideOnScreenshot,
                        onCheckedChange = { enabled ->
                            PrefsStore.setBoolean(
                                ctx, RingConfig.KEY_HIDE_ON_SCREENSHOT, enabled,
                            )
                            update(config.copy(hideOnScreenshot = enabled))
                        },
                        title = "截图时隐藏圆环",
                        summary = "截图与录屏画面中不再包含电量环（屏幕上仍正常显示）；开关即时生效，无需重启系统界面",
                        enabled = config.ringEnabled,
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    SwitchPreference(
                        checked = config.hideInShade,
                        onCheckedChange = { enabled ->
                            PrefsStore.setBoolean(ctx, RingConfig.KEY_HIDE_IN_SHADE, enabled)
                            update(config.copy(hideInShade = enabled))
                        },
                        title = "通知中心里隐藏",
                        summary = "下拉左侧「通知中心」时收起圆环，并交还状态栏原生电池图标，" +
                            "面板里显示正常电量效果；收起面板后环自动回来",
                        enabled = config.ringEnabled,
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    SwitchPreference(
                        checked = config.hideInControlCenter,
                        onCheckedChange = { enabled ->
                            PrefsStore.setBoolean(
                                ctx, RingConfig.KEY_HIDE_IN_CONTROL_CENTER, enabled,
                            )
                            update(config.copy(hideInControlCenter = enabled))
                        },
                        title = "控制中心里隐藏",
                        summary = "下拉右侧「控制中心」（开关/亮度/音量那一屏）时同样收起圆环并交还" +
                            "原生电池图标。通知中心与控制中心是两块独立面板，需要各自勾选",
                        enabled = config.ringEnabled,
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    SwitchPreference(
                        checked = config.hideRingOnLockScreen,
                        onCheckedChange = { enabled ->
                            PrefsStore.setBoolean(
                                ctx, RingConfig.KEY_HIDE_RING_ON_LOCK_SCREEN, enabled,
                            )
                            update(config.copy(hideRingOnLockScreen = enabled))
                        },
                        title = "锁屏时隐藏圆环",
                        summary = "锁屏上保留系统原样的锁屏界面，只去掉挖孔的圆环并交还原生电池图标；" +
                            "解锁后环自动回来。与人脸识别图标那几档互不影响",
                        enabled = config.ringEnabled,
                    )
                }
            }
            item(key = "tapTitle") {
                SmallTitle("点击与电量节点")
            }
            item(key = "tapCard") {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    SwitchPreference(
                        checked = config.tapShowPercent,
                        onCheckedChange = { enabled ->
                            PrefsStore.setBoolean(ctx, RingConfig.KEY_TAP_SHOW_PERCENT, enabled)
                            update(config.copy(tapShowPercent = enabled))
                        },
                        title = "点击显示电量",
                        summary = "轻点挖孔区域，环下方短暂显示具体电量数字（显示时长可调）",
                        enabled = config.ringEnabled,
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ConfigSlider(
                        title = "显示时长",
                        summary = "电量数字停留多久；点击挖孔与「电量节点」自动弹出共用此设置",
                        value = config.tapPercentDurationMs / 1000f,
                        valueRange = RingConfig.TAP_DURATION_MIN_MS / 1000f..
                            RingConfig.TAP_DURATION_MAX_MS / 1000f,
                        valueText = String.format("%.1f 秒", config.tapPercentDurationMs / 1000f),
                        enabled = config.ringEnabled &&
                            (config.tapShowPercent || config.percentNodesEnabled),
                        transform = { (it * 10).roundToInt() / 10f },
                        onChange = { v ->
                            update(config.copy(tapPercentDurationMs = (v * 1000).roundToInt()))
                        },
                        onCommit = { v ->
                            PrefsStore.setInt(
                                ctx, RingConfig.KEY_TAP_PERCENT_DURATION, (v * 1000).roundToInt(),
                            )
                            update(config.copy(tapPercentDurationMs = (v * 1000).roundToInt()))
                        },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    SwitchPreference(
                        checked = config.percentNodesEnabled,
                        onCheckedChange = { enabled ->
                            PrefsStore.setBoolean(
                                ctx, RingConfig.KEY_PERCENT_NODES_ENABLED, enabled,
                            )
                            update(config.copy(percentNodesEnabled = enabled))
                        },
                        title = "节点自动显示电量",
                        summary = "电量跨过设置的节点时，自动短暂显示一次具体电量数字",
                        enabled = config.ringEnabled,
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ArrowPreference(
                        title = "电量节点",
                        summary = "到达这些电量时自动显示数字：${PercentNodes.describe(config.percentNodes)}",
                        enabled = config.ringEnabled && config.percentNodesEnabled,
                        onClick = onEditNodes,
                    )
                }
            }
            item(key = "burnInTitle") {
                SmallTitle("亮度与护屏")
            }
            item(key = "burnInCard") {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    SwitchPreference(
                        checked = config.burnInProtection,
                        onCheckedChange = { enabled ->
                            PrefsStore.setBoolean(ctx, RingConfig.KEY_BURN_IN_PROTECTION, enabled)
                            update(config.copy(burnInProtection = enabled))
                        },
                        title = "防烧屏",
                        summary = "环以亚像素级缓慢漂移（每 5 秒约 0.2 像素，肉眼不可见），" +
                            "并限制最高亮度，避免固定图形长期静置在 OLED 上留下残影",
                        enabled = config.ringEnabled,
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ConfigSlider(
                        title = "亮度上限",
                        summary = "环、底槽与呼吸光晕的透明度上限；调低更护屏，调高更醒目",
                        value = config.maxBrightnessPercent.toFloat(),
                        valueRange = RingConfig.BRIGHTNESS_MIN.toFloat()..
                            RingConfig.BRIGHTNESS_MAX.toFloat(),
                        valueText = "${config.maxBrightnessPercent}%",
                        enabled = config.ringEnabled && config.burnInProtection,
                        transform = { it.roundToInt().toFloat() },
                        onChange = { v ->
                            update(config.copy(maxBrightnessPercent = v.roundToInt()))
                        },
                        onCommit = { v ->
                            PrefsStore.setInt(
                                ctx, RingConfig.KEY_MAX_BRIGHTNESS_PERCENT, v.roundToInt(),
                            )
                            update(config.copy(maxBrightnessPercent = v.roundToInt()))
                        },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ConfigSlider(
                        title = "发光强度",
                        summary = "所有光晕（呼吸/充电/提醒/音乐）的整体亮度；0 = 完全不发光。" +
                            "白底浅色环上光晕会把细微位移放大，关掉更干净；深色背景可调高更醒目",
                        value = config.glowStrengthPercent.toFloat(),
                        valueRange = RingConfig.GLOW_MIN.toFloat()..RingConfig.GLOW_MAX.toFloat(),
                        valueText = if (config.glowStrengthPercent == 0) {
                            "不发光"
                        } else {
                            "${config.glowStrengthPercent}%"
                        },
                        enabled = config.ringEnabled,
                        transform = { it.roundToInt().toFloat() },
                        onChange = { v ->
                            update(config.copy(glowStrengthPercent = v.roundToInt()))
                        },
                        onCommit = { v ->
                            PrefsStore.setInt(
                                ctx, RingConfig.KEY_GLOW_STRENGTH, v.roundToInt(),
                            )
                            update(config.copy(glowStrengthPercent = v.roundToInt()))
                        },
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
    onEditColor: (ColorEditing) -> Unit,
    onTransfer: (TransferKind) -> Unit,
) {
    // 打开共用色盘弹窗的简写，避免每一行都重复四个实参
    fun editColor(label: String, argb: Int, onResult: (Int) -> Unit) =
        buildColorEdit(onEditColor, label, argb, onResult)

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
                        title = "贴孔间隙",
                        summary = "环内边与挖孔边缘的距离；系统安全区普遍比物理孔大一圈，" +
                            "缝隙明显就往负调（可到 -6dp，直接压到孔边）",
                        value = config.gapDp,
                        valueRange = RingConfig.GAP_MIN..RingConfig.GAP_MAX,
                        valueText = String.format("%.1f dp", config.gapDp),
                        enabled = config.ringEnabled,
                        transform = { (it * 10).roundToInt() / 10f },
                        onChange = { update(config.copy(gapDp = it)) },
                        onCommit = {
                            PrefsStore.setFloat(ctx, RingConfig.KEY_GAP_DP, it)
                        },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
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
                        title = "底槽浓度",
                        summary = "未充满部分的浅色轨道圆的可见度；0 = 完全隐藏。" +
                            "白底上觉得那圈浅灰像印记的话，调到 0 就干净了",
                        value = config.trackOpacityPercent.toFloat(),
                        valueRange = RingConfig.TRACK_OPACITY_MIN.toFloat()..
                            RingConfig.TRACK_OPACITY_MAX.toFloat(),
                        valueText = if (config.trackOpacityPercent == 0) "隐藏" else
                            "${config.trackOpacityPercent}%",
                        enabled = config.ringEnabled,
                        transform = { it.roundToInt().toFloat() },
                        onChange = { v ->
                            update(config.copy(trackOpacityPercent = v.roundToInt()))
                        },
                        onCommit = { v ->
                            PrefsStore.setInt(
                                ctx, RingConfig.KEY_TRACK_OPACITY, v.roundToInt(),
                            )
                            update(config.copy(trackOpacityPercent = v.roundToInt()))
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
                    OverlayDropdownPreference(
                        items = COLOR_MODE_ITEMS,
                        selectedIndex = config.colorMode.coerceIn(0, COLOR_MODE_ITEMS.lastIndex),
                        title = "环颜色模式",
                        summary = "四种方式互斥，同一时刻只有一套颜色生效",
                        enabled = config.ringEnabled,
                        onSelectedIndexChange = { index ->
                            PrefsStore.setInt(ctx, RingConfig.KEY_COLOR_MODE, index)
                            update(config.copy(colorMode = index))
                        },
                    )
                    when (config.colorMode) {
                        RingConfig.MODE_FIXED_COLOR -> {
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                            ArrowPreference(
                                title = "选择颜色",
                                summary = "打开色盘设置固定环颜色，可输入十六进制精确取值",
                                enabled = config.ringEnabled,
                                onClick = editColor(
                                    label = "选择环颜色",
                                    argb = config.customColor,
                                    onResult = { argb ->
                                        PrefsStore.setInt(ctx, RingConfig.KEY_CUSTOM_COLOR, argb)
                                        update(config.copy(customColor = argb))
                                    },
                                ),
                                endActions = { ColorSwatch(config.customColor) },
                            )
                        }

                        RingConfig.MODE_BATTERY_STATE -> {
                            STATE_ROWS.forEach { row ->
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                                val current = row.get(config.stateColors)
                                ArrowPreference(
                                    title = row.title,
                                    summary = if (current == 0) {
                                        "未设置：${row.hint}"
                                    } else {
                                        "自定义 #${HexColor.format(current)}"
                                    },
                                    enabled = config.ringEnabled,
                                    onClick = editColor(
                                        label = "${row.title} 环颜色",
                                        // 未设置时不能把 0 当色盘初值：0 是全透明黑，ColorPicker 会沿用
                                        // alpha=0，确认下来仍是 0，等于把「未设置」又写回「未设置」
                                        argb = if (current == 0) MiuixPalette.PRIMARY_DARK else current,
                                        onResult = { argb ->
                                            PrefsStore.setInt(ctx, row.key, argb)
                                            val next = row.set(config.stateColors, argb)
                                            update(config.copy(stateColors = next))
                                        },
                                    ),
                                    endActions = { ColorSwatch(current) },
                                )
                            }
                        }

                        RingConfig.MODE_LEVEL_RANGE -> {
                            val ranges = config.levelRanges
                            if (ranges.isEmpty()) {
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                                Text(
                                    text = "暂无区间：此时环跟随系统电池图标色。",
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                            ranges.forEachIndexed { index, range ->
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                                val persist: (List<ColorRange>) -> Unit = { next ->
                                    PrefsStore.setString(
                                        ctx,
                                        RingConfig.KEY_LEVEL_RANGES,
                                        CustomColors.encodeRanges(next),
                                    )
                                    update(config.copy(levelRanges = next))
                                }
                                RangeColorRow(
                                    title = "区间 ${index + 1}",
                                    range = range,
                                    enabled = config.ringEnabled,
                                    onDragged = { moved ->
                                        update(
                                            config.copy(
                                                levelRanges = ranges.mapIndexed { i, r ->
                                                    if (i == index) moved else r
                                                },
                                            ),
                                        )
                                    },
                                    onCommitted = { moved ->
                                        persist(
                                            ranges.mapIndexed { i, r ->
                                                if (i == index) moved else r
                                            },
                                        )
                                    },
                                    onColorClicked = editColor(
                                        label = "区间 ${index + 1} 颜色",
                                        argb = range.color,
                                    ) { argb ->
                                        persist(
                                            ranges.mapIndexed { i, r ->
                                                if (i == index) r.copy(color = argb) else r
                                            },
                                        )
                                    },
                                    onDelete = {
                                        persist(ranges.filterIndexed { i, _ -> i != index })
                                    },
                                )
                            }
                            if (ranges.size < CustomColors.MAX_RANGES) {
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                                Button(
                                    onClick = {
                                        // 新区间插到最前 = 优先级最高（命中按列表顺序）
                                        val next = listOf(
                                            ColorRange(1, 20, MiuixPalette.PRIMARY_DARK),
                                        ) + ranges
                                        PrefsStore.setString(
                                            ctx,
                                            RingConfig.KEY_LEVEL_RANGES,
                                            CustomColors.encodeRanges(next),
                                        )
                                        update(config.copy(levelRanges = next))
                                    },
                                    enabled = config.ringEnabled,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                ) {
                                    Text(text = "新增区间（最多 ${CustomColors.MAX_RANGES} 段）")
                                }
                            }
                        }
                    }
                }
            }

            item(key = "transferTitle") {
                SmallTitle("配置导入导出")
            }
            item(key = "transferCard") {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    ArrowPreference(
                        title = "导出外观配置",
                        summary = "生成 JSON 并复制到剪贴板，可发送给他人",
                        onClick = { onTransfer(TransferKind.Export) },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ArrowPreference(
                        title = "导入外观配置",
                        summary = "粘贴他人导出的 JSON，校验通过后一次性覆盖外观设置",
                        onClick = { onTransfer(TransferKind.Import) },
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

private const val REPO_URL = "https://github.com/Little-white3110/powering"

/**
 * 打开项目仓库页面。
 *
 * 不用 `resolveActivity` 判断——Android 11+ 的包可见性过滤会让隐式 Intent 查不到
 * 处理器而误报失败，直接 `startActivity` 不受该限制。真没有可处理 http 的 Activity
 * 时（定制系统裁掉浏览器）退回复制链接，不弹异常。
 */
private fun openRepo(ctx: Context) {
    val ok = runCatching {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.isSuccess
    if (ok) return
    val copied = runCatching {
        ctx.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("HolePowerRing 仓库", REPO_URL))
    }.isSuccess
    Toast.makeText(
        ctx,
        if (copied) "未找到浏览器，链接已复制到剪贴板" else "打开失败：未找到浏览器",
        Toast.LENGTH_SHORT,
    ).show()
}

// ====================================================================
// 通知使用权（消息提醒的主通道）
// ====================================================================

/** 本应用是否已获得「通知使用权」。 */
private fun isNotificationAccessGranted(ctx: Context): Boolean {
    val flat = runCatching {
        Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
    }.getOrNull() ?: return false
    return flat.split(':').any { entry ->
        entry.isNotBlank() &&
            ComponentName.unflattenFromString(entry)?.packageName == ctx.packageName
    }
}

/** 跳系统「通知使用权」授权页（各版本入口名不同，用标准 Action 由系统路由）。 */
private fun openNotificationAccessSettings(ctx: Context) {
    val ok = runCatching {
        ctx.startActivity(
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.isSuccess
    if (!ok) {
        Toast.makeText(ctx, "未找到通知使用权设置页，请手动前往", Toast.LENGTH_SHORT).show()
    }
}

/**
 * 「通知使用权」状态行：显示是否已授权，点按跳到系统设置。
 *
 * 为什么要授权：消息提醒闪烁走的是通知监听**公开 API**——系统把通知增删回调给
 * 本应用，再同步给系统界面进程。这条路跨机型稳定；未授权时只能靠反射 Hook
 * 兜底，多数 HyperOS 版本无效。
 *
 * 状态用轻量轮询（1.5s）刷新，从系统设置返回后无需重进 App 就会更新。
 */
@Composable
private fun NotificationAccessRow(ctx: Context, enabled: Boolean) {
    var granted by remember { mutableStateOf(isNotificationAccessGranted(ctx)) }
    LaunchedEffect(enabled) {
        if (!enabled) return@LaunchedEffect
        while (true) {
            granted = isNotificationAccessGranted(ctx)
            delay(1500)
        }
    }
    ArrowPreference(
        title = if (granted) "通知使用权：已授权" else "通知使用权：未授权（点此开启）",
        summary = if (granted) {
            "消息提醒闪烁已就绪"
        } else {
            "消息提醒要生效必须先开启「通知使用权」；未开启时多数机型不会闪烁"
        },
        enabled = enabled,
        onClick = { openNotificationAccessSettings(ctx) },
    )
}

/**
 * 「音乐律动」的实时检测状态行。
 *
 * 用途是排查「听歌时环没反应」到底是哪一环出问题：这里显示「正在播放」
 * 说明系统确实上报了音乐播放状态（问题在渲染）；一直显示「未检测到」
 * 说明是检测侧拿不到状态。判断口径与 SystemUI 侧完全一致。
 */
@Composable
private fun MusicDetectRow(ctx: Context, enabled: Boolean) {
    var playing by remember { mutableStateOf(isMusicDetected(ctx)) }
    var source by remember { mutableStateOf(MusicPlayback.lastSource) }
    LaunchedEffect(enabled) {
        if (!enabled) return@LaunchedEffect
        while (true) {
            playing = isMusicDetected(ctx)
            source = MusicPlayback.lastSource
            delay(1000)
        }
    }
    val srcText = when (source) {
        MusicPlayback.Source.PLAYER_STATE -> "精确（读到播放器状态）"
        MusicPlayback.Source.AUDIO_STREAM -> "音频流（反射不可用，退化为系统判定）"
        MusicPlayback.Source.NONE -> "未取到"
    }
    ArrowPreference(
        title = if (playing) "音乐检测：正在播放" else "音乐检测：未检测到",
        summary = if (playing) {
            "已识别到音乐播放（仅音乐，视频/游戏不算），环会进入律动；判定依据：$srcText"
        } else {
            "放歌时这里应变为「正在播放」。若没放歌却显示「正在播放」，请把判定依据（$srcText）反馈：" +
                "若为「音频流」说明本机型反射不可用，需要换判定方式"
        },
        enabled = enabled,
        onClick = {},
    )
}

/** 与 SystemUI 侧口径完全一致的音乐判断，见 [MusicPlayback]。 */
private fun isMusicDetected(ctx: Context): Boolean = MusicPlayback.isPlaying(ctx)

/**
 * 「消息提醒」的实时检测状态行（v0.7.0）。
 *
 * 用来排查「通知早清了、环还在慢慢呼吸」这类脏状态：
 * - 显示「已授权」却仍显示「有提醒」→ 说明系统里**确实**还有可提醒通知，
 *   或应用侧集合没被清掉（会 4 秒自愈一次）；
 * - 显示「未授权」时环的提醒由 SystemUI 侧反射兜底通道驱动，
 *   本行只作参考。想彻底准确，建议开「通知使用权」。
 */
@Composable
private fun NotifDetectRow(ctx: Context, enabled: Boolean) {
    val granted = isNotificationAccessGranted(ctx)
    var active by remember { mutableStateOf(RingNotificationListener.lastActive) }
    var count by remember { mutableStateOf(RingNotificationListener.lastCount) }
    LaunchedEffect(enabled) {
        if (!enabled) return@LaunchedEffect
        while (true) {
            active = RingNotificationListener.lastActive
            count = RingNotificationListener.lastCount
            delay(1000)
        }
    }
    ArrowPreference(
        title = if (active) "通知提醒：有 $count 条未读提醒" else "通知提醒：无未读提醒",
        summary = if (!granted) {
            "未授权「通知使用权」，本行仅供参考（实际由系统界面内兜底通道驱动）"
        } else if (active) {
            "清除通知后这里应在 1–4 秒内变为「无未读提醒」；若一直是「有」但通知栏已空，请反馈"
        } else {
            "当前没有可提醒的通知，环不会进入提醒呼吸"
        },
        enabled = enabled,
        onClick = {},
    )
}

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
                    ArrowPreference(
                        title = "项目仓库",
                        summary = REPO_URL,
                        onClick = { openRepo(ctx) },
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
                        text = "提示：所有调节即时生效；息屏时圆环整体不绘制（提醒只在亮屏时播报）。" +
                            "状态栏自动收起（沉浸模式）时圆环同步收缩隐藏；下拉通知中心 / 控制中心" +
                            "时圆环平滑收起并交还原生电池图标，收起面板后平滑恢复。" +
                            "横屏时圆环无法贴合挖孔，默认会自动恢复原生电池图标。" +
                            "环颜色有四种互斥模式：跟随系统、固定单色、按电池状态、按电量区间；" +
                            "未设置的状态色会自动跟随原生图标。" +
                            "若环与挖孔之间缝隙明显，先用「贴孔间隙」往负调（安全区普遍大于物理孔）；" +
                            "若整体偏了再用「缩放」对齐半径、偏移微调中心。" +
                            "防烧屏：环以亚像素级漂移且可限最高亮度；白底上若觉得底槽/光晕像印记，" +
                            "可把「底槽浓度」或「发光强度」调到 0。",
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

// ====================================================================
// 颜色：模式表、状态色行表、色块、编辑入口
// ====================================================================

private val COLOR_MODE_ITEMS = listOf(
    "跟随系统电池图标",
    "固定单色",
    "按电池状态",
    "按电量区间",
)

/** 充电动画形式表；索引与 RingConfig.CHARGING_STYLE_* 一一对应。 */
private val CHARGING_STYLE_ITEMS = listOf(
    "进度弧（经典）",
    "光点绕圈",
    "双光点追逐",
    "彗尾流水",
    "整环脉冲（心跳）",
    "能量扩散（充能）",
    "呼吸光点",
    "双彗尾",
    "脉冲扫掠",
)

/** 充电动画覆盖范围表；索引与 RingConfig.CHARGING_ANIM_* 一一对应。 */
private val CHARGING_ANIM_ITEMS = listOf(
    "智能（按电量切换）",
    "一直整圈",
    "一直空白段",
)

/** 呼吸灯形式表；索引与 RingConfig.BREATHING_STYLE_* 一一对应。 */
private val BREATHING_STYLE_ITEMS = listOf(
    "光晕呼吸",
    "亮度脉动",
    "跑马光点",
    "双段柔光",
    "呼吸跑马",
)

/** 提醒闪烁形式表；索引与 RingConfig.BLINK_STYLE_* 一一对应。 */
private val BLINK_STYLE_ITEMS = listOf(
    "交替闪烁",
    "呼吸脉冲",
    "跑马警示",
    "双闪",
    "涟漪警示",
)

/** 提醒持续方式；索引与 RingConfig.BLINK_NOTIF_MODE_* 一一对应。 */
private val BLINK_NOTIF_MODE_ITEMS = listOf(
    "常驻（有未读就一直淡淡呼吸）",
    "限时（只播报一段时间）",
)


/** 音乐律动形式表；索引与 RingConfig.MUSIC_STYLE_* 一一对应。 */
private val MUSIC_PULSE_ITEMS = listOf(
    "呼吸",
    "跑马灯",
    "节拍闪动（跟拍）",
    "心跳",
    "波浪",
    "音量脉冲（跟声音）",
    "人声/音乐",
)

/** 音频驱动「反应源」表；索引与 RingConfig.MUSIC_REACT_* 一一对应。 */
private val MUSIC_REACT_ITEMS = listOf(
    "全部（人声与音乐都反应）",
    "只随音乐",
    "只随人声",
)

/** 「按电池状态」的一行配置：显示名、配置 key、未设置时的回退说明。 */
private data class StateRowSpec(
    val title: String,
    val key: String,
    val hint: String,
    val get: (StateColors) -> Int,
    val set: (StateColors, Int) -> StateColors,
)

private val STATE_ROWS = listOf(
    StateRowSpec(
        "普通", RingConfig.KEY_STATE_COLOR_NORMAL, "跟随系统电池图标反色",
        { it.normal }, { s, v -> s.copy(normal = v) },
    ),
    StateRowSpec(
        "低电量", RingConfig.KEY_STATE_COLOR_LOW, "跟随系统低电色（无则 error 红）",
        { it.low }, { s, v -> s.copy(low = v) },
    ),
    StateRowSpec(
        "省电模式", RingConfig.KEY_STATE_COLOR_POWER_SAVE, "跟随系统省电色（无则琥珀）",
        { it.powerSave }, { s, v -> s.copy(powerSave = v) },
    ),
    StateRowSpec(
        "性能模式", RingConfig.KEY_STATE_COLOR_PERFORMANCE, "跟随系统性能色（无则橙）",
        { it.performance }, { s, v -> s.copy(performance = v) },
    ),
    StateRowSpec(
        "充电中", RingConfig.KEY_STATE_COLOR_CHARGING, "跟随系统充电色（无则 primary 蓝）",
        { it.charging }, { s, v -> s.copy(charging = v) },
    ),
)

/** 行尾色块。0（未设置）用半透明主色，与已设置的颜色区分开。 */
@Composable
private fun ColorSwatch(argb: Int) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(
                if (argb == 0) MiuixTheme.colorScheme.primary.copy(alpha = 0.25f) else Color(argb),
            ),
    )
}

/**
 * 一条电量区间：`RangeSliderPreference` 双端滑杆（0–100，整数步进）+ 行尾色块 + 删除。
 *
 * 与 `ConfigSlider` 同一套「真实按下」门控：miuix Slider 在首次组合时可能回调
 * onValueChange / onValueChangeFinished，未按下的回调一律不落盘，否则一进页面
 * 就把默认区间写进配置。拖动中只改内存态，松手才持久化。
 */
@Composable
private fun RangeColorRow(
    title: String,
    range: ColorRange,
    enabled: Boolean,
    onDragged: (ColorRange) -> Unit,
    onCommitted: (ColorRange) -> Unit,
    onColorClicked: () -> Unit,
    onDelete: () -> Unit,
) {
    var dragging by remember { mutableStateOf<ClosedFloatingPointRange<Float>?>(null) }
    var touched by remember { mutableStateOf(false) }
    val display = dragging ?: (range.start.toFloat()..range.end.toFloat())

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

    RangeSliderPreference(
        value = display,
        onValueChange = {
            val snapped = it.start.roundToInt()..it.endInclusive.roundToInt()
            dragging = snapped.start.toFloat()..snapped.endInclusive.toFloat()
            if (touched) onDragged(ColorRange(snapped.start, snapped.endInclusive, range.color))
        },
        onValueChangeFinished = {
            if (touched) {
                dragging?.let {
                    onCommitted(ColorRange(it.start.toInt(), it.endInclusive.toInt(), range.color))
                }
            }
            touched = false
            dragging = null
        },
        title = title,
        summary = "色块改颜色，右端按钮删除本区间",
        valueText = "${range.start}%–${range.end}%",
        valueRange = 0f..100f,
        steps = 99,
        enabled = enabled,
        modifier = touchGate,
        endActions = {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color(range.color))
                    .clickable(enabled = enabled, onClick = onColorClicked),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = onDelete,
                enabled = enabled,
            ) {
                Text(text = "删除")
            }
        },
    )
}

/**
 * 打开共用色盘弹窗。返回一个可直接当 `onClick` 用的 lambda。
 *
 * 注意：`onResult` 捕获的是打开弹窗那一刻的 `config` 快照——弹窗打开期间
 * 不会有并发的配置变更（所有写入都只发生在「确定」），因此 `copy` 不会覆盖掉
 * 别的字段。
 */
private fun buildColorEdit(
    onEditColor: (ColorEditing) -> Unit,
    label: String,
    argb: Int,
    onResult: (Int) -> Unit,
    onFollow: (() -> Unit)? = null,
): () -> Unit = { onEditColor(ColorEditing(label, argb, onResult, onFollow)) }

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

/** 是否已授予录音权限（音频驱动律动需要）。 */
private fun hasRecordPermission(ctx: Context): Boolean =
    ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

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
