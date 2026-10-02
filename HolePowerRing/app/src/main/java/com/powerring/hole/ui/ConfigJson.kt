package com.powerring.hole.ui

import com.powerring.hole.ring.ColorRange
import com.powerring.hole.ring.CustomColors
import com.powerring.hole.ring.RingConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

/**
 * 外观页设置项的 JSON 导出 / 导入编解码（纯数据，JVM 可测）。
 *
 * 导入语义：缺失字段保持当前值、未知字段忽略；任何存在但非法的字段
 * 让整次导入失败（[applyPatch] 抛 [IllegalArgumentException]，消息可直接
 * 给用户看），绝不部分应用。
 *
 * 字段表与 prefs key 的对应关系见计划文档
 * `docs/superpowers/plans/2026-10-02-appearance-config-json-transfer.md`。
 */
object ConfigJson {

    /** 本版本 JSON 契约版本；结构变化时递增，导入侧按它拒绝不兼容文件。 */
    const val SCHEMA = 1
    const val APP = "hole_power_ring"

    private val prettyJson = Json { prettyPrint = true }

    fun encode(config: RingConfig): String = prettyJson.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("schema", SCHEMA)
            put("app", APP)
            put("stroke_width_dp", snapped(config.strokeWidthDp, 10f))
            put("ring_scale", snapped(config.scale, 100f))
            put("offset_x_dp", config.offsetXDp.roundToInt())
            put("offset_y_dp", config.offsetYDp.roundToInt())
            put("color_mode", config.colorMode)
            put("custom_color", CustomColors.hex8(config.customColor))
            put(
                "state_colors",
                buildJsonObject {
                    put("normal", CustomColors.hex8(config.stateColors.normal))
                    put("low", CustomColors.hex8(config.stateColors.low))
                    put("power_save", CustomColors.hex8(config.stateColors.powerSave))
                    put("performance", CustomColors.hex8(config.stateColors.performance))
                    put("charging", CustomColors.hex8(config.stateColors.charging))
                },
            )
            put(
                "level_ranges",
                buildJsonArray {
                    config.levelRanges.take(CustomColors.MAX_RANGES).forEach { range ->
                        add(
                            buildJsonObject {
                                put("start", range.start)
                                put("end", range.end)
                                put("color", CustomColors.hex8(range.color))
                            },
                        )
                    }
                },
            )
        },
    )

    /**
     * 按精度吸附后转 Double。
     *
     * 先乘 precision 取整、再**除以 Double**：Float 0.7f 直接 toDouble 会印出
     * 0.699999988…，而 (7/10.0) 是精确的 Double。
     */
    private fun snapped(value: Float, precision: Float): Double =
        (value * precision).roundToInt() / precision.toDouble()

    /**
     * 解析 [text] 并把其中出现的字段打补丁到 [base]。
     *
     * @throws IllegalArgumentException 消息为用户可读原因
     */
    fun applyPatch(text: String, base: RingConfig): RingConfig {
        require(text.isNotBlank()) { "导入内容为空" }
        val obj = try {
            Json.parseToJsonElement(text).jsonObject
        } catch (t: IllegalStateException) {
            throw IllegalArgumentException("不是合法的 JSON：解析失败")
        }
        val schema = obj["schema"]?.jsonPrimitive?.intOrNull
        require(schema == SCHEMA) {
            "不支持的配置版本（schema=${schema ?: "缺失"}，当前仅支持 $SCHEMA）"
        }
        require(obj["app"]?.jsonPrimitive?.contentOrNull == APP) {
            "这不是挖孔电量环的配置文件"
        }

        var next = base
        obj["stroke_width_dp"]?.let {
            next = next.copy(strokeWidthDp = it.asBoundedFloat("环粗细", RingConfig.STROKE_MIN..RingConfig.STROKE_MAX, 10f))
        }
        obj["ring_scale"]?.let {
            next = next.copy(scale = it.asBoundedFloat("环缩放", RingConfig.SCALE_MIN..RingConfig.SCALE_MAX, 100f))
        }
        obj["offset_x_dp"]?.let {
            next = next.copy(offsetXDp = it.asBoundedFloat("水平偏移", RingConfig.OFFSET_MIN..RingConfig.OFFSET_MAX, 1f))
        }
        obj["offset_y_dp"]?.let {
            next = next.copy(offsetYDp = it.asBoundedFloat("垂直偏移", RingConfig.OFFSET_MIN..RingConfig.OFFSET_MAX, 1f))
        }
        obj["color_mode"]?.let {
            val mode = it.asInt("配色模式")
            require(mode in RingConfig.MODE_FOLLOW_SYSTEM..RingConfig.MODE_LEVEL_RANGE) {
                "配色模式只能是 0–3 的整数（实际 $mode）"
            }
            next = next.copy(colorMode = mode)
        }
        obj["custom_color"]?.let {
            next = next.copy(customColor = it.asColor("固定单色", allowZero = false))
        }
        obj["state_colors"]?.let { element ->
            val o = element as? JsonObject
                ?: throw IllegalArgumentException("state_colors 需要是对象")
            next = next.copy(
                stateColors = next.stateColors.copy(
                    normal = o["normal"]?.asColor("状态色 normal", allowZero = true) ?: next.stateColors.normal,
                    low = o["low"]?.asColor("状态色 low", allowZero = true) ?: next.stateColors.low,
                    powerSave = o["power_save"]?.asColor("状态色 power_save", allowZero = true) ?: next.stateColors.powerSave,
                    performance = o["performance"]?.asColor("状态色 performance", allowZero = true) ?: next.stateColors.performance,
                    charging = o["charging"]?.asColor("状态色 charging", allowZero = true) ?: next.stateColors.charging,
                ),
            )
        }
        obj["level_ranges"]?.let { element ->
            val arr = element as? JsonArray
                ?: throw IllegalArgumentException("level_ranges 需要是数组")
            require(arr.size <= CustomColors.MAX_RANGES) {
                "区间最多 ${CustomColors.MAX_RANGES} 段（实际 ${arr.size}）"
            }
            next = next.copy(levelRanges = arr.mapIndexed { i, item ->
                val o = item as? JsonObject
                    ?: throw IllegalArgumentException("第 ${i + 1} 段区间需要是对象")
                val start = (o["start"] ?: throw IllegalArgumentException("第 ${i + 1} 段缺少 start"))
                    .asInt("第 ${i + 1} 段起点")
                val end = (o["end"] ?: throw IllegalArgumentException("第 ${i + 1} 段缺少 end"))
                    .asInt("第 ${i + 1} 段终点")
                require(start in 0..100 && end in 0..100 && start <= end) {
                    "第 ${i + 1} 段区间 $start–$end 不合法（需 0–100 且起点≤终点）"
                }
                val color = (o["color"] ?: throw IllegalArgumentException("第 ${i + 1} 段缺少 color"))
                    .asColor("第 ${i + 1} 段颜色", allowZero = false)
                ColorRange(start, end, color)
            })
        }
        return next
    }

    /** UI 侧直接用：把异常转成 Result，避免弹窗里写 try/catch。 */
    fun runCatchingPatch(text: String, base: RingConfig): Result<RingConfig> =
        runCatching { applyPatch(text, base) }

    private fun JsonElement.asBoundedFloat(
        label: String,
        range: ClosedFloatingPointRange<Float>,
        precision: Float,
    ): Float {
        val raw = (this as? JsonPrimitive)?.doubleOrNull
            ?: throw IllegalArgumentException("$label 需要是数字")
        val snapped = (raw.toFloat() * precision).roundToInt() / precision
        require(snapped in range) {
            // ClosedFloatingPointRange 只有 endInclusive，没有 end
            "$label 需在 ${range.start}–${range.endInclusive} 范围内（实际 $raw）"
        }
        return snapped
    }

    private fun JsonElement.asInt(label: String): Int =
        (this as? JsonPrimitive)?.intOrNull
            ?: throw IllegalArgumentException("$label 需要是整数")

    private fun JsonElement.asColor(label: String, allowZero: Boolean): Int {
        val text = (this as? JsonPrimitive)?.contentOrNull
            ?: throw IllegalArgumentException("$label 需要是颜色字符串")
        val argb = HexColor.parse(text)
            ?: throw IllegalArgumentException("$label「$text」不是合法十六进制色值（3/6/8 位）")
        require(allowZero || argb != 0) { "$label 不能为全透明 00000000" }
        return argb
    }
}
