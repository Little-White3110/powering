# 环形电量自定义配色扩展 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **执行约束（务必遵守）**：
> - **不要 `git commit` / `git add`**，任何步骤都不提交（用户长期偏好）。
> - 只改本计划「Files」列出的文件；计划里的**行号会漂移，用代码块里的锚点文本重新定位**，不要信任行号。
> - 本仓库**没有仪器化 UI 测试**；验证 = `./gradlew assembleDebug` 编译 + Task 1/2 的 JVM 单测 + Task 9 的真机 logcat 判读。
> - Hook 侧（SystemUI 进程内）新增逻辑必须包 `try/catch (Throwable)` 并静默降级，绝不让异常逃逸（AGENTS.md 铁律 1）。本次不新增任何 Hook 点、不动反射类名，只消费既有 `BatteryPalette` / `RingState` 状态。
> - UI 只用 miuix（`top.yukonga.miuix.kmp.*`），**不得引入 Material3 组件**。
> - 不动 `apks/`、`local.properties`、`app/build/`。

**Goal:** 把「自定义颜色」从一个开关扩成四种互斥配色模式（跟随系统 / 固定单色 / 按电池状态 5 槽 / 按电量区间多段），并让色盘支持直接输入十六进制色值。

**Architecture:** 配色模式作为唯一分支入口落在 `RingConfig.colorMode`，`RingRenderer` 按模式解析进度色，其余渲染几何与动画一概不动。用户配置经既有 ContentProvider 通道跨进程：5 路状态色各用一个 Int key，区间表用一个 String key（`1-20:FFFFD700,...`）由 `CustomColors` 编解码——解码函数对畸形输入逐段丢弃，因为它在 SystemUI 进程内被调用。「未设置」统一用 `0` 表示，渲染端逐槽退回系统调色板再退回内置令牌色，保证只改一两个状态时其余状态仍跟随原生图标。设置页把内联色盘抽成共用的 `ColorPickerDialog`，三处（固定色 / 状态色 / 区间色）复用同一份「色盘 + 十六进制输入」逻辑。

**Tech Stack:** Kotlin、AGP 9 + JetBrains Compose 插件、miuix 0.9.4-rc01（`ColorPicker` / `OverlayDialog` / `TextField` / `OverlayDropdownPreference` / `RangeSliderPreference`）、JUnit4（仅新增 JVM 单测）、Xposed API 仅 `compileOnly`。

---

## 已确认的设计决策

| 项 | 决策 | 理由 |
|---|---|---|
| 四种方式的关系 | **互斥**：设置页一个 `OverlayDropdownPreference` 选模式，同一时刻只有一套颜色生效 | 用户明确选择「单一模式下拉」；分支集中在一处，`RingRenderer` 可预测 |
| 状态色清单 | 5 项：普通 / 低电 / 省电 / 性能 / 充电 | 与系统 `MiuiBatteryMeterIconView.getProgressStatus()` 判定优先级完全一致，不需要新增取数逻辑；「充满」不单列（现有链路把它并进充电） |
| 状态判定依据 | `BatteryPalette.ready` 时用系统状态位（`chargingNow` 等），否则退回本模块广播侧能拿到的 `charging` / `powerSave` / `level<=15` | 复用现有双通道（`BatteryColorHook` + `BatteryObserver`），Hook 失效时功能不塌 |
| 未设置的颜色 | 存 `0`，逐槽回退：系统调色板 → `MiuixPalette` 令牌色 | 零配置即可获得与现状一致的观感；避免「勾了一个色、其余四态变黑」 |
| 区间编辑 UI | miuix `RangeSliderPreference` 双端滑杆（每行一条区间）+ 行内色块 + 删除；上限 8 条 | 用户选择双端滑杆 |
| 区间存储 | 单个 String key `level_range_colors` = `1-20:FFFFD700,21-80:FF277AF7` | 变长列表无法用固定 Int key 表达；String 列经 `MatrixCursor` → `HookPrefs.readColumn` 已有 `getString` 分支，无需改 IPC 机制 |
| 区间命中 | 按列表顺序**第一条命中生效**，新增区间插到列表最前（优先级最高） | 不做区间求交/自动重排，复杂度与可预期性平衡 |
| 区间取色用 `level` 还是动画值 | 用 `state.level`（整数百分比） | 过边界时颜色应当明确跳变。**2026-10-02 更正**：commit `32e263f` 已移除 `animatedLevel`，`RingRenderer` 现在的弧度就是 `state.level / 100f`，因此本条只是把既有事实写成契约 |
| 区间未命中 | 回退到「跟随系统」那条链（系统调色板可用则用 `normalColor`，否则令牌色） | 保证深浅背景下环始终可见 |
| 底槽色规则 | 四种模式下统一 `0x33` 透明度取进度色的 RGB；仅「回退到内置令牌色」时用 `sliderBackground` 令牌 | 统一后代码分支更少。**这是一处有意的微小视觉变化**：原「固定单色」分支用 `0x26`，现统一为 `0x33`（约 15%→20% 不透明度） |
| 十六进制格式 | `RGB` / `RRGGBB` / `AARRGGBB`，可带 `#`、大小写不敏感；6 位自动补不透明 alpha | 用户选择 3/6/8 全支持，8 位可画半透明环 |
| 全透明（alpha=00） | 允许（8 位输入），但在提示文案里明说「设成 00 环会完全看不见」；解码只丢弃 ARGB 恰好为 `0` 的片段 | 不额外做防呆拦截，尊重显式输入 |
| 旧配置迁移 | 派生而非写盘：`color_mode` 缺失时按 `use_custom_color` 推导（true→固定单色，false→跟随系统） | 升级后**不打开设置页**也不会丢配色；无需一次性写 XML 的副作用 |
| 环色变化动画 | 状态/区间模式下颜色跳变**不加过渡动画** | 现有 `normalColor` 动画是为跟随系统的反色时钟服务的；跳变符合区间/状态语义，另起动画会引入与弧动画的时序耦合（YAGNI） |

## 现状要点（供 subagent 建立上下文，均为已读代码确认）

- **2026-10-02 现状校正**：commit `32e263f` 已移除「电量变化动画」与「充电高亮辉光」两个功能，`RingConfig` 里不再有 `levelAnim` / `chargingGlow` 字段，`RingRenderer` 的弧度直接取 `state.level / 100f`，`drawGlowArc` 与辉光 Paint 已删除。本计划所有代码与锚点均已按此现状核对；执行时若看到旧计划/旧文档提到辉光或 `animatedLevel`，以当前磁盘代码为准。
- 现在只有一个 `use_custom_color` 布尔 + 一个 `custom_color_argb`。`RingRenderer` 的分支是：自定义色 > 系统调色板 5 状态 > `MiuixPalette` 令牌回退（`ring/RingRenderer.kt` 内「颜色优先级」注释块起）。
- 系统 5 状态色已经被捕获：`BatteryPalette(normal/low/powerSave/performance/charging + *Now 状态位)`，由 `hook/BatteryColorHook.kt` 反射写入 `RingState.setSystemBatteryColors`。本次配色功能直接消费它，**不需要新 Hook**。
- 跨进程配置：设置页 `ui/PrefsStore`（写 + `commit()` 后放开文件权限 + 发 `CONFIG_CHANGED` 广播）→ `ui/ConfigProvider`（游标列，默认值取自 `RingConfig.DEFAULT`）→ `core/HookPrefs`（volatile 缓存，专用 HandlerThread 刷新，退避重试）。
- `HookPrefs.readColumn` 的 `else` 分支返回 `cursor.getString(index)`，所以 String 列可直接传区间表。
- 色盘当前内联在 `SettingsScreen` 根作用域（`showColorPicker` + `editingColor` + `OverlayDialog` + `ColorPicker`），只有「确定」才落盘。
- `ring/` 与 `ui/` 的纯数据类都不依赖 Android，可写 JVM 单测。

---

## 文件结构

