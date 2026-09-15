package com.powerring.hole.ring

import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import com.powerring.hole.core.ModuleLog

/**
 * 挖孔环形电量绘制器（方案 A：直接挂在系统挖孔装饰 View 的 onDraw 之后）。
 *
 * 视觉规范对齐 miuix CircularProgressIndicator：
 * - 圆形进度，起点 -90°（12 点方向），圆角线帽（StrokeCap.ROUND）
 * - 底槽使用 miuix sliderBackground 令牌色
 * - 充电使用 primary 蓝，低电使用 error 红，省电使用琥珀色（应用自定义语义色）
 * - 充电时两层低透明度外扩弧模拟辉光（不使用 setShadowLayer，兼容性更好）
 */
object RingRenderer {

    /** 挖孔边缘到底槽之间的间隙（dp） */
    private const val GAP_DP = 1.2f

    /** 低电量阈值（百分比） */
    private const val LOW_BATTERY_THRESHOLD = 15

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val glowOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        alpha = 40
    }
    private val glowInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        alpha = 72
    }

    @Volatile
    private var clipRiskLogged = false

    fun draw(view: View, canvas: Canvas, config: RingConfig, state: RingState) {
        val density = view.resources.displayMetrics.density
        val stroke = config.strokeWidthDp * density
        val gap = GAP_DP * density

        // 辉光会再向外扩两圈，解析几何时把这部分预算进去，用于判断裁切风险
        val glowBudget = if (config.chargingGlow && state.charging) stroke * 2.2f else 0f
        val hole = CutoutGeometry.resolve(view, gap + stroke / 2f + glowBudget) ?: return
        state.markCutoutResolved()

        if (hole.clipRisk && !clipRiskLogged) {
            clipRiskLogged = true
            ModuleLog.e(
                "检测到环可能被挖孔装饰窗口裁切（R2 风险），" +
                    "需要在真机验证；若被裁切需启用扩窗方案", null,
            )
        }

        val darkIcons = MiuixPalette.isDarkIconMode(state.tintColor)

        // 颜色选择：低电红 > 充电蓝 > 省电琥珀 > 跟随状态栏图标色
        val progressColor = when {
            state.level <= LOW_BATTERY_THRESHOLD -> MiuixPalette.errorColor(darkIcons)
            state.charging -> MiuixPalette.primaryColor(darkIcons)
            state.powerSave -> MiuixPalette.POWER_SAVE_AMBER
            else -> MiuixPalette.foregroundColor(darkIcons)
        }

        val cx = hole.cx
        val cy = hole.cy
        val radius = hole.holeRadius + gap + stroke / 2f
        val fraction = (state.animatedLevel / 100f).coerceIn(0f, 1f)

        canvas.save()
        try {
            // 1) 底槽整圆
            trackPaint.strokeWidth = stroke
            trackPaint.color = MiuixPalette.trackColor(darkIcons)
            canvas.drawCircle(cx, cy, radius, trackPaint)

            if (fraction <= 0f) return

            // 2) 充电辉光（两圈外扩低透明弧）
            if (config.chargingGlow && state.charging) {
                drawGlowArc(canvas, cx, cy, radius, fraction, stroke, progressColor)
            }

            // 3) 电量进度弧
            progressPaint.strokeWidth = stroke
            progressPaint.color = progressColor
            if (fraction >= 0.999f) {
                // 满电直接画整圆，避免 ROUND 线帽在 360° 接缝处产生凸起
                canvas.drawCircle(cx, cy, radius, progressPaint)
            } else {
                val sweep = 360f * fraction
                canvas.drawArc(
                    cx - radius, cy - radius, radius * 2f, radius * 2f,
                    -90f, sweep, false, progressPaint,
                )
            }
        } finally {
            canvas.restore()
        }
    }

    private fun drawGlowArc(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        fraction: Float,
        stroke: Float,
        color: Int,
    ) {
        val sweep = 360f * fraction
        glowOuterPaint.color = color
        glowOuterPaint.strokeWidth = stroke * 2.4f
        glowInnerPaint.color = color
        glowInnerPaint.strokeWidth = stroke * 1.5f
        // 辉光半径依次外扩，宽度差即羽化范围
        canvas.drawArc(
            cx - radius - stroke, cy - radius - stroke,
            (radius + stroke) * 2f, (radius + stroke) * 2f,
            -90f, sweep, false, glowOuterPaint,
        )
        canvas.drawArc(
            cx - radius - stroke * 0.35f, cy - radius - stroke * 0.35f,
            (radius + stroke * 0.35f) * 2f, (radius + stroke * 0.35f) * 2f,
            -90f, sweep, false, glowInnerPaint,
        )
    }
}
