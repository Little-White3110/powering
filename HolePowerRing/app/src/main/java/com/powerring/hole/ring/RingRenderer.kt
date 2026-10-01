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
 * 配色策略（2026-10-01 改造）：
 * 1. 开启自定义色 → 固定用 [RingConfig.customColor]
 * 2. 未开启且读到了系统电池图标 → 五种状态全部跟随原生图标颜色
 *    （普通 / 低电 / 省电 / 性能 / 充电），底槽取进度色的低透明度版本
 * 3. 读不到（Hook 未装上 / 机型类名不同）→ 回退 miuix 语义色：
 *    底槽用 sliderBackground 令牌色、充电 primary 蓝、低电 error 红、
 *    省电琥珀（应用自定义语义色）
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

        // 颜色优先级：自定义色（覆盖一切）> 系统电池图标调色板 > 内置语义色回退。
        // followSystem 为假时行为与改造前逐像素一致（Hook 失效 / 开启自定义色的兜底）。
        val palette = state.batteryPalette
        val followSystem = !config.useCustomColor && palette.ready
        val rawProgress = when {
            config.useCustomColor -> config.customColor
            // 顺序与系统 MiuiBatteryMeterIconView.getProgressStatus() 的状态集一致：
            // 充电（含快充）> 性能模式 > 省电模式 > 低电量 > 普通
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
        // 自定义色 / 跟随系统色时，底槽取进度色的低透明度版本，视觉更统一
        val rawTrack = when {
            config.useCustomColor -> (0x26 shl 24) or (rawProgress and 0x00FFFFFF)
            followSystem -> (TRACK_ALPHA shl 24) or (rawProgress and 0x00FFFFFF)
            else -> MiuixPalette.trackColor(darkIcons)
        }
        val progressColor = scaleAlpha(rawProgress, expand)
        val trackColor = scaleAlpha(rawTrack, expand)

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

    /** 按比例缩放颜色透明度，用于沉浸收缩的淡出 */
    private fun scaleAlpha(color: Int, factor: Float): Int {
        val a = (android.graphics.Color.alpha(color) * factor).toInt().coerceIn(0, 255)
        return (a shl 24) or (color and 0x00FFFFFF)
    }
}
