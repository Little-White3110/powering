@file:OptIn(ExperimentalScrollBarApi::class)

package com.powerring.hole.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
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
import com.powerring.hole.ring.ColorRange
import com.powerring.hole.ring.CustomColors
import com.powerring.hole.ring.MiuixPalette
import com.powerring.hole.ring.RingConfig
import com.powerring.hole.ring.StateColors
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
import top.yukonga.miuix.kmp.interfaces.ExperimentalScrollBarApi
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.RangeSliderPreference
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
    // 当前正在编辑的颜色（标签 + 初值 + 确认回调）；null 表示弹窗关闭
    var colorEditing by remember { mutableStateOf<ColorEditing?>(null) }
    // 导入/导出弹窗种类；与 colorEditing 同理提升到根级，随 Tab 切换不丢
    var transferKind by remember { mutableStateOf<TransferKind?>(null) }

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
                    onEditColor = { colorEditing = it },
                    onTransfer = { transferKind = it },
                )
                2 -> AboutTabContent(ctx, contentPadding)
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
                        text = "提示：所有调节即时生效；息屏/AOD 期间自动隐藏圆环以防烧屏，" +
                            "状态栏自动收起（沉浸模式）时圆环同步收缩隐藏。" +
                            "横屏时圆环无法贴合挖孔，默认会自动恢复原生电池图标。" +
                            "环颜色有四种互斥模式：跟随系统、固定单色、按电池状态、按电量区间；" +
                            "未设置的状态色会自动跟随原生图标。" +
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

// ====================================================================
// 颜色：模式表、状态色行表、色块、编辑入口
// ====================================================================

private val COLOR_MODE_ITEMS = listOf(
    "跟随系统电池图标",
    "固定单色",
    "按电池状态",
    "按电量区间",
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
): () -> Unit = { onEditColor(ColorEditing(label, argb, onResult)) }

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
