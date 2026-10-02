# 外观配置 JSON 导入导出 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为外观页的全部设置项（几何微调 + 颜色四模式）提供 JSON 格式的导出（复制到剪贴板）与导入（粘贴他人 JSON 一次性覆盖）能力。

**Architecture:** 新增纯数据层 `ui/ConfigJson.kt`（JSON ↔ `RingConfig` 外观字段编解码，可 JVM 单测）；`PrefsStore` 增加一次落盘+一次广播的批量写入；UI 侧新增 `ui/ConfigTransferDialog.kt`（导出/导入两个 `OverlayDialog`），弹窗状态与色盘弹窗同样提升到 `SettingsScreen` 根级（HorizontalPager 会回收离屏页）。

**Tech Stack:** Kotlin、kotlinx-serialization-json（手动 `JsonObject` 树 API，**不需要编译器插件**）、miuix 0.9.4-rc01、JUnit4 JVM 单测。

---

## 范围与契约（实现前必读）

**覆盖的设置项**（即外观页 `AppearanceTabContent` 的全部状态，共 12 个 prefs key）：

| JSON 字段 | prefs key（`RingConfig.KEY_*`） | 类型 / 约束 |
|---|---|---|
| `stroke_width_dp` | KEY_STROKE_WIDTH | float，1–8，0.1 步进 |
| `ring_scale` | KEY_SCALE | float，0.7–1.5，0.01 步进 |
| `offset_x_dp` / `offset_y_dp` | KEY_OFFSET_X / KEY_OFFSET_Y | int，−20–20 |
| `color_mode` | KEY_COLOR_MODE | int，0–3 |
| `custom_color` | KEY_CUSTOM_COLOR | 8 位大写十六进制字符串（AARRGGBB，不带 `#`） |
| `state_colors.normal/low/power_save/performance/charging` | KEY_STATE_COLOR_* | 同上；`"00000000"` 合法，语义为「未设置、回退系统色」 |
| `level_ranges` | KEY_LEVEL_RANGES | 数组 `[{start,end,color}]`，0≤start≤end≤100，color 不得为 0，最多 8 段 |

顶层固定带 `"schema": 1` 与 `"app": "hole_power_ring"` 作为识别与版本字段。**不含**「开关」页的五个布尔开关，也不含遗留键 `use_custom_color`。

**导入语义（本计划锁定）**：
- JSON 中**缺失**的字段保持当前值不变；**未知**字段忽略（向前兼容他人用新版本导出的文件）。
- 任何**存在但非法**的字段 ⇒ 整个导入失败并给出人类可读原因，一条都不写入（不做部分应用）。
- 解析仅用 kotlinx-serialization 的 JSON 树 API + `HexColor.parse`，对任意恶意粘贴文本无副作用（不反射、不执行）。

**既有代码证据（无需猜测 API）**：
- `OverlayDialog(show, title, onDismissRequest) { … }`、`TextField(value, onValueChange, label, singleLine, …)`、`Button(onClick, enabled, modifier) { Text(...) }`、`ArrowPreference(title, summary, enabled, onClick)` 的调用形态全部见 `ui/ColorPickerDialog.kt:59-127` 与 `ui/SettingsScreen.kt`，均为 miuix 0.9.4-rc01 下已编译通过的用法。
- 弹窗必须渲染在 `SettingsScreen` 的 `Scaffold` 内、`HorizontalPager` 外（`SettingsScreen.kt:133-134` 的注释解释了原因：OverlayDialog 依赖 Scaffold popup 层，Pager 回收离屏页）。
- `HexColor.parse` 支持 3/6/8 位、可带 `#`、非法返回 null（`ui/HexColor.kt:17-30`）。
- `CustomColors.hex8` 产出 8 位大写十六进制（`ring/CustomColors.kt:72-74`）。

**执行约定（用户偏好）**：全程**不执行任何 `git commit`**，完成后由用户自行提交。每步的验证命令在 Git Bash 下于 `HolePowerRing/` 目录执行（`./gradlew`）。

