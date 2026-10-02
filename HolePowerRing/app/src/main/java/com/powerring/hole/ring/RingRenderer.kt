package com.powerring.hole.ring

import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import com.powerring.hole.core.ModuleLog

/**
 * 挖孔环形电量绘制器（方案 A：直接挂在系统挖孔装饰 View 的 onDraw 之后）。
 *
 * 几何规范对齐 miuix CircularProgressIndicator：
 * - 圆形进度，起点 -90°（12 点方向），圆角线帽（StrokeCap.ROUND）
 *
 * 配色策略（2026-10-02 改为四模式互斥，见 RingConfig.MODE_*）：
 * 1. MODE_FIXED_COLOR → 固定用 [RingConfig.customColor]
 * 2. MODE_BATTERY_STATE → 五路用户色（[RingConfig.stateColors]）；
 *    未设置（0）的那一路逐槽回退到系统电池图标色 / 内置语义色
 * 3. MODE_LEVEL_RANGE → 按电量区间表取第一条命中的色，未命中回退跟随系统
 * 4. MODE_FOLLOW_SYSTEM（默认）→ 五种状态全部跟随原生图标颜色
 *    （普通 / 低电 / 省电 / 性能 / 充电），底槽取进度色的低透明度版本
 * 5. 上述任一路径都取不到颜色（Hook 未装上 / 机型类名不同）→ 回退 miuix 语义色：
 *    底槽用 sliderBackground 令牌色、充电 primary 蓝、低电 error 红、
 *    省电琥珀与性能橙（应用自定义语义色）
 */
object RingRenderer {

    /** 挖孔边缘到底槽之间的间隙（dp） */
    private const val GAP_DP = 1.2f

    /** 低电量阈值（百分比） */
    private const val LOW_BATTERY_THRESHOLD = 15

    /**
     * 跟随系统电池图标颜色时，底槽取进度色该比例的透明度。
     * 与自定义色分支的 0x26（约 15%）保持同一量级，视觉上与原生图标一致。
     */
    private const val TRACK_ALPHA = 0x33

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    @Volatile
    private var clipRiskLogged = false

    @Volatile
    private var diagLogged = false

    /** 配色变化日志：与 RingState 里的日志同样必须节流，跟随系统时每帧都在变。 */
    @Volatile
    private var lastColorLogMs = 0L

    @Volatile
    private var lastLoggedColor = 0

    fun draw(view: View, canvas: Canvas, config: RingConfig, state: RingState) {
        val density = view.resources.displayMetrics.density
        val stroke = config.strokeWidthDp * density
        val gap = GAP_DP * density

        val hole = CutoutGeometry.resolve(view, gap + stroke / 2f) ?: return
        state.markCutoutResolved()

        if (hole.clipRisk && !clipRiskLogged) {
            clipRiskLogged = true
            ModuleLog.i("环有越出窗口边界的部分（用户可通过缩放/偏移修正）")
        }

        val darkIcons = MiuixPalette.isDarkIconMode(state.tintColor)

        // 沉浸收缩：expand=1 完整显示，expand→0 半径向内收敛、透明度同步淡出
        val expand = 1f - state.collapseProgress.coerceIn(0f, 1f)
        if (expand <= 0.01f) return

        // 四种模式互斥；分支只决定「进度色从哪来」，几何与动画一律不受影响。
        val palette = state.batteryPalette
        val fromUserOrSystem: Boolean
        val modeColor: Int = when (config.colorMode) {
            RingConfig.MODE_FIXED_COLOR -> {
                fromUserOrSystem = true
                config.customColor
            }

            RingConfig.MODE_BATTERY_STATE -> {
                fromUserOrSystem = true
                batteryStateColor(config, palette, state, darkIcons)
            }

            RingConfig.MODE_LEVEL_RANGE -> {
                fromUserOrSystem = true
                levelRangeColor(config, palette, state, darkIcons)
            }

            else -> {
                val followSystem = palette.ready
                fromUserOrSystem = followSystem
                // 顺序与系统 MiuiBatteryMeterIconView.getProgressStatus() 一致：
                // 充电（含快充）> 性能模式 > 省电模式 > 低电量 > 普通
                when {
                    followSystem && palette.chargingNow -> palette.charging
                    followSystem && palette.performanceNow -> palette.performance
                    followSystem && palette.powerSaveNow -> palette.powerSave
                    followSystem && palette.lowNow -> palette.low
                    followSystem -> state.normalColor
                    state.level <= LOW_BATTERY_THRESHOLD -> MiuixPalette.errorColor(darkIcons)
                    state.charging -> MiuixPalette.primaryColor(darkIcons)
                    state.powerSave -> MiuixPalette.POWER_SAVE_AMBER
                    else -> MiuixPalette.foregroundColor(darkIcons)
                }
            }
        }
        // 灵动岛保护：岛是黑色药丸，环色跟到近黑就不可见，改白（保留 alpha）。
        // 与 RingState 的岛色冻结是两层、各管各的：跟随系统的普通态由冻结机制
        // 定格成白后流到这里已是白色、守卫 no-op；三种自定义配色模式与取色回退
        // 链不经过 normalColor，只靠这里的守卫兜底。别删任何一层。
        val rawProgress = IslandColorGuard.ensureVisible(
            modeColor,
            islandShowing = state.islandShowing,
            collapseOnIsland = config.collapseOnIsland,
        )
        // 来自用户配置或系统调色板时，底槽取进度色的低透明度版本，视觉更统一；
        // 只有退回内置令牌时才用 sliderBackground 槽色。
        val rawTrack = if (fromUserOrSystem) {
            (TRACK_ALPHA shl 24) or (rawProgress and 0x00FFFFFF)
        } else {
            MiuixPalette.trackColor(darkIcons)
        }
        val progressColor = scaleAlpha(rawProgress, expand)
        val trackColor = scaleAlpha(rawTrack, expand)

        val colorNow = android.os.SystemClock.uptimeMillis()
        if (progressColor != lastLoggedColor && colorNow - lastColorLogMs >= 500L) {
            lastLoggedColor = progressColor
            lastColorLogMs = colorNow
            ModuleLog.i(
                "环配色: mode=${config.colorMode} color=#${Integer.toHexString(progressColor)} " +
                    "level=${state.level} ranges=${config.levelRanges.size}",
            )
        }

        // 用户手动微调：缩放（半径）+ 上下左右偏移
        val baseRadius = (hole.holeRadius + gap + stroke / 2f) * config.scale
        val cx = hole.cx + config.offsetXDp * density
        val cy = hole.cy + config.offsetYDp * density
        val radius = baseRadius * expand
        val fraction = (state.level / 100f).coerceIn(0f, 1f)

        if (!diagLogged) {
            diagLogged = true
            ModuleLog.i(
                "环绘制参数: fraction=$fraction level=${state.level} charging=${state.charging} " +
                    "color=#${Integer.toHexString(progressColor)} cx=$cx cy=$cy radius=$radius " +
                    "stroke=$stroke scale=${config.scale} off=(${config.offsetXDp},${config.offsetYDp}), collapse=${state.collapseProgress}",
            )
        }

        canvas.save()
        try {
            // 1) 底槽整圆
            trackPaint.strokeWidth = stroke
            trackPaint.color = trackColor
            canvas.drawCircle(cx, cy, radius, trackPaint)

            if (fraction <= 0f) return

            // 2) 电量进度弧
            progressPaint.strokeWidth = stroke
            progressPaint.color = progressColor
            if (fraction >= 0.999f) {
                // 满电直接画整圆，避免 ROUND 线帽在 360° 接缝处产生凸起
                canvas.drawCircle(cx, cy, radius, progressPaint)
            } else {
                val sweep = 360f * fraction
                // drawArc 参数为 left, top, right, bottom（边界坐标，不是宽高）
                canvas.drawArc(
                    cx - radius, cy - radius, cx + radius, cy + radius,
                    -90f, sweep, false, progressPaint,
                )
            }
        } finally {
            canvas.restore()
        }
    }

