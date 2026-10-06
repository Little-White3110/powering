package com.powerring.hole.ring

/**
 * 挖孔几何纯计算层：不依赖任何 Android 类，JVM 可测。
 *
 * 把「从系统给的原始矩形推出环的圆心与半径」拆成纯函数，[CutoutGeometry]
 * 只负责取数（insets / WindowManager 度量 / 反射），这里负责算——启发式的
 * 每一步都能用单测钉死，改判据不再需要等真机验证。
 */

/**
 * 挖孔安全区矩形（屏幕坐标，Int 版）。
 *
 * 与 android.graphics.Rect 解耦：本层不允许出现 Android 依赖，
 * 调用方从各来源把 Rect map 成它。
 */
data class RectI(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    /** 空矩形（退化成点或负尺寸）不能当挖孔候选。 */
    val isEmpty: Boolean get() = width <= 0 || height <= 0
}

/** 解析成功的挖孔核心几何（View 局部坐标，px）。 */
data class HoleCore(val cx: Float, val cy: Float, val holeRadius: Float)

/**
 * 换算到 View 局部坐标的安全区矩形。
 *
 * [fellBack] = true 表示「屏幕坐标差值」换算失败，退回了缩放后的原始矩形：
 * 部分机型状态栏窗口与屏幕原点重合，getLocationOnScreen 的差值反而把矩形推飞。
 */
data class LocalRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val fellBack: Boolean,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    /** 与视图 (0,0)-(viewW,viewH) 有交集即可，不要求完整落在视图内。 */
    fun intersects(viewW: Float, viewH: Float): Boolean =
        left < viewW && right > 0f && top < viewH && bottom > 0f
}

object CutoutMath {

    /**
     * 取当前方向上真正承载摄像头的那个挖孔矩形（顶部/侧置通用）。
     *
     * 启发式：到屏幕四边的最小距离越小越可能是当前挖孔（挖孔总贴着某条边）；
     * 同距时取面积更大者——多挖孔机型里主摄一侧的安全区通常更大。
     * 空表返回 null。
     */
    fun pickCutoutRect(rects: List<RectI>, screenW: Int, screenH: Int): RectI? {
        val candidates = rects.filter { !it.isEmpty }
        if (candidates.isEmpty()) return null
        return candidates.minWithOrNull(
            compareBy<RectI>(
                { rect ->
                    minOf(
                        rect.top, rect.left,
                        screenW - rect.right, screenH - rect.bottom,
                    ).coerceAtLeast(0)
                },
                // 面积取负参与最小值比较（= 面积大者优先）；Long 相乘避免极端尺寸溢出翻转
                { rect -> -(rect.width.toLong() * rect.height) },
            ),
        )
    }

    /**
     * 把屏幕坐标的安全区矩形换算到 View 局部坐标。
     *
     * 两步：先按 [scale] 缩放（cutout 矩形是物理像素，视图坐标空间可能被缩放），
     * 再减去 View 在屏幕上的位置。**只要差值换算结果与视图有交集就用它**；
     * 差值把矩形推飞时退回缩放后的原始矩形（[LocalRect.fellBack] = true）。
     *
     * 判交用「有交集即可」而不是「圆心必须在视图内」：竖长安全区
     * （如本机 72×144px）中心 cy=72px，状态栏视图更矮时圆心判会把
     * 今天能用的几何误判失败，宁可保守。
     */
    fun toViewLocal(
        rect: RectI,
        locX: Int,
        locY: Int,
        scale: Float,
        viewW: Float,
        viewH: Float,
    ): LocalRect? {
        val scaled = LocalRect(
            left = rect.left * scale,
            top = rect.top * scale,
            right = rect.right * scale,
            bottom = rect.bottom * scale,
            fellBack = false,
        )
        val local = LocalRect(
            left = scaled.left - locX,
            top = scaled.top - locY,
            right = scaled.right - locX,
            bottom = scaled.bottom - locY,
            fellBack = false,
        )
        if (local.width > 0f && local.height > 0f && local.intersects(viewW, viewH)) {
            return local
        }
        if (scaled.width > 0f && scaled.height > 0f && scaled.intersects(viewW, viewH)) {
            return scaled.copy(fellBack = true)
        }
        return null
    }

    /**
     * 从局部矩形推出挖孔核心几何。
     *
     * 保留既有行为：圆心取矩形中心，半径取短边一半——竖长安全区
     * （灵动岛小岛胶囊在其内垂直居中）下环与胶囊对齐。
     */
    fun holeFromLocal(local: LocalRect): HoleCore = HoleCore(
        cx = local.centerX,
        cy = local.centerY,
        holeRadius = minOf(local.width, local.height) / 2f,
    )

    /**
     * 手动模式（自动定位拿不到 insets 的机型）：直接由用户指定的孔径算几何。
     *
     * 经验值：孔在顶部、圆心略低于半径位置（cy = r × 1.15），水平居中；
     * 上下左右微调由渲染端现成的 offset 配置叠加，这里不掺和。
     * diameterPx <= 0 时返回零几何兜底（正常调用方不会传，防 NaN 把画布弄花）。
     */
    fun manualHole(viewW: Float, diameterPx: Float): HoleCore {
        if (diameterPx <= 0f) return HoleCore(viewW / 2f, 0f, 0f)
        val r = diameterPx / 2f
        return HoleCore(cx = viewW / 2f, cy = r * 1.15f, holeRadius = r)
    }

    /**
     * 环半径钳制：状态栏高度有限，大缩放/大偏移时环外缘可能整体出界被裁。
     *
     * [limit] 是「圆心到四边再让出描边」后环外缘能到达的最大半径；
     * [minR] 保底要包住挖孔（holeRadius 的 3/4）且至少容下描边本体。
     * 视图极小时 limit < minR，优先保证包住挖孔——钳制后环顶轻微贴孔
     * 是可接受退化（比被视图裁掉一段弧好）。
     */
    fun clampRingRadius(
        cx: Float,
        cy: Float,
        viewW: Float,
        viewH: Float,
        radius: Float,
        stroke: Float,
        holeRadius: Float,
    ): Float {
        val limit = minOf(cx, viewW - cx, cy, viewH - cy) - 0.5f - stroke / 2f
        val minR = maxOf(holeRadius * 0.75f, stroke / 2f + 1f)
        if (limit < minR) return minR
        return radius.coerceIn(minR, limit)
    }
}

/**
 * 挖孔定位重试额度。
 *
 * insets 晚到是常态：状态栏窗口 attach 时系统往往还没把 display cutout
 * 派发进来，需要按固定间隔重试，且必须有上限——拿不到就一直空转重绘。
 * 成功解析或窗口重新 attach 时 [reset] 归零，让下一轮失败从头计。
 */
class RetryBudget(private val maxAttempts: Int = MAX_ATTEMPTS) {
    private var attempts = 0

    /** 消耗一次重试额度；额度用尽返回 false。 */
    fun tryConsume(): Boolean {
        if (attempts >= maxAttempts) return false
        attempts++
        return true
    }

    /** 归还全部额度。 */
    fun reset() {
        attempts = 0
    }

    companion object {
        /** 上限 12 次：约 6 秒还拿不到 insets 就停，等窗口重建再来一轮。 */
        const val MAX_ATTEMPTS = 12

        /** 重试间隔（ms）：文档经验值，覆盖绝大多数机型的 insets 派发延迟。 */
        const val INTERVAL_MS = 500L
    }
}