---

### Task 1: 引入 kotlinx-serialization-json 依赖

**Files:**
- Modify: `HolePowerRing/app/build.gradle.kts`（dependencies 块，约 49-65 行）

- [ ] **Step 1: 添加依赖**

在 `dependencies { … }` 中 `testImplementation("junit:junit:4.13.2")` 之前插入：

```kotlin
    // 外观配置 JSON 导入导出（ui/ConfigJson）。只用手动 JsonObject 树 API，
    // 不依赖 @Serializable 代码生成，因此无需 kotlin 序列化编译器插件。
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
```

注意：AGP 9 内置 Kotlin 支持，**不要**为本功能添加 `org.jetbrains.kotlin.plugin.serialization`——本计划只用 `buildJsonObject`/`Json.parseToJsonElement` 这类运行时树 API，插件仅 `@Serializable` 代码生成才需要。

- [ ] **Step 2: 验证构建不受影响**

Run: `cd HolePowerRing && ./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`（若因 Kotlin 版本对 `1.8.1` 报兼容性错误，降级为 `1.7.3` 后重跑；此为本计划唯一的依赖版本兜底，其余步骤不受影响）

---

### Task 2: ConfigJson 编码（encode）

**Files:**
- Create: `HolePowerRing/app/src/test/java/com/powerring/hole/ui/ConfigJsonTest.kt`
- Create: `HolePowerRing/app/src/main/java/com/powerring/hole/ui/ConfigJson.kt`

- [ ] **Step 1: 写失败测试（encode 输出结构）**

创建 `ConfigJsonTest.kt`：

```kotlin
package com.powerring.hole.ui

import com.powerring.hole.ring.ColorRange
import com.powerring.hole.ring.RingConfig
import com.powerring.hole.ring.StateColors
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class ConfigJsonTest {

    private val sample = RingConfig(
        strokeWidthDp = 3.5f,
        scale = 1.23f,
        offsetXDp = -4f,
        offsetYDp = 6f,
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
        assertEquals("1.23", obj["ring_scale"]!!.jsonPrimitive.content)
        assertEquals("-4", obj["offset_x_dp"]!!.jsonPrimitive.content)
        assertEquals("6", obj["offset_y_dp"]!!.jsonPrimitive.content)
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
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd HolePowerRing && ./gradlew :app:testDebugUnitTest --tests "com.powerring.hole.ui.ConfigJsonTest"`
Expected: 编译失败，`Unresolved reference: ConfigJson`

- [ ] **Step 3: 实现 encode**

创建 `ui/ConfigJson.kt`（本任务只含 `encode` 与常量，`applyPatch` 在 Task 3 补上）：

```kotlin
package com.powerring.hole.ui

import com.powerring.hole.ring.ColorRange
import com.powerring.hole.ring.CustomColors
import com.powerring.hole.ring.RingConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
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
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd HolePowerRing && ./gradlew :app:testDebugUnitTest --tests "com.powerring.hole.ui.ConfigJsonTest"`
Expected: `BUILD SUCCESSFUL`，1 个测试 PASS

---

### Task 3: ConfigJson 解码与校验（applyPatch）

**Files:**
- Modify: `HolePowerRing/app/src/test/java/com/powerring/hole/ui/ConfigJsonTest.kt`
- Modify: `HolePowerRing/app/src/main/java/com/powerring/hole/ui/ConfigJson.kt`

- [ ] **Step 1: 写失败测试**

在 `ConfigJsonTest` 类内追加以下测试方法（`sample` 复用 Task 2 的字段；新增 import：`org.junit.Assert.assertTrue`、`org.junit.Assert.assertFalse`、`kotlinx.serialization.json.Json` 已有）：

