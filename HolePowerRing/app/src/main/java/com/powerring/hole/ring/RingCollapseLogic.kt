package com.powerring.hole.ring

/**
 * 圆环收起决策（无 Android 依赖，便于 JVM 单测）。
 *
 * 三条通路取最强值：沉浸收起与灵动岛是布尔（要么 0 要么 1），面板通路形参是 fraction
 * 但**当前同样是布尔** —— 真机取证（2026-10-03）表明本机可用信号
 * `onShadeOrQsExpanded(Boolean)` 只产生 0f/1f，连续进度点位在本机不派发。
 * fraction 形参与本函数的 clamp/阈值逻辑保留，供控制中心侧确认到连续信号后复用。
 */
internal object RingCollapseLogic {

    /** 小于该值的进度变化不值得重绘（拖拽期间每帧回调） */
    const val EPSILON = 0.02f

    fun target(
        statusBarCollapsed: Boolean,
        collapseOnImmersive: Boolean,
        islandShowing: Boolean,
        collapseOnIsland: Boolean,
        shadeFraction: Float,
        collapseOnShade: Boolean,
    ): Float {
        val byImmersive = if (statusBarCollapsed && collapseOnImmersive) 1f else 0f
        val byIsland = if (islandShowing && collapseOnIsland) 1f else 0f
        val byShade = if (collapseOnShade) shadeFraction.coerceIn(0f, 1f) else 0f
        return maxOf(byImmersive, byIsland, byShade)
    }

    fun shouldEmit(current: Float, incoming: Float): Boolean =
        kotlin.math.abs(current - incoming) >= EPSILON
}
