package com.powerring.hole.ring

import android.graphics.Color
import kotlin.math.pow

/**
 * miuix（HyperOS）设计令牌的颜色子集，取自参考工程
 * miuix-ui 的 Colors.kt（lightColorScheme / darkColorScheme），
 * 用于让环形电量与 miuix 组件视觉语言一致。
 *
 * 明暗模式不依据系统夜间模式，而是依据挖孔填充色（= 状态栏图标颜色）：
 * 黑色图标 -> 浅色令牌；白色图标 -> 深色令牌。
 */
object MiuixPalette {

    // primary（Switch / Button / Slider 的品牌蓝）
    const val PRIMARY_LIGHT = 0xFF3482FF.toInt()
    const val PRIMARY_DARK = 0xFF277AF7.toInt()

    // error（miuix 的错误/警告红，低电量使用）
    const val ERROR_LIGHT = 0xFFE94634.toInt()
    const val ERROR_DARK = 0xFFF12522.toInt()

    // sliderBackground（进度槽色：黑/白 6% 透明）
    const val TRACK_LIGHT = 0x0F000000
    const val TRACK_DARK = 0x26FFFFFF

    // onBackground（常规前景：状态栏图标跟随色）
    const val FG_LIGHT = 0xFF1A1A1A.toInt()
    const val FG_DARK = 0xF2FFFFFF.toInt()

    /**
     * 省电模式语义色：miuix 令牌中没有对应琥珀色，
     * 这里使用应用自定义语义色（与 error 红区分）。
     */
    const val POWER_SAVE_AMBER = 0xFFFFB340.toInt()

    /**
     * 性能模式语义色：miuix 令牌里同样没有对应色，取与省电琥珀可区分的橙色。
     * 仅在「按电池状态」模式下、且系统调色板不可用时作为回退。
     */
    const val PERFORMANCE_ORANGE = 0xFFFF7A1E.toInt()

    /** 判断挖孔填充色偏亮还是偏暗，返回 true 表示图标为深色（浅色 UI）。 */
    fun isDarkIconMode(tintColor: Int): Boolean = luminance(tintColor) < 0.5f

    fun trackColor(darkIcons: Boolean): Int =
        if (darkIcons) TRACK_LIGHT else TRACK_DARK

    fun primaryColor(darkIcons: Boolean): Int =
        if (darkIcons) PRIMARY_LIGHT else PRIMARY_DARK

    fun errorColor(darkIcons: Boolean): Int =
        if (darkIcons) ERROR_LIGHT else ERROR_DARK

    fun foregroundColor(darkIcons: Boolean): Int =
        if (darkIcons) FG_LIGHT else FG_DARK

    /** 相对亮度（近似 sRGB），用于判断颜色明暗。 */
    private fun luminance(color: Int): Float {
        val r = gamma(Color.red(color) / 255f)
        val g = gamma(Color.green(color) / 255f)
        val b = gamma(Color.blue(color) / 255f)
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }

    private fun gamma(c: Float): Float =
        if (c <= 0.03928f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
}