```kotlin
    @Test
    fun `round trip encode then applyPatch equals source`() {
        val out = ConfigJson.applyPatch(ConfigJson.encode(sample), RingConfig.DEFAULT)
        assertEquals(sample, out)
    }

    @Test
    fun `missing fields keep base values`() {
        val json = """{"schema":1,"app":"hole_power_ring","color_mode":1,"custom_color":"FF277AF7"}"""
        val base = RingConfig(strokeWidthDp = 6.0f, scale = 1.4f)
        val out = ConfigJson.applyPatch(json, base)
        assertEquals(1, out.colorMode)
        assertEquals(0xFF277AF7.toInt(), out.customColor)
        assertEquals(6.0f, out.strokeWidthDp, 0.0001f)
        assertEquals(1.4f, out.scale, 0.0001f)
    }

    @Test
    fun `unknown fields are ignored`() {
        val json = """{"schema":1,"app":"hole_power_ring","future_key":"whatever","color_mode":2}"""
        assertEquals(2, ConfigJson.applyPatch(json, RingConfig.DEFAULT).colorMode)
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
        assertFalse(ConfigJson.runCatchingPatch(num("ring_scale", 0.5), RingConfig.DEFAULT).isSuccess)
        assertFalse(ConfigJson.runCatchingPatch(num("offset_x_dp", -25), RingConfig.DEFAULT).isSuccess)
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
```

在测试类末尾追加辅助方法（`num`/`str` 被上面多个测试引用）：

```kotlin
    // —— 测试辅助 ——

    private fun num(key: String, value: Number) =
        """{"schema":1,"app":"hole_power_ring","$key":$value}"""

    private fun str(key: String, value: String) =
        """{"schema":1,"app":"hole_power_ring","$key":"$value"}"""
```

`ConfigJsonTest` 需要 import：`StateColors`（Task 2 已列）、`org.junit.Assert.assertTrue`、`org.junit.Assert.assertFalse`。`runCatchingPatch` 是 Step 3 实现里提供的 Result 包装（见下）。

- [ ] **Step 2: 跑测试确认失败**

Run: `cd HolePowerRing && ./gradlew :app:testDebugUnitTest --tests "com.powerring.hole.ui.ConfigJsonTest"`
Expected: 编译失败，`Unresolved reference: applyPatch`

- [ ] **Step 3: 实现 applyPatch**

在 `ConfigJson.kt` 中补充（新增 import：`kotlinx.serialization.json.JsonArray`、`JsonElement`、`JsonPrimitive`、`contentOrNull`、`doubleOrNull`、`intOrNull`、`jsonObject`、`jsonPrimitive`；已有 `ColorRange` import）：

```kotlin
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
            "$label 需在 ${range.start}–${range.end} 范围内（实际 $raw）"
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
```

说明：`Json.parseToJsonElement("hello")` 对裸词抛 `SerializationException`（`IllegalStateException` 子类）；`[1,2]` 能解析成数组但 `.jsonObject` 抛 `IllegalArgumentException`，消息会被 `require` 前的 catch 漏过——因此对 `.jsonObject` 的失败路径**不需要**特殊处理：`IllegalArgumentException` 本身就是 `applyPatch` 的契约异常，UI 直接展示其 message。

- [ ] **Step 4: 跑测试确认全部通过**

Run: `cd HolePowerRing && ./gradlew :app:testDebugUnitTest --tests "com.powerring.hole.ui.ConfigJsonTest"`
Expected: 全部 PASS（含既有 `CustomColorsTest`/`HexColorTest` 可在下一步一起回归）

Run: `cd HolePowerRing && ./gradlew test`
Expected: `BUILD SUCCESSFUL`

---

### Task 4: PrefsStore 批量落盘

**Files:**
- Modify: `HolePowerRing/app/src/main/java/com/powerring/hole/ui/PrefsStore.kt`（`setString` 之后，约 72 行处）

- [ ] **Step 1: 实现 applyAppearance**

此方法依赖 Android `Context`，无 JVM 单测（与既有 setBoolean 等一致，靠编译 + 真机验证）。在 `setString` 函数后新增：

