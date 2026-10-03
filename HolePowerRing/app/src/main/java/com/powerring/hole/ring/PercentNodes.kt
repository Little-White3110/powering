package com.powerring.hole.ring

/**
 * 「电量节点」编解码（纯数据，JVM 可测）。
 *
 * 节点是一组 1–100 的百分比；电量跨过某个节点时，环下方短暂显示一次当前电量
 * 数字（复用「点击显示电量」的那条渲染通路）。适合 20 / 80 / 100 这类关键刻度。
 *
 * 编码成一条逗号分隔字符串存进 SharedPreferences，与 [CustomColors] 的区间表
 * 同一个模式：解析函数内部对坏数据逐段降级，绝不抛异常。
 */
object PercentNodes {

    /** 最多几个节点（避免配置页与摘要过长） */
    const val MAX_NODES = 8

    /** 出厂默认节点：低电提醒 20、保养区间 80、充满 100 */
    val DEFAULT = listOf(20, 80, 100)

    /**
     * 宽松解析：[raw] 为 null（键不存在）时回退 [DEFAULT]；
     * 空串（用户清空）返回空表 = 关闭节点显示；非法段逐段丢弃。
     */
    fun decode(raw: String?): List<Int> {
        if (raw == null) return DEFAULT
        if (raw.isBlank()) return emptyList()
        return raw.split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 1..100 }
            .distinct()
            .sorted()
            .take(MAX_NODES)
    }

    /**
     * 严格解析（配置页输入校验用）：任一非空段非法就返回 null。
     * 空串视为合法的「清空」。
     */
    fun decodeStrict(raw: String): List<Int>? {
        val text = raw.trim()
        if (text.isEmpty()) return emptyList()
        val parts = text.split(',', '，').map { it.trim() }
        val result = ArrayList<Int>(parts.size)
        for (p in parts) {
            if (p.isEmpty()) return null
            val v = p.toIntOrNull() ?: return null
            if (v !in 1..100) return null
            if (v !in result) result.add(v)
        }
        if (result.size > MAX_NODES) return null
        return result.sorted()
    }

    /** 编码为存储用字符串。 */
    fun encode(nodes: List<Int>): String =
        nodes.asSequence()
            .filter { it in 1..100 }
            .distinct()
            .sorted()
            .take(MAX_NODES)
            .joinToString(",")

    /** 供 UI 摘要展示，如「20 / 80 / 100%」。 */
    fun describe(nodes: List<Int>): String =
        if (nodes.isEmpty()) "未设置" else nodes.joinToString(" / ") + "%"
}