    /**
     * 「按电池状态」：状态判定优先级与系统 `getProgressStatus()` 一致
     * （充电 > 性能 > 省电 > 低电 > 普通）。系统调色板可用时借用它的状态位，
     * 否则退回本模块从广播侧能拿到的 charging / powerSave / level。
     * 用户未设置（0）的槽位逐槽回退：系统色 → 内置令牌色。
     */
    private fun batteryStateColor(
        config: RingConfig,
        palette: BatteryPalette,
        state: RingState,
        darkIcons: Boolean,
    ): Int {
        val useSystem = palette.ready
        val charging = if (useSystem) palette.chargingNow else state.charging
        val performance = useSystem && palette.performanceNow
        val powerSave = if (useSystem) palette.powerSaveNow else state.powerSave
        val low = if (useSystem) palette.lowNow else state.level <= LOW_BATTERY_THRESHOLD
        val s = config.stateColors
        return when {
            charging -> s.charging.orElse(
                if (useSystem) palette.charging else MiuixPalette.primaryColor(darkIcons),
            )

            performance -> s.performance.orElse(
                if (useSystem) palette.performance else MiuixPalette.PERFORMANCE_ORANGE,
            )

            powerSave -> s.powerSave.orElse(
                if (useSystem) palette.powerSave else MiuixPalette.POWER_SAVE_AMBER,
            )

            low -> s.low.orElse(
                if (useSystem) palette.low else MiuixPalette.errorColor(darkIcons),
            )

            else -> s.normal.orElse(
                if (useSystem) state.normalColor else MiuixPalette.foregroundColor(darkIcons),
            )
        }
    }

    /**
     * 「按电量区间」：按列表顺序取第一条命中 `state.level` 的区间色。
     * 过边界时颜色就该明确跳变，不做插值——否则颜色会与弧度不同步。
     * 未命中任何区间时退回「跟随系统」那条链，保证环在深浅背景下都可见。
     */
    private fun levelRangeColor(
        config: RingConfig,
        palette: BatteryPalette,
        state: RingState,
        darkIcons: Boolean,
    ): Int {
        val hit = CustomColors.colorForLevel(config.levelRanges, state.level)
        if (hit != 0) return hit
        return when {
            palette.ready -> state.normalColor
            state.level <= LOW_BATTERY_THRESHOLD -> MiuixPalette.errorColor(darkIcons)
            state.charging -> MiuixPalette.primaryColor(darkIcons)
            state.powerSave -> MiuixPalette.POWER_SAVE_AMBER
            else -> MiuixPalette.foregroundColor(darkIcons)
        }
    }

    /**
     * 0（未设置）或全透明（alpha=00）都取回退色。
     * 全透明按未设置处理：色盘初值、十六进制输入都可能带出 alpha=0 的颜色，
     * 若照单画出来环会整个消失，比「没变色」更让人困惑。
     */
    private fun Int.orElse(fallback: Int): Int =
        if (this == 0 || this ushr 24 == 0) fallback else this

    /** 按比例缩放颜色透明度，用于沉浸收缩的淡出 */
    private fun scaleAlpha(color: Int, factor: Float): Int {
        val a = (android.graphics.Color.alpha(color) * factor).toInt().coerceIn(0, 255)
        return (a shl 24) or (color and 0x00FFFFFF)
    }
}