```kotlin
    /**
     * 导入外观配置用：把外观页全部设置项一次写入并提交，
     * 只触发一次权限放开与一次 CONFIG_CHANGED 广播。
     */
    fun applyAppearance(context: Context, config: RingConfig) {
        prefs(context).edit()
            .putFloat(RingConfig.KEY_STROKE_WIDTH, config.strokeWidthDp)
            .putFloat(RingConfig.KEY_SCALE, config.scale)
            .putFloat(RingConfig.KEY_OFFSET_X, config.offsetXDp)
            .putFloat(RingConfig.KEY_OFFSET_Y, config.offsetYDp)
            .putInt(RingConfig.KEY_COLOR_MODE, config.colorMode)
            .putInt(RingConfig.KEY_CUSTOM_COLOR, config.customColor)
            .putInt(RingConfig.KEY_STATE_COLOR_NORMAL, config.stateColors.normal)
            .putInt(RingConfig.KEY_STATE_COLOR_LOW, config.stateColors.low)
            .putInt(RingConfig.KEY_STATE_COLOR_POWER_SAVE, config.stateColors.powerSave)
            .putInt(RingConfig.KEY_STATE_COLOR_PERFORMANCE, config.stateColors.performance)
            .putInt(RingConfig.KEY_STATE_COLOR_CHARGING, config.stateColors.charging)
            .putString(RingConfig.KEY_LEVEL_RANGES, CustomColors.encodeRanges(config.levelRanges))
            .commit()
        commit(context)
    }
```

不写 `KEY_USE_CUSTOM_COLOR`：该键是遗留推导用（见 `resolveColorMode` 注释），`color_mode` 显式写入后它不再参与取色。

- [ ] **Step 2: 编译验证**

Run: `cd HolePowerRing && ./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`

---

### Task 5: 导出/导入弹窗 UI

**Files:**
- Create: `HolePowerRing/app/src/main/java/com/powerring/hole/ui/ConfigTransferDialog.kt`

- [ ] **Step 1: 实现两个 OverlayDialog**

组件用法完全沿用 `ColorPickerDialog.kt` 的形态（`OverlayDialog` + `TextField` + 底部 `Row(Button)`）；滚动容器与剪贴板是标准 Compose/Android API：

