package com.powerring.hole.ui

import com.powerring.hole.ring.ColorRange
import com.powerring.hole.ring.RingConfig
import com.powerring.hole.ring.StateColors
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigJsonTest {

    private val sample = RingConfig(
        strokeWidthDp = 3.5f,
        holeDiameterDp = 22.5f,
        offsetXDp = -4.5f,
        offsetYDp = 6.2f,
        colorMode = RingConfig.MODE_LEVEL_RANGE,
        customColor = 0x11AA22,
        stateColors = StateColors(
            normal = 0,
            low = 0xFFFF4444.toInt(),
            powerSave = 0,
            performance = 0xFFCC8800.toInt(),
            charging = 0,
        ),
        levelRanges = listOf(
            ColorRange(1, 20, 0xFFFFD700.toInt()),
            ColorRange(80, 100, 0xFF00C853.toInt()),
        ),
    )

    @Test
    fun `encode writes schema app and all appearance fields`() {
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(
            ConfigJson.encode(sample),
        ).jsonObject

        assertEquals(1, obj["schema"]!!.jsonPrimitive.content.toInt())
        assertEquals("hole_power_ring", obj["app"]!!.jsonPrimitive.content)
        assertEquals("3.5", obj["stroke_width_dp"]!!.jsonPrimitive.content)
        assertEquals("22.5", obj["hole_diameter_dp"]!!.jsonPrimitive.content)
        assertEquals("-4.5", obj["offset_x_dp"]!!.jsonPrimitive.content)
        assertEquals("6.2", obj["offset_y_dp"]!!.jsonPrimitive.content)
        // 「环缩放」已移除：导出里不再出现 ring_scale
        assertFalse(obj.containsKey("ring_scale"))
        assertEquals("3", obj["color_mode"]!!.jsonPrimitive.content)
        assertEquals("0011AA22", obj["custom_color"]!!.jsonPrimitive.content)

        val states = obj["state_colors"]!!.jsonObject
        assertEquals("00000000", states["normal"]!!.jsonPrimitive.content)
        assertEquals("FFFF4444", states["low"]!!.jsonPrimitive.content)
        assertEquals("FFCC8800", states["performance"]!!.jsonPrimitive.content)

        val ranges = obj["level_ranges"]!!.jsonArray
        assertEquals(2, ranges.size)
        assertEquals("1", ranges[0].jsonObject["start"]!!.jsonPrimitive.content)
        assertEquals("FFFFD700", ranges[0].jsonObject["color"]!!.jsonPrimitive.content)
    }

    @Test
    fun `round trip encode then applyPatch equals source`() {
        val out = ConfigJson.applyPatch(ConfigJson.encode(sample), RingConfig.DEFAULT)
        assertEquals(sample, out)
    }

    @Test
    fun `missing fields keep base values`() {
        val json = """{"schema":1,"app":"hole_power_ring","color_mode":1,"custom_color":"FF277AF7"}"""
        val base = RingConfig(strokeWidthDp = 6.0f)
        val out = ConfigJson.applyPatch(json, base)
        assertEquals(1, out.colorMode)
        assertEquals(0xFF277AF7.toInt(), out.customColor)
        assertEquals(6.0f, out.strokeWidthDp, 0.0001f)
    }

    @Test
    fun `unknown fields are ignored`() {
        val json = """{"schema":1,"app":"hole_power_ring","future_key":"whatever","color_mode":2}"""
        assertEquals(2, ConfigJson.applyPatch(json, RingConfig.DEFAULT).colorMode)
    }

    @Test
    fun `legacy ring_scale field is ignored on import`() {
        // 老版本导出的外观 JSON 可能带 ring_scale；「环缩放」已移除，导入时应跳过
        val json = """{"schema":1,"app":"hole_power_ring","ring_scale":1.3,"stroke_width_dp":4}"""
        val out = ConfigJson.applyPatch(json, RingConfig.DEFAULT)
        assertEquals(RingConfig.DEFAULT.scale, out.scale, 0.0001f)
        assertEquals(4f, out.strokeWidthDp, 0.0001f)
    }

    @Test
    fun `blank not-json bad-schema and bad-app all fail`() {
        assertFalse(ConfigJson.runCatchingPatch("", RingConfig.DEFAULT).isSuccess)
        assertFalse(ConfigJson.runCatchingPatch("hello", RingConfig.DEFAULT).isSuccess)
        assertFalse(ConfigJson.runCatchingPatch("""{"schema":2,"app":"hole_power_ring"}""", RingConfig.DEFAULT).isSuccess)
        assertFalse(ConfigJson.runCatchingPatch("""{"schema":1,"app":"other"}""", RingConfig.DEFAULT).isSuccess)
        assertFalse(ConfigJson.runCatchingPatch("""[1,2]""", RingConfig.DEFAULT).isSuccess)
    }

    @Test
    fun `out of range numbers fail`() {
        assertFalse(ConfigJson.runCatchingPatch(num("stroke_width_dp", 12), RingConfig.DEFAULT).isSuccess)
        assertFalse(ConfigJson.runCatchingPatch(num("hole_diameter_dp", 50), RingConfig.DEFAULT).isSuccess)
        assertFalse(ConfigJson.runCatchingPatch(num("offset_x_dp", -25), RingConfig.DEFAULT).isSuccess)
        assertFalse(ConfigJson.runCatchingPatch(num("offset_x_dp", -25.4), RingConfig.DEFAULT).isSuccess)
        assertFalse(ConfigJson.runCatchingPatch(num("color_mode", 4), RingConfig.DEFAULT).isSuccess)
        assertFalse(ConfigJson.runCatchingPatch(num("color_mode", -1), RingConfig.DEFAULT).isSuccess)
    }

    @Test
    fun `bad colors fail and zero color fails for non-state fields`() {
        assertFalse(ConfigJson.runCatchingPatch(str("custom_color", "zzz"), RingConfig.DEFAULT).isSuccess)
        assertFalse(ConfigJson.runCatchingPatch(str("custom_color", "12345"), RingConfig.DEFAULT).isSuccess)
        assertFalse(ConfigJson.runCatchingPatch(str("custom_color", "00000000"), RingConfig.DEFAULT).isSuccess)
        // 状态色允许 00000000（语义 = 未设置，回退系统色）
        val out = ConfigJson.applyPatch(
            """{"schema":1,"app":"hole_power_ring","state_colors":{"low":"00000000"}}""",
            RingConfig(stateColors = StateColors(low = 0xFFFF4444.toInt())),
        )
        assertEquals(0, out.stateColors.low)
        // 但区间色不允许 0
        assertFalse(
            ConfigJson.runCatchingPatch(
                """{"schema":1,"app":"hole_power_ring","level_ranges":[{"start":1,"end":2,"color":"00000000"}]}""",
                RingConfig.DEFAULT,
            ).isSuccess,
        )
    }

    @Test
    fun `level ranges validation`() {
        val good = """"level_ranges":[{"start":1,"end":20,"color":"FFFFD700"}]"""
        assertTrue(ConfigJson.runCatchingPatch("""{"schema":1,"app":"hole_power_ring",$good}""", RingConfig.DEFAULT).isSuccess)
        // start > end / 越界 / 缺 color / 超过 8 段，全部拒绝
        assertFalse(ConfigJson.runCatchingPatch("""{"schema":1,"app":"hole_power_ring","level_ranges":[{"start":30,"end":20,"color":"FFFFD700"}]}""", RingConfig.DEFAULT).isSuccess)
        assertFalse(ConfigJson.runCatchingPatch("""{"schema":1,"app":"hole_power_ring","level_ranges":[{"start":-1,"end":20,"color":"FFFFD700"}]}""", RingConfig.DEFAULT).isSuccess)
        assertFalse(ConfigJson.runCatchingPatch("""{"schema":1,"app":"hole_power_ring","level_ranges":[{"start":1,"end":20}]}""", RingConfig.DEFAULT).isSuccess)
        val nine = (1..9).joinToString(",") { """{"start":$it,"end":$it,"color":"FF0000FF"}""" }
        assertFalse(ConfigJson.runCatchingPatch("""{"schema":1,"app":"hole_power_ring","level_ranges":[$nine]}""", RingConfig.DEFAULT).isSuccess)
    }

    // —— 测试辅助 ——

    private fun num(key: String, value: Number) =
        """{"schema":1,"app":"hole_power_ring","$key":$value}"""

    private fun str(key: String, value: String) =
        """{"schema":1,"app":"hole_power_ring","$key":"$value"}"""
}
