package com.powerring.hole.ring

/**
 * 灵动岛期间的环色保护。
 *
 * 灵动岛本体是黑色药丸；「有岛时隐藏圆环」关闭时环保持展开，
 * 而跟随系统取色在浅色背景下就是黑色——黑岛上套黑环完全看不清电量。
 * 本守卫只在这一场景把 R/G/B 三通道均 ≤ [DARK_CHANNEL_MAX] 的近黑色
 * 替换为白色（保留原 alpha），其余颜色（用户自定义色、充电蓝、低电红等）不动。
 *
 * 纯 Int 运算，零 Android 依赖，可直接 JVM 单测。
 */
object IslandColorGuard {

    /** 不透明纯白，替换后的 RGB 端 */
    const val WHITE = 0xFFFFFFFF.toInt()

    /** 三通道均不超过该值才算「在黑岛上看不清」的近黑色（含反色动画深色中间值） */
    const val DARK_CHANNEL_MAX = 64

    /**
     * @param color           渲染侧本轮算出的进度色（ARGB）
     * @param islandShowing   灵动岛当前是否显示（宿主侧信号，见 IslandVisibilityHook）
     * @param collapseOnIsland 「有岛时隐藏圆环」开关；开启时环本就收起，守卫不介入
     */
    fun ensureVisible(color: Int, islandShowing: Boolean, collapseOnIsland: Boolean): Int {
        if (!islandShowing || collapseOnIsland) return color
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        if (r > DARK_CHANNEL_MAX || g > DARK_CHANNEL_MAX || b > DARK_CHANNEL_MAX) return color
        return (color and 0xFF000000.toInt()) or (WHITE and 0x00FFFFFF)
    }
}
