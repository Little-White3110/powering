package com.powerring.hole.ui

/**
 * 色盘旁的十六进制输入框用的解析 / 格式化。
 *
 * 刻意不依赖 `android.graphics.Color`，这样它是纯函数、能在 JVM 单测里跑
 * （`HexColorTest`）。色值在跨进程配置里始终以 ARGB Int 传递，本工具只做
 * 「字符串 ↔ Int」这一件事。
 */
object HexColor {

    /**
     * 解析用户输入：可省略 `#`、大小写混用，支持
     * 3 位（RGB，逐位扩展成 RRGGBB）、6 位（RRGGBB，补不透明 alpha）、
     * 8 位（AARRGGBB，alpha 由用户负责）。非法返回 null，绝不抛异常。
     */
    fun parse(text: String): Int? {
        val body = text.trim().removePrefix("#")
        val digits = when (body.length) {
            3 -> buildString { body.forEach { append(it).append(it) } }
            6, 8 -> body
            else -> return null
        }
        val value = digits.toLongOrNull(16) ?: return null
        return if (digits.length == 6) {
            (value or 0xFF000000L).toInt()
        } else {
            value.toInt()
        }
    }

    /** 显示用：不透明输出 `RRGGBB`，含透明度输出 `AARRGGBB`，均大写、不带 `#`。 */
    fun format(argb: Int): String {
        val alpha = (argb ushr 24) and 0xFF
        return if (alpha == 0xFF) {
            java.lang.Long.toHexString(argb.toLong() and 0xFFFFFFL).uppercase().padStart(6, '0')
        } else {
            java.lang.Long.toHexString(argb.toLong() and 0xFFFFFFFFL).uppercase().padStart(8, '0')
        }
    }

    /**
     * 输入过滤：只保留十六进制字符、以及**开头**的 `#`，统一大写，
     * 最长 9 个字符（`#` + 8 位）——必须容得下 `#AARRGGBB`，
     * 截到 8 会让带 `#` 的 8 位输入永远打不完。
     */
    fun normalizeInput(raw: String): String {
        val sb = StringBuilder(raw.length.coerceAtMost(9))
        for ((index, ch) in raw.withIndex()) {
            if (ch == '#' && index == 0) {
                sb.append('#')
                continue
            }
            if (ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F') sb.append(ch.uppercaseChar())
            if (sb.length == 9) break
        }
        return sb.toString()
    }
}