```kotlin
package com.powerring.hole.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.powerring.hole.ring.RingConfig
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 外观配置导入/导出弹窗的种类。 */
enum class TransferKind { Export, Import }

/**
 * 外观配置 JSON 传输弹窗。与色盘弹窗同一模式：`kind == null` 即两窗皆关，
 * 状态由 SettingsScreen 根级持有。
 */
@Composable
fun ConfigTransferDialog(
    kind: TransferKind?,
    config: RingConfig,
    onDismiss: () -> Unit,
    onApply: (RingConfig) -> Unit,
) {
    ExportConfigDialog(show = kind == TransferKind.Export, config = config, onDismiss = onDismiss)
    ImportConfigDialog(
        show = kind == TransferKind.Import,
        config = config,
        onDismiss = onDismiss,
        onApply = onApply,
    )
}

@Composable
private fun ExportConfigDialog(show: Boolean, config: RingConfig, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    // 每次打开重新生成：打开期间 config 不可能被其他入口改动（写入只发生在本页）
    var text by remember(show) { mutableStateOf(ConfigJson.encode(config)) }

    OverlayDialog(show = show, title = "导出外观配置", onDismissRequest = onDismiss) {
        Text(
            text = "把下面的 JSON 复制发给他人；对方在「导入外观配置」粘贴即可覆盖外观设置。不含「开关」页的功能开关。",
            fontSize = MiuixTheme.textStyles.body2.fontSize,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 300.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            TextField(value = text, onValueChange = { text = it }, label = "配置 JSON")
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(modifier = Modifier.weight(1f))
            Button(onClick = onDismiss) {
                Text(text = "关闭")
            }
            Spacer(modifier = Modifier.width(12.dp))
            Button(
                onClick = {
                    copyToClipboard(ctx, "HolePowerRing 外观配置", text)
                    Toast.makeText(ctx, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                    onDismiss()
                },
                enabled = text.isNotBlank(),
            ) {
                Text(text = "复制")
            }
        }
    }
}

@Composable
private fun ImportConfigDialog(
    show: Boolean,
    config: RingConfig,
    onDismiss: () -> Unit,
    onApply: (RingConfig) -> Unit,
) {
    val ctx = LocalContext.current
    var input by remember(show) { mutableStateOf("") }
    val patch = remember(input, config) {
        if (input.isBlank()) null else ConfigJson.runCatchingPatch(input, config)
    }

    OverlayDialog(show = show, title = "导入外观配置", onDismissRequest = onDismiss) {
        Text(
            text = "粘贴他人导出的 JSON。缺失的项保持你当前的值；有任何一项非法则整次导入失败、不做修改。",
            fontSize = MiuixTheme.textStyles.body2.fontSize,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 300.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            TextField(value = input, onValueChange = { input = it }, label = "配置 JSON")
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = when {
                input.isBlank() -> "等待粘贴…"
                patch?.isSuccess == true -> "配置有效，应用后将覆盖当前外观设置"
                else -> "✕ ${patch?.exceptionOrNull()?.message ?: "解析失败"}"
            },
            modifier = Modifier.fillMaxWidth(),
            fontSize = MiuixTheme.textStyles.body2.fontSize,
            color = when {
                input.isNotBlank() && patch?.isSuccess != true -> MiuixTheme.colorScheme.error
                else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
            },
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = {
                    readClipboard(ctx)?.let { input = it }
                        ?: Toast.makeText(ctx, "剪贴板为空", Toast.LENGTH_SHORT).show()
                },
            ) {
                Text(text = "从剪贴板粘贴")
            }
            Spacer(modifier = Modifier.weight(1f))
            Button(onClick = onDismiss) {
                Text(text = "取消")
            }
            Spacer(modifier = Modifier.width(12.dp))
            Button(
                onClick = { patch?.getOrNull()?.let(onApply) },
                enabled = patch?.isSuccess == true,
            ) {
                Text(text = "应用")
            }
        }
    }
}

private fun copyToClipboard(ctx: Context, label: String, text: String) {
    runCatching {
        ctx.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText(label, text))
    }
}

private fun readClipboard(ctx: Context): String? = runCatching {
    ctx.getSystemService(ClipboardManager::class.java)
        ?.primaryClip?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)?.text?.toString()?.takeIf { it.isNotBlank() }
}.getOrNull()
```

注意：`patch` 在输入非法时也要保留错误消息——`remember(input, config)` 里对非法输入返回的是 `Result.failure`（`runCatchingPatch` 已捕获），上面状态文案分支因此能取到 `exceptionOrNull()?.message`。若 miuix `TextField` 多行形态编译报错（本文件是唯一未在项目内实测过的参数组合——默认多行），降级方案：给两处 `TextField` 补 `singleLine = false` 显式实参；再不行检查是否需要 `keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)`（`ColorPickerDialog.kt:73-82` 已示范 `KeyboardOptions` 用法）。

- [ ] **Step 2: 编译验证**

Run: `cd HolePowerRing && ./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`

---

### Task 6: 接线到外观页与 SettingsScreen 根级

**Files:**
- Modify: `HolePowerRing/app/src/main/java/com/powerring/hole/ui/SettingsScreen.kt`

- [ ] **Step 1: 根级持有弹窗状态并渲染在 Pager 外**

`SettingsScreen` 内，`var colorEditing …`（88 行附近）之后新增：

```kotlin
    // 导入/导出弹窗种类；与 colorEditing 同理提升到根级，随 Tab 切换不丢
    var transferKind by remember { mutableStateOf<TransferKind?>(null) }
```

`1 -> AppearanceTabContent(...)` 调用处（122-128 行）追加一个实参：