| 文件 | 本次职责 |
|---|---|
| `HolePowerRing/app/build.gradle.kts` | 仅新增 `testImplementation("junit:junit:4.13.2")` |
| `ring/CustomColors.kt`（新建） | 配色数据契约：`StateColors`、`ColorRange`、区间表编解码与命中判定（纯 Kotlin，可单测） |
| `ui/HexColor.kt`（新建） | 十六进制解析 / 格式化 / 输入过滤（纯 Kotlin，可单测；刻意不用 `android.graphics.Color`） |
| `ring/RingConfig.kt` | 新增 `colorMode` / `stateColors` / `levelRanges` 字段、7 个 key、4 个 MODE 常量 |
| `core/HookPrefs.kt` | 从游标还原新字段（含 `toStr` 辅助） |
| `ui/ConfigProvider.kt` | 新增 7 个游标列；`color_mode` 列做旧配置派生 |
| `ui/PrefsStore.kt` | 新增 `setString`、`resolveColorMode`，`load()` 读新字段 |
| `ring/MiuixPalette.kt` | 新增性能模式回退色常量 `PERFORMANCE_ORANGE` |
| `ring/RingRenderer.kt` | 按 `colorMode` 解析进度色 + 底槽规则统一 + 配色变化日志（节流） |
| `ring/RingState.kt` | `signature()` 纳入新字段，保证改配置即时重绘 |
| `ui/ColorPickerDialog.kt`（新建） | 共用色盘弹窗：`ColorPicker` + 十六进制输入（双向同步） |
| `ui/SettingsScreen.kt` | 模式下拉、5 路状态色行、区间列表（双端滑杆 + 色块 + 增删） |
| `挖孔环形电量LSP模块-可行性分析报告.md` | 结论回写（**新增 §15**；§13 预留给取色链路、§14 预留给横屏恢复，勿占用） |

---

### Task 1: 配色数据契约 `CustomColors`（含 JVM 单测）

**Files:**
- Create: `HolePowerRing/app/src/main/java/com/powerring/hole/ring/CustomColors.kt`
- Create: `HolePowerRing/app/src/test/java/com/powerring/hole/ring/CustomColorsTest.kt`
- Modify: `HolePowerRing/app/build.gradle.kts`

- [ ] **Step 1: 加单测依赖**

锚点：`dependencies {` 块内，`compileOnly(project(":xposedstub"))` 之前插入：

```kotlin
    // 纯数据层（ring/CustomColors、ui/HexColor）的 JVM 单测；UI 与 Hook 侧仍靠真机验证
    testImplementation("junit:junit:4.13.2")
```

- [ ] **Step 2: 写失败的单测**

新建 `HolePowerRing/app/src/test/java/com/powerring/hole/ring/CustomColorsTest.kt`，完整内容：

```kotlin
package com.powerring.hole.ring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomColorsTest {

    @Test
    fun encodeRangesUsesDashColonHexForm() {
        val encoded = CustomColors.encodeRanges(
            listOf(ColorRange(1, 20, 0xFFFFD700.toInt()), ColorRange(21, 80, 0xFF277AF7.toInt())),
        )
        assertEquals("1-20:FFFFD700,21-80:FF277AF7", encoded)
    }

    @Test
    fun decodeRoundTripsEveryColor() {
        val ranges = listOf(
            ColorRange(0, 0, 0xFF000000.toInt()),
            ColorRange(1, 20, 0x80FFD700.toInt()),
            ColorRange(21, 100, 0xFF277AF7.toInt()),
        )
        assertEquals(ranges, CustomColors.decodeRanges(CustomColors.encodeRanges(ranges)))
    }

    @Test
    fun decodeNullAndEmptyGiveEmptyList() {
        assertTrue(CustomColors.decodeRanges(null).isEmpty())
        assertTrue(CustomColors.decodeRanges("").isEmpty())
    }

    @Test
    fun decodeDropsMalformedSegmentsButKeepsGoodOnes() {
        val raw = "1-20:FFFFD700,broken,,30-x:FF000000,21-40:ZZZZZZZZ,5-1:FF112233,51-101:FF112233"
        assertEquals(listOf(ColorRange(1, 20, 0xFFFFD700.toInt())), CustomColors.decodeRanges(raw))
    }

    @Test
    fun decodeStopsAtMaxRanges() {
        val raw = (1..20).joinToString(",") { "$it-$it:FF0000AA" }
        assertEquals(CustomColors.MAX_RANGES, CustomColors.decodeRanges(raw).size)
    }

    @Test
    fun colorForLevelReturnsFirstMatchInListOrder() {
        val ranges = listOf(ColorRange(1, 50, 0xFFFFD700.toInt()), ColorRange(10, 20, 0xFF277AF7.toInt()))
        assertEquals(0xFFFFD700.toInt(), CustomColors.colorForLevel(ranges, 15))
        assertEquals(0, CustomColors.colorForLevel(ranges, 0))
        assertEquals(0, CustomColors.colorForLevel(ranges, 51))
        assertEquals(0, CustomColors.colorForLevel(emptyList(), 50))
    }
}
```

- [ ] **Step 3: 跑测试确认失败**

```bash
cd "HolePowerRing" && ./gradlew :app:testDebugUnitTest
```
预期：**编译失败**，`Unresolved reference: CustomColors` / `ColorRange`。

> 若 Gradle 报「找不到 `testDebugUnitTest` 任务」或 `src/test` 里的 Kotlin 源集没被识别：说明 AGP 9 内置 Kotlin 支持未覆盖测试源集。此时**不要**去加 `org.jetbrains.kotlin.android` 插件（AGENTS.md 明令禁止），改为：删除 Step 1 新增的 `testImplementation` 行、删除 `CustomColorsTest.kt`，`CustomColors.kt` 仍按 Step 4 落地，改由 `assembleDebug` + Task 9 的真机用例覆盖这段逻辑，并在 Step 6 的抽查记录里写明「单测未启用：AGP 9 内置 Kotlin 不支持 src/test」。后续 Task 里所有 `:app:testDebugUnitTest` 命令同样跳过。

- [ ] **Step 4: 实现 `CustomColors.kt`**

新建 `HolePowerRing/app/src/main/java/com/powerring/hole/ring/CustomColors.kt`，完整内容：

```kotlin
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
```

- [ ] **Step 5: 跑测试确认通过**

```bash
cd "HolePowerRing" && ./gradlew :app:testDebugUnitTest
```
预期：`BUILD SUCCESSFUL`，6 个用例全绿。

- [ ] **Step 6: 抽查 diff**

核对：`CustomColors.kt` 无任何 `android.*` import；区间上限常量名 `MAX_RANGES`（后续 Task 与单测都用这个名字）。**不提交。**

---

### Task 2: 十六进制工具 `HexColor`（含 JVM 单测）

**Files:**
- Create: `HolePowerRing/app/src/main/java/com/powerring/hole/ui/HexColor.kt`
- Create: `HolePowerRing/app/src/test/java/com/powerring/hole/ui/HexColorTest.kt`

- [ ] **Step 1: 写失败的单测**

新建 `HolePowerRing/app/src/test/java/com/powerring/hole/ui/HexColorTest.kt`，完整内容：

```kotlin
package com.powerring.hole.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HexColorTest {

    @Test
    fun parseSixDigitAddsOpaqueAlpha() {
        assertEquals(0xFF277AF7.toInt(), HexColor.parse("277AF7"))
        assertEquals(0xFF277AF7.toInt(), HexColor.parse("#277af7"))
    }

    @Test
    fun parseThreeDigitExpandsPerChannel() {
        assertEquals(0xFFFFFFFF.toInt(), HexColor.parse("FFF"))
        assertEquals(0xFFFF77DD.toInt(), HexColor.parse("#f7d"))
    }

    @Test
    fun parseEightDigitKeepsUserAlpha() {
        assertEquals(0x80FFD700.toInt(), HexColor.parse("80FFD700"))
    }

    @Test
    fun parseRejectsWrongLengthOrIllegalChars() {
        assertNull(HexColor.parse(""))
        assertNull(HexColor.parse("277A"))
        assertNull(HexColor.parse("277AF"))
        assertNull(HexColor.parse("277AF7G"))
        assertNull(HexColor.parse("#"))
    }

    @Test
    fun formatHidesOpaqueAlphaAndKeepsTransparency() {
        assertEquals("277AF7", HexColor.format(0xFF277AF7.toInt()))
        assertEquals("80FFD700", HexColor.format(0x80FFD700.toInt()))
        assertEquals("000000", HexColor.format(0xFF000000.toInt()))
    }

    @Test
    fun normalizeInputStripsNonHexAndCapsLength() {
        assertEquals("277AF7", HexColor.normalizeInput("27 7a-f7"))
        assertEquals("#FFD700", HexColor.normalizeInput("#FFD700"))
        assertEquals("#FFD70000", HexColor.normalizeInput("#FFD70000"))
        assertEquals("#FFD70000", HexColor.normalizeInput("#FFD70000AA"))
        assertEquals("FF", HexColor.normalizeInput("zzFF"))
    }

    @Test
    fun parseAcceptsHashPrefixedEightDigit() {
        assertEquals(0xFFD70000.toInt(), HexColor.parse("#FFD70000"))
        assertEquals(0xFF277AF7.toInt(), HexColor.parse("#277AF7"))
    }

    @Test
    fun formatAndParseRoundTrip() {
        for (argb in listOf(0xFF277AF7.toInt(), 0x80FFD700.toInt(), 0xFF000000.toInt())) {
            assertEquals(argb, HexColor.parse(HexColor.format(argb)))
        }
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd "HolePowerRing" && ./gradlew :app:testDebugUnitTest
```
预期：编译失败，`Unresolved reference: HexColor`。

