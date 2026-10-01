package com.powerring.hole.ring

/**
 * 「按电池状态」模式的五枚用户色。
 *
 * 约定：**0 = 该槽未设置**，由 [RingRenderer] 逐槽退回系统电池图标调色板
 * （[BatteryPalette]），系统调色板也没读到时退回 [MiuixPalette] 的内置令牌色。
 * 这样用户只改其中一两态时其余状态仍跟随原生图标，不会出现
 * 「改了一个颜色、其余四态全变黑」的意外。
 */
data class StateColors(
    val normal: Int = 0,
    val low: Int = 0,
    val powerSave: Int = 0,
    val performance: Int = 0,
    val charging: Int = 0,
) {
    companion object {
        val DEFAULT = StateColors()
    }
}

/** 一段电量区间（闭区间、整数百分比 0..100）及其颜色。 */
data class ColorRange(
    val start: Int,
    val end: Int,
    val color: Int,
)

/**
 * 自定义配色的数据契约：区间表编解码 + 命中判定。
 *
 * [decodeRanges] 会在 SystemUI 进程内被调用，因此对任何畸形片段**直接丢弃、
 * 绝不抛异常**（AGENTS.md 铁律 1）。整张表用一条字符串经 ContentProvider 跨进程，
 * 格式：`1-20:FFFFD700,21-80:FF277AF7`（ARGB 大写 8 位十六进制、不带 `#`）。
 */
object CustomColors {

    /** 区间条数上限；编码与解码两侧都会截断，避免配置串无界增长。 */
    const val MAX_RANGES = 8

    fun encodeRanges(ranges: List<ColorRange>): String =
        ranges.take(MAX_RANGES).joinToString(",") { "${it.start}-${it.end}:${hex8(it.color)}" }

    fun decodeRanges(raw: String?): List<ColorRange> {
        if (raw.isNullOrEmpty()) return emptyList()
        val out = ArrayList<ColorRange>(MAX_RANGES)
        for (segment in raw.split(',')) {
            try {
                val dash = segment.indexOf('-')
                val colon = segment.indexOf(':')
                if (dash <= 0 || colon <= dash) continue
                val start = segment.substring(0, dash).trim().toInt()
                val end = segment.substring(dash + 1, colon).trim().toInt()
                val color = segment.substring(colon + 1).trim().toLong(16).toInt()
                if (start > end || start < 0 || end > 100 || color == 0) continue
                out.add(ColorRange(start, end, color))
                if (out.size == MAX_RANGES) break
            } catch (t: Throwable) {
                // 单段坏数据只丢这一段，其余区间照常生效
                continue
            }
        }
        return out
    }

    /** 列表顺序里第一条命中 [level] 的区间色；未命中返回 0。 */
    fun colorForLevel(ranges: List<ColorRange>, level: Int): Int =
        ranges.firstOrNull { level in it.start..it.end }?.color ?: 0

    /** 8 位大写十六进制 ARGB（编码用，不带 `#`）。 */
    fun hex8(argb: Int): String =
        java.lang.Long.toHexString(argb.toLong() and 0xFFFFFFFFL).uppercase().padStart(8, '0')
}