```kotlin
                1 -> AppearanceTabContent(
                    ctx = ctx,
                    config = config,
                    update = { config = it },
                    contentPadding = contentPadding,
                    onEditColor = { colorEditing = it },
                    onTransfer = { transferKind = it },
                )
```

在 `ColorPickerDialog(...)` 之后（134 行附近）新增：

```kotlin
        // 外观配置导入/导出弹窗
        ConfigTransferDialog(
            kind = transferKind,
            config = config,
            onDismiss = { transferKind = null },
            onApply = { patched ->
                PrefsStore.applyAppearance(ctx, patched)
                config = patched
                transferKind = null
                Toast.makeText(ctx, "外观配置已导入", Toast.LENGTH_SHORT).show()
            },
        )
```

- [ ] **Step 2: AppearanceTabContent 增加入口行**

签名（260-266 行）加参数：

```kotlin
    onTransfer: (TransferKind) -> Unit,
```

在 `item(key = "colorCard") { … }` 整块之后、`item(key = "bottomSpacer")` 之前插入：

```kotlin
            item(key = "transferTitle") {
                SmallTitle("配置导入导出")
            }
            item(key = "transferCard") {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    ArrowPreference(
                        title = "导出外观配置",
                        summary = "生成 JSON 并复制到剪贴板，可发送给他人",
                        onClick = { onTransfer(TransferKind.Export) },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ArrowPreference(
                        title = "导入外观配置",
                        summary = "粘贴他人导出的 JSON，校验通过后一次性覆盖外观设置",
                        onClick = { onTransfer(TransferKind.Import) },
                    )
                }
            }
```

- [ ] **Step 3: 全量回归**

Run: `cd HolePowerRing && ./gradlew test assembleDebug`
Expected: `BUILD SUCCESSFUL`，全部单测 PASS

---

### Task 7: 真机验收（需用户配合，不自动执行设备操作）

- [ ] **Step 1: 安装与冒烟**

`./gradlew installDebug`（需用户确认设备连接），打开设置页 → 外观 Tab 底部出现「配置导入导出」两张行。

- [ ] **Step 2: 导出**

点「导出外观配置」→ 弹窗内 JSON 完整可读 → 点「复制」→ Toast「已复制到剪贴板」→ 在任意输入框验证粘贴出的 JSON 内容正确。

- [ ] **Step 3: 导入往返**

修改若干外观项（滑杆 + 颜色模式 + 加一段区间）→ 导出复制 → 把某项改回默认 → 导入（从剪贴板粘贴 → 应用）→ 页面各项立即回到导出时的值；环即时重绘（无需重启 SystemUI，验证 CONFIG_CHANGED 广播路径）。

- [ ] **Step 4: 非法输入**

导入框输入 `hello` → 红字原因 + 「应用」置灰；输入合法 JSON 但 `stroke_width_dp: 12` → 红字越界原因；确认此期间 prefs 未被改动。

- [ ] **Step 5: 兼容性**

手工构造缺字段的 JSON（只留 `schema`/`app`/`color_mode`）→ 导入成功且其他项保持当前值。

- [ ] **Step 6: 交付**

汇报验证结果；**不执行 git commit**，由用户自行提交。

---

## Self-Review 记录

1. **需求覆盖**：外观页全部 12 个设置项 ✓（Task 2 字段表）；导出给他人口 ✓（剪贴板）；外部粘贴导入口 ✓（导入弹窗 + 从剪贴板粘贴）。
2. **占位符扫描**：无 TBD/TODO；唯一允许的兜底分支已在 Task 1 Step 2（依赖版本降级）与 Task 5 Step 1 末尾（TextField 多行参数）显式写出具体做法，不是空描述。
3. **类型一致性**：`ConfigJson.encode/applyPatch/runCatchingPatch` 三处签名在 Task 2/3/5 一致；`TransferKind`、`ConfigTransferDialog(kind, config, onDismiss, onApply)`、`PrefsStore.applyAppearance(context, config)` 与 Task 6 调用点一致；测试辅助 `num/str` 在 Task 3 定义并使用。