- [ ] **Step 3: 实现 `HexColor.kt`**

新建 `HolePowerRing/app/src/main/java/com/powerring/hole/ui/HexColor.kt`，完整内容：

```kotlin
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
     * 最长 9 个字符（`#` + 8 位）。让输入框不可能出现非法字符。
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
```

- [ ] **Step 4: 跑测试确认通过**

```bash
cd "HolePowerRing" && ./gradlew :app:testDebugUnitTest
```
预期：`BUILD SUCCESSFUL`（`CustomColorsTest` + `HexColorTest` 全绿）。

- [ ] **Step 5: 抽查 diff**（确认没混进 `android.graphics`）。**不提交。**

---

### Task 3: `RingConfig` 新增模式与配色字段

**Files:**
- Modify: `HolePowerRing/app/src/main/java/com/powerring/hole/ring/RingConfig.kt`

- [ ] **Step 1: 加字段**

锚点：数据类里 `customColor` 那两行（`/** 自定义颜色（ARGB） */` + `val customColor: Int = ...`）之后追加：

```kotlin
    /** 自定义颜色（ARGB）；仅 [colorMode] 为 MODE_FIXED_COLOR 时生效 */
    val customColor: Int = 0xFF277AF7.toInt(),
    /** 配色模式，四选一互斥，取值见 [MODE_FOLLOW_SYSTEM] 等常量 */
    val colorMode: Int = MODE_FOLLOW_SYSTEM,
    /** 「按电池状态」模式下五路用户色；单路为 0 表示未设置、该状态回退系统色 */
    val stateColors: StateColors = StateColors.DEFAULT,
    /** 「按电量区间」模式的区间表；列表顺序即优先级，第一条命中生效 */
    val levelRanges: List<ColorRange> = emptyList(),
```

同时把 `useCustomColor` 的注释改为遗留说明（字段保留，渲染端不再读它）：

```kotlin
    /**
     * 遗留字段：旧版本的「自定义颜色」开关。现由 [colorMode] 取代，
     * 仅在 [colorMode] 尚未写入配置时用于推导模式（见 `PrefsStore.resolveColorMode`）。
     */
    val useCustomColor: Boolean = false,
```

- [ ] **Step 2: 加 key 与模式常量**

锚点：companion 内 `KEY_CUSTOM_COLOR` 之后：

```kotlin
        const val KEY_CUSTOM_COLOR = "custom_color_argb"
        const val KEY_COLOR_MODE = "color_mode"
        const val KEY_STATE_COLOR_NORMAL = "state_color_normal"
        const val KEY_STATE_COLOR_LOW = "state_color_low"
        const val KEY_STATE_COLOR_POWER_SAVE = "state_color_power_save"
        const val KEY_STATE_COLOR_PERFORMANCE = "state_color_performance"
        const val KEY_STATE_COLOR_CHARGING = "state_color_charging"
        const val KEY_LEVEL_RANGES = "level_range_colors"

        /** 跟随系统电池图标色（改动前的默认行为） */
        const val MODE_FOLLOW_SYSTEM = 0

        /** 固定单色，取 [customColor] */
        const val MODE_FIXED_COLOR = 1

        /** 按电池状态五路取色，取 [stateColors] */
        const val MODE_BATTERY_STATE = 2

        /** 按电量区间取色，取 [levelRanges] */
        const val MODE_LEVEL_RANGE = 3
```

- [ ] **Step 3: 编译**

```bash
cd "HolePowerRing" && ./gradlew assembleDebug
```
预期：`BUILD SUCCESSFUL`（渲染端与配置链路尚未读新字段，行为不变）。

- [ ] **Step 4: 抽查 diff**（key 字符串共 7 个，后续 Task 4 会逐字引用）。**不提交。**

---

### Task 4: 配置链路打通（PrefsStore → ConfigProvider → HookPrefs）

**Files:**
- Modify: `HolePowerRing/app/src/main/java/com/powerring/hole/ui/PrefsStore.kt`
- Modify: `HolePowerRing/app/src/main/java/com/powerring/hole/ui/ConfigProvider.kt`
- Modify: `HolePowerRing/app/src/main/java/com/powerring/hole/core/HookPrefs.kt`

- [ ] **Step 1: `PrefsStore` 加 `setString` 与模式派生**

锚点：`fun setInt(...)` 整段之后插入两个成员：

```kotlin
    fun setString(context: Context, key: String, value: String) {
        prefs(context).edit().putString(key, value).commit()
        commit(context)
    }

    /**
     * 有效配色模式。`color_mode` 是本版本新增的 key，老用户 XML 里只有
     * `use_custom_color`；未显式设置过模式时按旧值推导，这样模块升级后
     * **即使从不打开设置页**也不会把「固定单色」静默重置成「跟随系统」。
     */
    fun resolveColorMode(prefs: SharedPreferences): Int = when {
        prefs.contains(RingConfig.KEY_COLOR_MODE) ->
            prefs.getInt(RingConfig.KEY_COLOR_MODE, RingConfig.DEFAULT.colorMode)
        prefs.getBoolean(RingConfig.KEY_USE_CUSTOM_COLOR, false) -> RingConfig.MODE_FIXED_COLOR
        else -> RingConfig.MODE_FOLLOW_SYSTEM
    }
```

- [ ] **Step 2: `PrefsStore.load()` 读新字段**

锚点：`customColor = p.getBoolean/getInt(...)` 那行（现为 `customColor = p.getInt(RingConfig.KEY_CUSTOM_COLOR, 0xFF277AF7.toInt()),`）之后追加：

```kotlin
            colorMode = resolveColorMode(p),
            stateColors = StateColors(
                normal = p.getInt(RingConfig.KEY_STATE_COLOR_NORMAL, 0),
                low = p.getInt(RingConfig.KEY_STATE_COLOR_LOW, 0),
                powerSave = p.getInt(RingConfig.KEY_STATE_COLOR_POWER_SAVE, 0),
                performance = p.getInt(RingConfig.KEY_STATE_COLOR_PERFORMANCE, 0),
                charging = p.getInt(RingConfig.KEY_STATE_COLOR_CHARGING, 0),
            ),
            levelRanges = CustomColors.decodeRanges(
                p.getString(RingConfig.KEY_LEVEL_RANGES, ""),
            ),
```

import 区补：

```kotlin
import com.powerring.hole.ring.CustomColors
import com.powerring.hole.ring.StateColors
```

- [ ] **Step 3: `ConfigProvider` 暴露新列**

锚点：`valueOf()` 的 `when` 内 `RingConfig.KEY_CUSTOM_COLOR -> ...` 之后：

```kotlin
        RingConfig.KEY_COLOR_MODE -> PrefsStore.resolveColorMode(prefs)
        RingConfig.KEY_STATE_COLOR_NORMAL -> prefs.getInt(key, 0)
        RingConfig.KEY_STATE_COLOR_LOW -> prefs.getInt(key, 0)
        RingConfig.KEY_STATE_COLOR_POWER_SAVE -> prefs.getInt(key, 0)
        RingConfig.KEY_STATE_COLOR_PERFORMANCE -> prefs.getInt(key, 0)
        RingConfig.KEY_STATE_COLOR_CHARGING -> prefs.getInt(key, 0)
        RingConfig.KEY_LEVEL_RANGES -> prefs.getString(key, "")
```

锚点：`ALL_KEYS` 数组内 `RingConfig.KEY_CUSTOM_COLOR,` 之后：

```kotlin
            RingConfig.KEY_CUSTOM_COLOR,
            RingConfig.KEY_COLOR_MODE,
            RingConfig.KEY_STATE_COLOR_NORMAL,
            RingConfig.KEY_STATE_COLOR_LOW,
            RingConfig.KEY_STATE_COLOR_POWER_SAVE,
            RingConfig.KEY_STATE_COLOR_PERFORMANCE,
            RingConfig.KEY_STATE_COLOR_CHARGING,
            RingConfig.KEY_LEVEL_RANGES,
```

- [ ] **Step 4: `HookPrefs.load()` 还原新字段**

锚点：`cached = RingConfig(` 构造块内 `customColor = toInt(values[RingConfig.KEY_CUSTOM_COLOR], d.customColor),` 之后：

```kotlin
            colorMode = toInt(values[RingConfig.KEY_COLOR_MODE], d.colorMode),
            stateColors = StateColors(
                normal = toInt(values[RingConfig.KEY_STATE_COLOR_NORMAL], 0),
                low = toInt(values[RingConfig.KEY_STATE_COLOR_LOW], 0),
                powerSave = toInt(values[RingConfig.KEY_STATE_COLOR_POWER_SAVE], 0),
                performance = toInt(values[RingConfig.KEY_STATE_COLOR_PERFORMANCE], 0),
                charging = toInt(values[RingConfig.KEY_STATE_COLOR_CHARGING], 0),
            ),
            // 区间表是一条字符串；解析函数内部已对坏数据逐段降级，不会抛
            levelRanges = CustomColors.decodeRanges(
                values[RingConfig.KEY_LEVEL_RANGES] as? String,
            ),
```

import 区补（锚点：现有 `import com.powerring.hole.ring.RingConfig` 附近）：

```kotlin
import com.powerring.hole.ring.CustomColors
import com.powerring.hole.ring.StateColors
```

- [ ] **Step 5: 编译**

```bash
cd "HolePowerRing" && ./gradlew assembleDebug
```
预期：`BUILD SUCCESSFUL`。

- [ ] **Step 6: 单测仍绿**

```bash
cd "HolePowerRing" && ./gradlew :app:testDebugUnitTest
```
预期：`BUILD SUCCESSFUL`。

- [ ] **Step 7: 抽查 diff**（重点：7 个 key 字符串在 `RingConfig` / `PrefsStore` / `ConfigProvider` / `HookPrefs` 四处逐字一致；`KEY_COLOR_MODE` 走 `resolveColorMode` 而不是直接 `prefs.getInt`）。**不提交。**

---

### Task 5: 渲染端按模式取色 + 配置签名 + 配色日志

**Files:**
- Modify: `HolePowerRing/app/src/main/java/com/powerring/hole/ring/MiuixPalette.kt`
- Modify: `HolePowerRing/app/src/main/java/com/powerring/hole/ring/RingRenderer.kt`
- Modify: `HolePowerRing/app/src/main/java/com/powerring/hole/ring/RingState.kt`

- [ ] **Step 1: `MiuixPalette` 补性能模式回退色**

锚点：`POWER_SAVE_AMBER` 常量及其注释之后：

```kotlin
    /**
     * 性能模式语义色：miuix 令牌里同样没有对应色，取与省电琥珀可区分的橙色。
     * 仅在「按电池状态」模式下、且系统调色板不可用时作为回退。
     */
    const val PERFORMANCE_ORANGE = 0xFFFF7A1E.toInt()
```

- [ ] **Step 2: 文件头配色策略改为模式语义**

锚点：`RingRenderer` 类 KDoc 里「配色策略（2026-10-01 改造）：」到该注释块结束，整段替换为：

```kotlin
 * 配色策略（2026-10-02 改为四模式互斥，见 RingConfig.MODE_*）：
 * 1. MODE_FIXED_COLOR → 固定用 [RingConfig.customColor]
 * 2. MODE_BATTERY_STATE → 五路用户色（[RingConfig.stateColors]）；
 *    未设置（0）的那一路逐槽回退到系统电池图标色 / 内置语义色
 * 3. MODE_LEVEL_RANGE → 按电量区间表取第一条命中的色，未命中回退跟随系统
 * 4. MODE_FOLLOW_SYSTEM（默认）→ 五种状态全部跟随原生图标颜色
 *    （普通 / 低电 / 省电 / 性能 / 充电），底槽取进度色的低透明度版本
 * 5. 上述任一路径都取不到颜色（Hook 未装上 / 机型类名不同）→ 回退 miuix 语义色：
 *    底槽用 sliderBackground 令牌色、充电 primary 蓝、低电 error 红、
 *    省电琥珀与性能橙（应用自定义语义色）
```

- [ ] **Step 3: 替换颜色解析块**

锚点：从 `// 颜色优先级：自定义色（覆盖一切）> 系统电池图标调色板 > 内置语义色回退。` 到 `rawTrack` 那个 `when` 结束（现为 `else -> MiuixPalette.trackColor(darkIcons)` 收尾的 `}`），整段替换为：

```kotlin
        // 四种模式互斥；分支只决定「进度色从哪来」，几何与动画一律不受影响。
        val palette = state.batteryPalette
        val fromUserOrSystem: Boolean
        val rawProgress: Int = when (config.colorMode) {
            RingConfig.MODE_FIXED_COLOR -> {
                fromUserOrSystem = true
                config.customColor
            }

            RingConfig.MODE_BATTERY_STATE -> {
                fromUserOrSystem = true
                batteryStateColor(config, palette, state, darkIcons)
            }

            RingConfig.MODE_LEVEL_RANGE -> {
                fromUserOrSystem = true
                levelRangeColor(config, palette, state, darkIcons)
            }

            else -> {
                val followSystem = palette.ready
                fromUserOrSystem = followSystem
                // 顺序与系统 MiuiBatteryMeterIconView.getProgressStatus() 一致：
                // 充电（含快充）> 性能模式 > 省电模式 > 低电量 > 普通
                when {
                    followSystem && palette.chargingNow -> palette.charging
                    followSystem && palette.performanceNow -> palette.performance
                    followSystem && palette.powerSaveNow -> palette.powerSave
                    followSystem && palette.lowNow -> palette.low
                    followSystem -> state.normalColor
                    state.level <= LOW_BATTERY_THRESHOLD -> MiuixPalette.errorColor(darkIcons)
                    state.charging -> MiuixPalette.primaryColor(darkIcons)
                    state.powerSave -> MiuixPalette.POWER_SAVE_AMBER
                    else -> MiuixPalette.foregroundColor(darkIcons)
                }
            }
        }
        // 来自用户配置或系统调色板时，底槽取进度色的低透明度版本，视觉更统一；
        // 只有退回内置令牌时才用 sliderBackground 槽色。
        val rawTrack = if (fromUserOrSystem) {
            (TRACK_ALPHA shl 24) or (rawProgress and 0x00FFFFFF)
        } else {
            MiuixPalette.trackColor(darkIcons)
        }
```

- [ ] **Step 4: 加配色日志（节流）**

锚点：`@Volatile private var diagLogged = false` 之后：

```kotlin
    /** 配色变化日志：与 RingState 里的日志同样必须节流，跟随系统时每帧都在变。 */
    @Volatile
    private var lastColorLogMs = 0L

    @Volatile
    private var lastLoggedColor = 0
```

锚点：`val trackColor = scaleAlpha(rawTrack, expand)` 之后：

```kotlin
        val colorNow = android.os.SystemClock.uptimeMillis()
        if (progressColor != lastLoggedColor && colorNow - lastColorLogMs >= 500L) {
            lastLoggedColor = progressColor
            lastColorLogMs = colorNow
            ModuleLog.i(
                "环配色: mode=${config.colorMode} color=#${Integer.toHexString(progressColor)} " +
                    "level=${state.level} ranges=${config.levelRanges.size}",
            )
        }
```

- [ ] **Step 5: 两个模式解析辅助函数**

锚点：`object RingRenderer` 的 `fun draw(...)` 结束 `}` 之后、`scaleAlpha` 之前，插入：

```kotlin
    /**
     * 「按电池状态」：状态判定优先级与系统 `getProgressStatus()` 一致
     * （充电 > 性能 > 省电 > 低电 > 普通）。系统调色板可用时借用它的状态位，
     * 否则退回本模块从广播侧能拿到的 charging / powerSave / level。
     * 用户未设置（0）的槽位逐槽回退：系统色 → 内置令牌色。
     */
    private fun batteryStateColor(
        config: RingConfig,
        palette: BatteryPalette,
        state: RingState,
        darkIcons: Boolean,
    ): Int {
        val useSystem = palette.ready
        val charging = if (useSystem) palette.chargingNow else state.charging
        val performance = useSystem && palette.performanceNow
        val powerSave = if (useSystem) palette.powerSaveNow else state.powerSave
        val low = if (useSystem) palette.lowNow else state.level <= LOW_BATTERY_THRESHOLD
        val s = config.stateColors
        return when {
            charging -> s.charging.orElse(
                if (useSystem) palette.charging else MiuixPalette.primaryColor(darkIcons),
            )

            performance -> s.performance.orElse(
                if (useSystem) palette.performance else MiuixPalette.PERFORMANCE_ORANGE,
            )

            powerSave -> s.powerSave.orElse(
                if (useSystem) palette.powerSave else MiuixPalette.POWER_SAVE_AMBER,
            )

            low -> s.low.orElse(
                if (useSystem) palette.low else MiuixPalette.errorColor(darkIcons),
            )

            else -> s.normal.orElse(
                if (useSystem) state.normalColor else MiuixPalette.foregroundColor(darkIcons),
            )
        }
    }

    /**
     * 「按电量区间」：按列表顺序取第一条命中 `state.level` 的区间色。
     * 用 level 而不是 animatedLevel——过边界时颜色本就该明确跳变，
     * 拿动画值去取色只会让颜色和弧度不同步。
     * 未命中任何区间时退回「跟随系统」那条链，保证环在深浅背景下都可见。
     */
    private fun levelRangeColor(
        config: RingConfig,
        palette: BatteryPalette,
        state: RingState,
        darkIcons: Boolean,
    ): Int {
        val hit = CustomColors.colorForLevel(config.levelRanges, state.level)
        if (hit != 0) return hit
        return when {
            palette.ready -> state.normalColor
            state.level <= LOW_BATTERY_THRESHOLD -> MiuixPalette.errorColor(darkIcons)
            state.charging -> MiuixPalette.primaryColor(darkIcons)
            state.powerSave -> MiuixPalette.POWER_SAVE_AMBER
            else -> MiuixPalette.foregroundColor(darkIcons)
        }
    }

    /** 0 = 未设置，取回退色。 */
    private fun Int.orElse(fallback: Int): Int = if (this == 0) fallback else this
```

`CustomColors` / `BatteryPalette` / `MiuixPalette` / `RingConfig` 与 `RingRenderer` 同包（`com.powerring.hole.ring`），**不加任何 import**——该文件现有 import 只有 `android.graphics.*`、`android.view.View` 与 `ModuleLog`，保持不变即可。

- [ ] **Step 6: `RingState.signature()` 纳入新字段**

锚点：`RingState` 内 `private fun RingConfig.signature()` 整段替换：

```kotlin
    private fun RingConfig.signature() =
        "$ringEnabled|$strokeWidthDp|$offsetXDp|$offsetYDp|$scale|" +
            "$colorMode|$customColor|" +
            "$collapseOnImmersive|$collapseOnIsland|" +
            "${stateColors.normal},${stateColors.low},${stateColors.powerSave}," +
            "${stateColors.performance},${stateColors.charging}|" +
            CustomColors.encodeRanges(levelRanges)
```

同文件无需新增 import：`CustomColors` 与 `RingState` 同在 `com.powerring.hole.ring` 包。

- [ ] **Step 7: 编译**

```bash
cd "HolePowerRing" && ./gradlew assembleDebug
```
预期：`BUILD SUCCESSFUL`。若报 `progressFromConfig` / `fromUserOrSystem` 「must be initialized more than once」，说明 Step 3 的 `when` 有分支漏赋值——每个分支（含 `else`）都必须赋一次。

- [ ] **Step 8: 单测仍绿**

```bash
cd "HolePowerRing" && ./gradlew :app:testDebugUnitTest
```

- [ ] **Step 9: 抽查 diff**（关键回归点：`MODE_FOLLOW_SYSTEM` 分支的九行 `when` 与改动前逐行等价，只是外面包了一层 `else ->`）。**不提交。**

---

### Task 6: 共用色盘弹窗（含十六进制输入）

**Files:**
- Create: `HolePowerRing/app/src/main/java/com/powerring/hole/ui/ColorPickerDialog.kt`

- [ ] **Step 1: 新建 `ColorPickerDialog.kt`**

完整内容：

```kotlin
package com.powerring.hole.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.powerring.hole.ring.RingConfig
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ColorPicker
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 一次颜色编辑会话：标题、初值、确认回调。
 * 由页面侧持有（每次打开都新建实例），弹窗自身不携带业务语义。
 */
data class ColorEditing(
    val label: String,
    val initialArgb: Int,
    val onConfirm: (Int) -> Unit,
)

/**
 * 全站共用的色盘弹窗：miuix `ColorPicker` + 十六进制输入框，两者双向同步。
 *
 * 同步不会成环：滑色盘 → 重写 [hexText]，输入十六进制 → 重写 [color]，
 * 两条路径写的是各自的对端状态，不是彼此监听（无 `LaunchedEffect` 回环）。
 * 落盘只发生在「确定」，与改造前的行为一致。
 *
 * [editing] 为 null 时弹窗关闭；传入新对象会重置内部编辑态。
 */
@Composable
fun ColorPickerDialog(editing: ColorEditing?, onDismiss: () -> Unit) {
    val initial = editing?.initialArgb ?: RingConfig.DEFAULT.customColor
    var color by remember(editing) { mutableStateOf(Color(initial)) }
    var hexText by remember(editing) { mutableStateOf(HexColor.format(initial)) }
    val hexInvalid = hexText.isNotEmpty() && HexColor.parse(hexText) == null

    OverlayDialog(
        show = editing != null,
        title = editing?.label ?: "选择颜色",
        onDismissRequest = onDismiss,
    ) {
        ColorPicker(
            color = color,
            onColorChanged = {
                color = it
                hexText = HexColor.format(it.toArgb())
            },
            showPreview = true,
        )
        Spacer(modifier = Modifier.height(12.dp))
        TextField(
            value = hexText,
            onValueChange = { raw ->
                val normalized = HexColor.normalizeInput(raw)
                hexText = normalized
                HexColor.parse(normalized)?.let { color = Color(it) }
            },
            label = "十六进制色值",
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            trailingIcon = {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(color),
                )
            },
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = if (hexInvalid) {
                "长度只能是 3 / 6 / 8 位"
            } else {
                "支持 RGB、RRGGBB、AARRGGBB；前两位是透明度，设为 00 时环会看不见"
            },
            modifier = Modifier.fillMaxWidth(),
            fontSize = MiuixTheme.textStyles.body2.fontSize,
            color = if (hexInvalid) {
                MiuixTheme.colorScheme.error
            } else {
                MiuixTheme.colorScheme.onSurfaceVariantSummary
            },
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(modifier = Modifier.weight(1f))
            Button(onClick = onDismiss) {
                Text(text = "取消")
            }
            Spacer(modifier = Modifier.width(12.dp))
            Button(
                onClick = {
                    val argb = color.toArgb()
                    editing?.onConfirm?.invoke(argb)
                    onDismiss()
                },
            ) {
                Text(text = "确定")
            }
        }
    }
}
```

- [ ] **Step 2: 编译**

```bash
cd "HolePowerRing" && ./gradlew assembleDebug
```
预期：`BUILD SUCCESSFUL`（本文件暂时无人引用，只是编译进 APK）。若 `TextField` / `OverlayDialog` / `ColorPicker` 报参数不存在，说明本仓库 miuix 版本与计划所依据的 `0.9.4-rc01`（`app/build.gradle.kts` 已确认）不一致，**停下来报告 API 差异**，不要改用 Material3 组件替代。

- [ ] **Step 3: 抽查 diff。不提交。**

---

### Task 7: 模式下拉 + 固定单色接入共用弹窗

**Files:**
- Modify: `HolePowerRing/app/src/main/java/com/powerring/hole/ui/SettingsScreen.kt`

- [ ] **Step 1: 页面根状态改用 `ColorEditing`**

锚点：`SettingsScreen` 内这两行整体删除：

```kotlin
    var showColorPicker by remember { mutableStateOf(false) }
    // 色盘内的临时编辑色，点确定才落盘
    var editingColor by remember { mutableStateOf(Color(config.customColor)) }
```

替换为：

```kotlin
    // 当前正在编辑的颜色（标签 + 初值 + 确认回调）；null 表示弹窗关闭
    var colorEditing by remember { mutableStateOf<ColorEditing?>(null) }
```

- [ ] **Step 2: 内联色盘弹窗换成共用弹窗**

锚点：`SettingsScreen` 里那段 `// 色盘弹窗` 注释起、整个 `OverlayDialog(...) { ... }`（含 `ColorPicker`、取消/确定按钮行）到它的收尾 `}`，整段替换为：

```kotlin
        ColorPickerDialog(editing = colorEditing, onDismiss = { colorEditing = null })
```

- [ ] **Step 3: 外观页接收编辑回调**

锚点：`HorizontalPager` 里 `1 -> AppearanceTabContent(` 调用处，把 `onOpenColorPicker = { ... }` 那一段换成：

```kotlin
                    onEditColor = { colorEditing = it },
```

锚点：`AppearanceTabContent` 的签名里 `onOpenColorPicker: () -> Unit,` 换成：

```kotlin
    onEditColor: (ColorEditing) -> Unit,
```

- [ ] **Step 4: 颜色区改成模式驱动**

锚点：`AppearanceTabContent` 内 `item(key = "colorCard") {` 的整个 `Card { ... }` 内容（现在是一个 `SwitchPreference` + `ArrowPreference`），替换为：

```kotlin
            item(key = "colorCard") {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    OverlayDropdownPreference(
                        items = COLOR_MODE_ITEMS,
                        selectedIndex = config.colorMode.coerceIn(0, COLOR_MODE_ITEMS.lastIndex),
                        title = "环颜色模式",
                        summary = "四种方式互斥，同一时刻只有一套颜色生效",
                        enabled = config.ringEnabled,
                        onSelectedIndexChange = { index ->
                            PrefsStore.setInt(ctx, RingConfig.KEY_COLOR_MODE, index)
                            update(config.copy(colorMode = index))
                        },
                    )
                    when (config.colorMode) {
                        RingConfig.MODE_FIXED_COLOR -> {
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                            ArrowPreference(
                                title = "选择颜色",
                                summary = "打开色盘设置固定环颜色，可输入十六进制精确取值",
                                enabled = config.ringEnabled,
                                onClick = onEditColor(
                                    label = "选择环颜色",
                                    argb = config.customColor,
                                    onResult = { argb ->
                                        PrefsStore.setInt(ctx, RingConfig.KEY_CUSTOM_COLOR, argb)
                                        update(config.copy(customColor = argb))
                                    },
                                ),
                                endActions = { ColorSwatch(config.customColor) },
                            )
                        }

                        RingConfig.MODE_BATTERY_STATE -> {
                            STATE_ROWS.forEach { row ->
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                                val current = row.get(config.stateColors)
                                ArrowPreference(
                                    title = row.title,
                                    summary = if (current == 0) {
                                        "未设置：${row.hint}"
                                    } else {
                                        "自定义 #${HexColor.format(current)}"
                                    },
                                    enabled = config.ringEnabled,
                                    onClick = onEditColor(
                                        label = "${row.title} 环颜色",
                                        argb = current,
                                        onResult = { argb ->
                                            PrefsStore.setInt(ctx, row.key, argb)
                                            val next = row.set(config.stateColors, argb)
                                            update(config.copy(stateColors = next))
                                        },
                                    ),
                                    endActions = { ColorSwatch(current) },
                                )
                            }
                        }

                        RingConfig.MODE_LEVEL_RANGE -> {
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                            Text(
                                text = "命中顺序自上而下，第一条命中的区间生效；未命中时跟随系统电池图标色。想让 0% 也覆盖，需把某段起点设为 0。",
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                fontSize = MiuixTheme.textStyles.body2.fontSize,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
            }
```

> 区间模式的行列表在 Task 8 落地；此处先只放说明文案，保证本 Task 编译可用。

- [ ] **Step 5: 加本页共用的色块、编辑入口与模式表**

锚点：文件末尾 `ConfigSlider` 那个 `@Composable private fun` 之前插入：

```kotlin
// ====================================================================
// 颜色：模式表、状态色行表、色块、编辑入口
// ====================================================================

private val COLOR_MODE_ITEMS = listOf(
    "跟随系统电池图标",
    "固定单色",
    "按电池状态",
    "按电量区间",
)

/** 「按电池状态」的一行配置：显示名、配置 key、未设置时的回退说明。 */
private data class StateRowSpec(
    val title: String,
    val key: String,
    val hint: String,
    val get: (StateColors) -> Int,
    val set: (StateColors, Int) -> StateColors,
)

private val STATE_ROWS = listOf(
    StateRowSpec(
        "普通", RingConfig.KEY_STATE_COLOR_NORMAL, "跟随系统电池图标反色",
        { it.normal }, { s, v -> s.copy(normal = v) },
    ),
    StateRowSpec(
        "低电量", RingConfig.KEY_STATE_COLOR_LOW, "跟随系统低电色（无则 error 红）",
        { it.low }, { s, v -> s.copy(low = v) },
    ),
    StateRowSpec(
        "省电模式", RingConfig.KEY_STATE_COLOR_POWER_SAVE, "跟随系统省电色（无则琥珀）",
        { it.powerSave }, { s, v -> s.copy(powerSave = v) },
    ),
    StateRowSpec(
        "性能模式", RingConfig.KEY_STATE_COLOR_PERFORMANCE, "跟随系统性能色（无则橙）",
        { it.performance }, { s, v -> s.copy(performance = v) },
    ),
    StateRowSpec(
        "充电中", RingConfig.KEY_STATE_COLOR_CHARGING, "跟随系统充电色（无则 primary 蓝）",
        { it.charging }, { s, v -> s.copy(charging = v) },
    ),
)

/** 行尾色块。0（未设置）用半透明主色，与已设置的颜色区分开。 */
@Composable
private fun ColorSwatch(argb: Int) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(
                if (argb == 0) MiuixTheme.colorScheme.primary.copy(alpha = 0.25f) else Color(argb),
            ),
    )
}

/**
 * 打开共用色盘弹窗。返回一个可直接当 `onClick` 用的 lambda。
 *
 * 注意：`onResult` 捕获的是打开弹窗那一刻的 `config` 快照——弹窗打开期间
 * 不会有并发的配置变更（所有写入都只发生在「确定」），因此 `copy` 不会覆盖掉
 * 别的字段。
 */
private fun buildColorEdit(
    onEditColor: (ColorEditing) -> Unit,
    label: String,
    argb: Int,
    onResult: (Int) -> Unit,
): () -> Unit = { onEditColor(ColorEditing(label, argb, onResult)) }
```

`StateRowSpec` 里 `key` 只用于 `PrefsStore.setInt(ctx, row.key, argb)`，读写 `StateColors` 一律走 `get`/`set` lambda——因此 `ring/StateColors` 保持纯数据类，不需要反向依赖 `RingConfig` 的 key 常量。

- [ ] **Step 6: 在 `AppearanceTabContent` 内定义简写入口**

锚点：`AppearanceTabContent` 函数体第一行（`val listState = rememberLazyListState()`）之前插入：

```kotlin
    // 打开共用色盘弹窗的简写，避免每一行都重复四个实参
    fun editColor(label: String, argb: Int, onResult: (Int) -> Unit) =
        buildColorEdit(onEditColor, label, argb, onResult)
```

并把 Step 4 里两处 `onClick = onEditColor(...)` 改成 `onClick = editColor(...)`（参数不变）。

- [ ] **Step 7: import 补齐**

锚点：文件顶部 import 区，按需补（已有的不要重复）：

```kotlin
import com.powerring.hole.ring.StateColors
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
```

`ColorPicker`、`OverlayDialog` 若在 Step 2 之后不再被本文件直接使用，删除这两个 import（编译告警清理，保持文件干净）。

- [ ] **Step 8: 编译**

```bash
cd "HolePowerRing" && ./gradlew assembleDebug
```
预期：`BUILD SUCCESSFUL`。

- [ ] **Step 9: 抽查 diff**（确认：没有 Material3 组件；`use_custom_color` 不再出现在本页；模式下拉 `selectedIndex` 与 `COLOR_MODE_ITEMS` 顺序对应 0..3）。**不提交。**

---

### Task 8: 按电量区间（双端滑杆 + 色块 + 增删）

**Files:**
- Modify: `HolePowerRing/app/src/main/java/com/powerring/hole/ui/SettingsScreen.kt`

- [ ] **Step 1: 区间行 composable**

锚点：Task 7 新增的 `ColorSwatch` 之后插入：

```kotlin
/**
 * 一条电量区间：`RangeSliderPreference` 双端滑杆（0–100，整数步进）+ 行尾色块 + 删除。
 *
 * 与 `ConfigSlider` 同一套「真实按下」门控：miuix Slider 在首次组合时可能回调
 * onValueChange / onValueChangeFinished，未按下的回调一律不落盘，否则一进页面
 * 就把默认区间写进配置。拖动中只改内存态，松手才持久化。
 */
@Composable
private fun RangeColorRow(
    title: String,
    range: ColorRange,
    enabled: Boolean,
    onDragged: (ColorRange) -> Unit,
    onCommitted: (ColorRange) -> Unit,
    onColorClicked: () -> Unit,
    onDelete: () -> Unit,
) {
    var dragging by remember { mutableStateOf<ClosedFloatingPointRange<Float>?>(null) }
    var touched by remember { mutableStateOf(false) }
    val display = dragging ?: (range.start.toFloat()..range.end.toFloat())

    val touchGate = Modifier.pointerInput(enabled) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                if (event.type == androidx.compose.ui.input.pointer.PointerEventType.Press) {
                    touched = true
                }
            }
        }
    }

    RangeSliderPreference(
        value = display,
        onValueChange = {
            val snapped = it.start.roundToInt()..it.end.roundToInt()
            dragging = snapped.start.toFloat()..snapped.endInclusive.toFloat()
            if (touched) onDragged(ColorRange(snapped.start, snapped.endInclusive, range.color))
        },
        onValueChangeFinished = {
            if (touched) {
                dragging?.let {
                    onCommitted(ColorRange(it.start.toInt(), it.end.toInt(), range.color))
                }
            }
            touched = false
            dragging = null
        },
        title = title,
        summary = "色块改颜色，右端按钮删除本区间",
        valueText = "${range.start}%–${range.end}%",
        valueRange = 0f..100f,
        steps = 99,
        enabled = enabled,
        modifier = touchGate,
        endActions = {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color(range.color))
                    .clickable(enabled = enabled, onClick = onColorClicked),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = onDelete,
                enabled = enabled,
                modifier = Modifier.height(32.dp),
            ) {
                Text(text = "删除")
            }
        },
    )
}
```

- [ ] **Step 2: 把区间模式分支填进 `AppearanceTabContent`**

锚点：Step 4（Task 7）里 `RingConfig.MODE_LEVEL_RANGE -> { ... }` 那个分支整段替换为：

```kotlin
                        RingConfig.MODE_LEVEL_RANGE -> {
                            val ranges = config.levelRanges
                            if (ranges.isEmpty()) {
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                                Text(
                                    text = "暂无区间：此时环跟随系统电池图标色。",
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                            ranges.forEachIndexed { index, range ->
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                                val persist: (List<ColorRange>) -> Unit = { next ->
                                    PrefsStore.setString(
                                        ctx,
                                        RingConfig.KEY_LEVEL_RANGES,
                                        CustomColors.encodeRanges(next),
                                    )
                                    update(config.copy(levelRanges = next))
                                }
                                RangeColorRow(
                                    title = "区间 ${index + 1}",
                                    range = range,
                                    enabled = config.ringEnabled,
                                    onDragged = { moved ->
                                        update(
                                            config.copy(
                                                levelRanges = ranges.mapIndexed { i, r ->
                                                    if (i == index) moved else r
                                                },
                                            ),
                                        )
                                    },
                                    onCommitted = { moved ->
                                        persist(
                                            ranges.mapIndexed { i, r ->
                                                if (i == index) moved else r
                                            },
                                        )
                                    },
                                    onColorClicked = editColor(
                                        label = "区间 ${index + 1} 颜色",
                                        argb = range.color,
                                    ) { argb ->
                                        persist(
                                            ranges.mapIndexed { i, r ->
                                                if (i == index) r.copy(color = argb) else r
                                            },
                                        )
                                    },
                                    onDelete = {
                                        persist(ranges.filterIndexed { i, _ -> i != index })
                                    },
                                )
                            }
                            if (ranges.size < CustomColors.MAX_RANGES) {
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                                Button(
                                    onClick = {
                                        // 新区间插到最前 = 优先级最高（命中按列表顺序）
                                        val next = listOf(
                                            ColorRange(1, 20, MiuixPalette.PRIMARY_DARK),
                                        ) + ranges
                                        PrefsStore.setString(
                                            ctx,
                                            RingConfig.KEY_LEVEL_RANGES,
                                            CustomColors.encodeRanges(next),
                                        )
                                        update(config.copy(levelRanges = next))
                                    },
                                    enabled = config.ringEnabled,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                ) {
                                    Text(text = "新增区间（最多 ${CustomColors.MAX_RANGES} 段）")
                                }
                            }
                        }
```

- [ ] **Step 3: import 补齐**

锚点：文件顶部 import 区补（已有则跳过）：

```kotlin
import androidx.compose.foundation.clickable
import com.powerring.hole.ring.ColorRange
import com.powerring.hole.ring.CustomColors
import com.powerring.hole.ring.MiuixPalette
import top.yukonga.miuix.kmp.preference.RangeSliderPreference
```

- [ ] **Step 4: 编译**

```bash
cd "HolePowerRing" && ./gradlew assembleDebug
```
预期：`BUILD SUCCESSFUL`。`steps = 99` 配 `valueRange = 0f..100f` 即 1% 整数步进（miuix `steps` 语义与 Compose 一致：端点间的离散步数）；`onValueChange` 里的 `roundToInt()` 是二次保险，两者不冲突。

- [ ] **Step 5: 抽查 diff**（确认：区间写入永远经 `CustomColors.encodeRanges`；没有任何一处直接拼字符串；删除/新增都走同一个 `persist`）。**不提交。**

---

### Task 9: 关于页文案 + 真机验证 + 文档回写

**Files:**
- Modify: `HolePowerRing/app/src/main/java/com/powerring/hole/ui/SettingsScreen.kt`（「提示」文案）
- Modify: `挖孔环形电量LSP模块-可行性分析报告.md`（新增 §15）
- Modify: `AGENTS.md`（如验证未全部达预期，补一行说明）

- [ ] **Step 1: 「提示」文案补配色模式说明**

锚点：`AboutTabContent` 里 `"提示：所有调节即时生效；…"` 那串拼接，在「横屏时圆环无法贴合挖孔…」之后插入一句：

```kotlin
                            "环颜色有四种互斥模式：跟随系统、固定单色、按电池状态、按电量区间；" +
                            "未设置的状态色会自动跟随原生图标。" +
```

- [ ] **Step 2: 全量编译与单测**

```bash
cd "HolePowerRing" && ./gradlew assembleDebug :app:testDebugUnitTest
```
预期：两条命令都 `BUILD SUCCESSFUL`。

- [ ] **Step 3: 装机**

```bash
cd "HolePowerRing" && ./gradlew installDebug
```
后续动作需用户手动/征得同意后执行（本机记忆约束：`adb shell su` 不可用；杀 SystemUI 属设备状态变更，**先问用户**）：LSPosed 确认作用域仍勾选系统界面 → 重启 SystemUI（`adb shell killall com.android.systemui`）。

- [ ] **Step 4: 基线回归（跟随系统模式）**

```bash
adb -s 6f1adaee logcat -c
adb -s 6f1adaee logcat -s HolePowerRing | grep -E "配置已重新加载|环配色"
```
预期：`配置已重新加载: RingConfig(... colorMode=0, stateColors=StateColors(normal=0, low=0, powerSave=0, performance=0, charging=0), levelRanges=[])`，且 `环配色: mode=0 ...` 的颜色与升级前观感一致。若 `colorMode` 不是 0，回到 Task 4 Step 3/7 核对三处 key 字符串。

- [ ] **Step 5: 旧配置迁移用例（重要）**

若测试机升级前是「自定义颜色=开」：不打开设置页，直接重启 SystemUI。
预期：日志 `colorMode=1`（由 `use_custom_color` 派生），环仍是原来的自定义色。若变成 `colorMode=0`，说明 `ConfigProvider` 的 `KEY_COLOR_MODE` 分支没走 `resolveColorMode`。

- [ ] **Step 6: 固定单色 + 十六进制输入**

设置页 → 外观 → 模式选「固定单色」→ 选颜色。逐条验：
- `F7D` → 环呈浅紫蓝，色盘同步跳到对应色；
- `277AF7` → 与 `#277AF7` 同色；
- `80FFD700` → 金色半透明（透明度 50%），底槽仍是该色 20%；
- 输入到 `277A`（4 位）时提示行变红且环色不变，补成 6 位后立刻生效；
- 点「取消」不落盘；点「确定」后 `环配色: mode=1 color=#...` 与新色一致（无需重启 SystemUI，`CONFIG_CHANGED` 广播应即时生效）。

- [ ] **Step 7: 按电池状态模式**

只设「低电量」为金色，其余四路保持未设置（色块为半透明主色）。
预期：电量 <15%（或系统判定低电）时环呈金色、日志 `mode=2`；电量 >20% 时环恢复跟随原生图标反色（`normalColor`），充电插拔时跟随系统充电色。若未设置的那几路变成黑色 → `orElse(0)` 判定没生效，回 Task 5 Step 5。
顺带验一行：把「普通」也设成固定色后，充电/省电/性能/低电四路仍各自独立（优先级：充电 > 性能 > 省电 > 低电 > 普通）。

- [ ] **Step 8: 按电量区间模式**

新增两段：`1–20` 金色、`21–80` 蓝。逐条验：
- 20% 以下环为金色；跨过 20% 边界时颜色**跳变**（弧仍平滑动画）；
- 81–100% 未覆盖 → 回退跟随系统；
- 删除「金色」这段后同一电量立即改色；
- 新增区间出现在列表**最上方**并优先生效；
- 连点新增直到 8 段，按钮消失（`MAX_RANGES` 生效）；
- 重启 SystemUI 后区间表还在（经 ContentProvider 读回：日志 `ranges=8`）。

- [ ] **Step 9: 稳定性检查（AGENTS.md 最高优先级）**

```bash
adb -s 6f1adaee logcat -d -s HolePowerRing AndroidRuntime SystemUI | grep -iE "exception|anr|配置刷新失败|环配色"
```
预期：无 `AndroidRuntime` 崩溃、无 ANR、无「配置刷新失败」。特别确认：反复切模式 + 反复拖动区间滑杆不会让 SystemUI 卡死（配置读取全在 `HookPrefs` 的 HandlerThread，绘制端只读 volatile 缓存）。

- [ ] **Step 10: 回写可行性分析报告**

先确认章节号未被占用：

```bash
grep -nE '^## 1[3-9]' "挖孔环形电量LSP模块-可行性分析报告.md"
```
若 §13/§14 仍为空（当前状态：只到 §12），仍按 AGENTS.md 预留不动，直接新增 **§15**，骨架（按 Step 4–9 的实测结果填写横线处）：

```markdown
## 15. 自定义配色模式（2026-10-02，25102RKBEC / 17.03.260226.r）

**模式契约**：`color_mode` 四值互斥（0 跟随系统 / 1 固定单色 / 2 按电池状态 / 3 按电量区间）。
`use_custom_color` 降级为遗留字段，仅在 `color_mode` 缺写时用于推导（true→1），
推导逻辑只在 `ConfigProvider` / `PrefsStore.resolveColorMode` 一处。

**存储**：五路状态色各一个 Int key（`state_color_*`，0 = 未设置）；
区间表一个 String key `level_range_colors` = `1-20:FFFFD700,21-80:FF277AF7`。
String 能跨进程是因为 `HookPrefs.readColumn` 的 `else` 分支已在读 `getString`。
- 实测最大串长（8 段）：____
- 解码策略：逐段 `try/catch` 丢弃畸形片段，上限 8 段

**取色链**：状态优先级沿用系统 `getProgressStatus()`（充电 > 性能 > 省电 > 低电 > 普通）；
未设置槽位回退顺序 = 系统调色板 → `MiuixPalette` 令牌（性能橙 `PERFORMANCE_ORANGE`、
省电琥珀为本模块自定义语义色，miuix 令牌中不存在）。
区间命中用 `state.level` 而非 `animatedLevel`，跨边界颜色明确跳变。

**视觉变更（有意）**：固定单色的底槽由 `0x26` 统一到 `TRACK_ALPHA=0x33`。

**实测结论**：Step 4–9 逐项写实际观察到的日志行与现象；未通过项写清失败表现。
```

- [ ] **Step 11: 同步 AGENTS.md**

Step 5–8 任一项未达预期时，在「已知待验证项」加一行（含未覆盖分支与「仅本机 17.03.260226.r / 插件 18.2.2.2.0 验证」）。全部通过则只补一句：配色模式契约见报告 §15。

- [ ] **Step 12: 收尾**

不要提交。把改动文件清单交用户，由用户决定何时 commit。

---

## Self-Review 记录

- **需求覆盖**：① 按系统电池状态配色 → Task 1（`StateColors`）+ Task 3/4（key 链路）+ Task 5（`batteryStateColor`）+ Task 7（5 行 UI + 模式下拉）；② 按电量区间配色 → Task 1（`ColorRange`/编解码）+ Task 4（String key 跨进程）+ Task 5（`levelRangeColor`）+ Task 8（滑杆列表与增删）；③ 色盘十六进制输入 → Task 2（`HexColor`）+ Task 6（共用弹窗）+ Task 9 Step 6（真机验收）。
- **占位符扫描**：无 TBD/TODO。Task 9 Step 10 的下划线是**待真机实测填入的数据位**，不是实现占位。
- **类型一致性**：
  - `CustomColors.MAX_RANGES`（Task 1 定义，Task 8 两处引用）——命名统一，未使用 `RingConfig.MAX_LEVEL_RANGES`。
  - `StateColors.DEFAULT` / 五个字段名 `normal|low|powerSave|performance|charging`（Task 1 定义；Task 3 字段、Task 4 构造与 `copy`、Task 5 `stateColors.*` 读取、Task 7 `STATE_ROWS` 的 `get/set`）一致。
  - `ColorRange(start, end, color)`（Task 1 定义；Task 5 `colorForLevel`、Task 8 行构造与 `copy(color=)`）一致。
  - `ColorEditing(label, initialArgb, onConfirm)`（Task 6 定义）↔ `buildColorEdit(onEditColor, label, argb, onResult)`（Task 7 Step 5 定义）↔ `editColor(label, argb, onResult)`（Task 7 Step 6 局部函数）三层参数名一致；Task 8 的 `onColorClicked = editColor(...) { ... }` 依赖 `buildColorEdit` 返回 `() -> Unit`，与 `ArrowPreference.onClick` / `Modifier.clickable(onClick=)` 的 `() -> Unit` 契合。
  - key 字符串 7 个（`color_mode`、5 个 `state_color_*`、`level_range_colors`）在 `RingConfig`（Task 3）、`PrefsStore.load`（Task 4 Step 2）、`ConfigProvider.valueOf` + `ALL_KEYS`（Task 4 Step 3）、`HookPrefs.load`（Task 4 Step 4）四处必须逐字相同。
  - `RingRenderer` 内部辅助 `batteryStateColor` / `levelRangeColor` / `Int.orElse`（Task 5 Step 5 定义，Step 3 的 `when` 调用）签名一致；两者入参均为 `(RingConfig, BatteryPalette, RingState, Boolean)`。
- **同包引用已核对**：`ring/` 内部（`RingRenderer`、`RingState` 引用 `CustomColors` / `StateColors` / `ColorRange`）一律不加 import；`ui/SettingsScreen.kt` 与 `core/HookPrefs.kt` 是跨包，Task 4/7/8 已各自列出需要补的 import 行。
