# 灵动岛期间近黑环色保护（变白）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 当「有岛时隐藏圆环」开关关闭且灵动岛正在显示时，若环的进度色是近黑色（在黑岛背景上不可见），替换为白色（保留原透明度）；其余颜色一律不动。

**Architecture:** 新增一个零 Android 依赖的纯函数对象 `IslandColorGuard`（可 JVM 单测），在 `RingState` 上把已有的 `islandShowing` 状态暴露为只读属性，`RingRenderer.draw` 在四模式取色 `when` 之后、底槽色推导之前套用该守卫。底槽色与淡出动画由 `rawProgress` 派生，自动跟随守卫结果，无需单独处理。

**Tech Stack:** Kotlin（JDK 17+，AGP 9.x）、JUnit 4 JVM 单测（与 `CustomColorsTest` 同目录同风格）、Xposed Hook 侧改动全部在 SystemUI 进程内、纯内存计算无新增 Hook。

**需求澄清结论（已与用户确认，2026-10-02）：**
- 触发范围：**仅黑色时改白**——岛显示期间只有环算出来的颜色过暗才改白，自定义配色、充电蓝、低电红等一律不动（四种 `color_mode` 都参与守卫，因为守卫作用在最终算出的颜色上）。
- 黑色判定：**深色都算**——R/G/B 三通道均 ≤ 64/255 视为「在黑岛上看不清」的近黑色（覆盖纯黑、深灰与反色动画的深色中间值）；替换时**保留原 alpha**。

**约定提醒：**
- 遵循本仓库纪律：执行全程**不做 git 提交**（用户偏好），验收后由用户决定提交。
- 本改动不新增 Hook，不触碰 `ClassLoader.loadClass` 回调，不改动 `apks/`。

---

## 文件结构

| 文件 | 动作 | 职责 |
|---|---|---|
| `HolePowerRing/app/src/main/java/com/powerring/hole/ring/IslandColorGuard.kt` | 新建 | 纯函数：岛显示期间近黑色→白色（保留 alpha），零 Android 依赖 |
| `HolePowerRing/app/src/test/java/com/powerring/hole/ring/IslandColorGuardTest.kt` | 新建 | 守卫逻辑的 JVM 单测 |
| `HolePowerRing/app/src/main/java/com/powerring/hole/ring/RingState.kt` | 修改（约 55-57 行） | `islandShowing` 由 private 改为公开只读 |
| `HolePowerRing/app/src/main/java/com/powerring/hole/ring/RingRenderer.kt` | 修改（约 82、118 行） | 取色 `when` 结果套用守卫，底槽色用守卫后的颜色派生 |
| `挖孔环形电量LSP模块-可行性分析报告.md` | 修改 | 追加 §16 记录行为契约（§13/§14 编号已被预留/空置，勿占用 §13） |

---

### Task 1: IslandColorGuard 纯函数（TDD）

**Files:**
- Create: `HolePowerRing/app/src/test/java/com/powerring/hole/ring/IslandColorGuardTest.kt`
- Create: `HolePowerRing/app/src/main/java/com/powerring/hole/ring/IslandColorGuard.kt`

- [ ] **Step 1: 写失败的单测**

创建 `HolePowerRing/app/src/test/java/com/powerring/hole/ring/IslandColorGuardTest.kt`，完整内容：

```kotlin
package com.powerring.hole.ring

import org.junit.Assert.assertEquals
import org.junit.Test

class IslandColorGuardTest {

    @Test
    fun opaqueBlackBecomesWhiteWhenIslandShowing() {
        assertEquals(
            IslandColorGuard.WHITE,
            IslandColorGuard.ensureVisible(0xFF000000.toInt(), islandShowing = true, collapseOnIsland = false),
        )
    }

    @Test
    fun darkGrayBecomesWhiteWhenIslandShowing() {
        // 反色动画的深色中间值（≈#262626）同样不可见，应改白
        assertEquals(
            IslandColorGuard.WHITE,
            IslandColorGuard.ensureVisible(0xFF262626, islandShowing = true, collapseOnIsland = false),
        )
    }

    @Test
    fun channelBoundaryIsInclusive() {
        // 三通道均恰好 64：近黑，改白
        assertEquals(
            IslandColorGuard.WHITE,
            IslandColorGuard.ensureVisible(0xFF404040, islandShowing = true, collapseOnIsland = false),
        )
        // 任一通道 65：不再算近黑，保持原色
        val justAbove = 0xFF414040
        assertEquals(
            justAbove,
            IslandColorGuard.ensureVisible(justAbove, islandShowing = true, collapseOnIsland = false),
        )
    }

    @Test
    fun preservesOriginalAlpha() {
        // 半透明黑 → 半透明白，不借机变成不透明
        assertEquals(
            0x80FFFFFF.toInt(),
            IslandColorGuard.ensureVisible(0x80000000, islandShowing = true, collapseOnIsland = false),
        )
    }

    @Test
    fun lightAndSaturatedColorsUntouched() {
        val cases = intArrayOf(
            IslandColorGuard.WHITE,           // 已经是白
            0xFFFFFFFF.toInt(),                // 同上
            0xFF277AF7.toInt(),                // 充电蓝（default customColor）
            0xFFE53935.toInt(),                // 低电红
            0xFFFFD700.toInt(),                // 金黄
            0xFF808080.toInt(),                // 中灰
        )
        for (c in cases) {
            assertEquals(c, IslandColorGuard.ensureVisible(c, islandShowing = true, collapseOnIsland = false))
        }
    }

    @Test
    fun disabledWhenIslandNotShowing() {
        assertEquals(
            0xFF000000.toInt(),
            IslandColorGuard.ensureVisible(0xFF000000.toInt(), islandShowing = false, collapseOnIsland = false),
        )
    }

    @Test
    fun disabledWhenCollapseOnIslandEnabled() {
        // 「有岛时隐藏圆环」开着时环本就收起，守卫不介入
        assertEquals(
            0xFF000000.toInt(),
            IslandColorGuard.ensureVisible(0xFF000000.toInt(), islandShowing = true, collapseOnIsland = true),
        )
    }
}
```

- [ ] **Step 2: 跑测试确认编译失败**

```bash
cd HolePowerRing
./gradlew.bat testDebugUnitTest --tests "com.powerring.hole.ring.IslandColorGuardTest"
```

Expected: FAIL，报 `Unresolved reference: IslandColorGuard`（Kotlin 编译错误）。

- [ ] **Step 3: 写最小实现**

创建 `HolePowerRing/app/src/main/java/com/powerring/hole/ring/IslandColorGuard.kt`，完整内容：

```kotlin
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
```

- [ ] **Step 4: 跑测试确认全绿**

```bash
cd HolePowerRing
./gradlew.bat testDebugUnitTest --tests "com.powerring.hole.ring.IslandColorGuardTest"
```

Expected: BUILD SUCCESSFUL，7 个用例全部 PASS。

顺带回归既有用例（确认没碰坏配色编解码）：

```bash
./gradlew.bat testDebugUnitTest
```

Expected: 全部 PASS（含 `CustomColorsTest`、`ConfigJsonTest`、`HexColorTest`）。

- [ ] **Step 5: 提交**

本任务**不提交**（用户偏好：全程不提交，验收后统一处理）。仅确认工作区只有上述两个新文件：

```bash
git status --short
```

Expected: 两行 `??`，路径为 Step 1/Step 3 创建的两个文件。

---

### Task 2: RingState 暴露 islandShowing + RingRenderer 接入守卫

**Files:**
- Modify: `HolePowerRing/app/src/main/java/com/powerring/hole/ring/RingState.kt:55-57`
- Modify: `HolePowerRing/app/src/main/java/com/powerring/hole/ring/RingRenderer.kt:82-124`

说明：`RingRenderer.draw` 依赖 `Canvas/View`，真机才有意义，不写 JVM 单测（Step 3-4 用编译 + 既有单测回归验证，Task 4 真机验收行为）。

- [ ] **Step 1: RingState 把 islandShowing 改为公开只读**

`RingState.kt` 第 55-57 行现为：

```kotlin
    /** 最近一次上报的灵动岛显示状态（由 IslandVisibilityHook 驱动） */
    @Volatile
    private var islandShowing: Boolean = false
```

改为（只动可见性，写入仍只经 `setIslandShowing`）：

```kotlin
    /** 最近一次上报的灵动岛显示状态（由 IslandVisibilityHook 驱动，渲染侧只读） */
    @Volatile
    var islandShowing: Boolean = false
        private set
```

无需其它改动：`setIslandShowing` 在岛状态翻转时已调用 `invalidateAll()`（经 `animateCollapseTo` 的直落分支），环窗口会立刻重绘，守卫在下一帧生效/失效。

- [ ] **Step 2: RingRenderer 在取色后、底槽前套用守卫**

`RingRenderer.kt` 第 79-122 行，把：

```kotlin
        // 四种模式互斥；分支只决定「进度色从哪来」，几何与动画一律不受影响。
        val palette = state.batteryPalette
        val fromUserOrSystem: Boolean
        val rawProgress: Int = when (config.colorMode) {
```

改为：

```kotlin
        // 四种模式互斥；分支只决定「进度色从哪来」，几何与动画一律不受影响。
        val palette = state.batteryPalette
        val fromUserOrSystem: Boolean
        val modeColor: Int = when (config.colorMode) {
```

（`when` 块本体一行不改。）

在 `when` 块结束（原第 115 行 `}`）与底槽注释之间插入守卫，即把：

```kotlin
        // 来自用户配置或系统调色板时，底槽取进度色的低透明度版本，视觉更统一；
        // 只有退回内置令牌时才用 sliderBackground 槽色。
        val rawTrack = if (fromUserOrSystem) {
            (TRACK_ALPHA shl 24) or (rawProgress and 0x00FFFFFF)
        } else {
            MiuixPalette.trackColor(darkIcons)
        }
        val progressColor = scaleAlpha(rawProgress, expand)
        val trackColor = scaleAlpha(rawTrack, expand)
```

改为：

```kotlin
        // 灵动岛保护：岛是黑色药丸，环色跟到近黑就不可见，改白（保留 alpha）；
        // 底槽与淡出都从守卫后的颜色派生，无需单独处理。见 IslandColorGuard。
        val rawProgress = IslandColorGuard.ensureVisible(
            modeColor,
            islandShowing = state.islandShowing,
            collapseOnIsland = config.collapseOnIsland,
        )
        // 来自用户配置或系统调色板时，底槽取进度色的低透明度版本，视觉更统一；
        // 只有退回内置令牌时才用 sliderBackground 槽色。
        val rawTrack = if (fromUserOrSystem) {
            (TRACK_ALPHA shl 24) or (rawProgress and 0x00FFFFFF)
        } else {
            MiuixPalette.trackColor(darkIcons)
        }
        val progressColor = scaleAlpha(rawProgress, expand)
        val trackColor = scaleAlpha(rawTrack, expand)
```

- [ ] **Step 3: 编译验证**

```bash
cd HolePowerRing
./gradlew.bat assembleDebug
```

Expected: BUILD SUCCESSFUL；产物 `app/build/outputs/apk/debug/app-debug.apk` 生成。

- [ ] **Step 4: 全量单测回归**

```bash
./gradlew.bat testDebugUnitTest
```

Expected: 全部 PASS（守卫纯函数 + 既有 14 条配色相关用例）。

- [ ] **Step 5: 提交**

本任务**不提交**（同 Task 1 Step 5 约定）。

---

### Task 3: 文档回写（可行性分析报告 §16）

**Files:**
- Modify: `挖孔环形电量LSP模块-可行性分析报告.md`（在 `## 15. 自定义配色模式契约（2026-10-02）` 一节末尾、`## 附录 A` 之前插入）

- [ ] **Step 1: 追加 §16**

在 `## 附录 A：关键类索引（逆向实证）` 标题之前插入：

```markdown
## 16. 灵动岛期间近黑环色保护（2026-10-02）

**背景**：「有岛时隐藏圆环」（`collapse_on_island`）关闭时，灵动岛显示期间环保持展开；
跟随系统取色在浅色背景下为黑色，与黑色岛体融为一体，电量不可读。

**契约**（`ring/IslandColorGuard.kt`，渲染侧在 `RingRenderer` 四模式取色之后统一套用）：

- 生效条件：`islandShowing == true` 且 `collapseOnIsland == false`；
- 判定：ARGB 的 R/G/B 三通道均 ≤ 64/255 视为近黑（覆盖纯黑、深灰、反色动画深色中间值）；
- 动作：替换为白色并**保留原 alpha**（`0x80000000 → 0x80FFFFFF`）；
- 不生效：其余任何颜色（充电蓝、低电红、四模式自定义色、已是白色）一律不动；
- 岛状态信号源沿用 §12 的宿主侧 `MiuiBatteryMeterView.updateIslandShowing`，无新增 Hook。

**验证状态**：JVM 单测已覆盖判定与边界（通道 64/65、alpha 保留、两开关组合）；真机六项验收见 Task 4 清单，**尚未执行**。

计划文档：`docs/superpowers/plans/2026-10-02-island-dark-ring-white.md`
```

- [ ] **Step 2: 确认插入位置**

```bash
grep -n "^## " "挖孔环形电量LSP模块-可行性分析报告.md"
```

Expected: 新 `## 16. 灵动岛期间近黑环色保护（2026-10-02）` 位于 `## 15.` 与 `## 附录 A` 之间。

---

### Task 4: 真机验收（人工，需设备 25102RKBEC）

**Files:** 无代码改动。

- [ ] **Step 1: 安装**

设备已连接时：

```bash
cd HolePowerRing
./gradlew.bat installDebug
adb shell killall com.android.systemui
```

（若模块已启用过 LSPosed，仅需重启 SystemUI；新装模块先在 LSPosed 管理器确认作用域含 `com.android.systemui`。）

- [ ] **Step 2: 按下表逐项验收**

前置：设置页关闭「有岛时隐藏圆环」（`collapse_on_island` 关），配色模式「跟随系统」。

| # | 场景 | 预期 |
|---|---|---|
| 1 | 浅色背景（状态栏图标为黑）+ 触发灵动岛 | 环变白，电量清晰可读 |
| 2 | 深色背景（状态栏图标为白）+ 岛显示 | 环保持白色，无任何改动 |
| 3 | 岛消失后回到浅色背景 | 环恢复黑色（跟随系统），过渡正常 |
| 4 | 岛显示中切换浅/深背景（反色动画） | 无闪烁，深色中间值期间环仍可见 |
| 5 | 岛显示中分别验证四种配色模式的非黑自定义色（固定蓝、五路红、区间黄等） | 颜色完全不被守卫改动 |
| 6 | 「有岛时隐藏圆环」打开 + 触发岛 | 环照常收起，行为与改动前一致（守卫不介入） |

- [ ] **Step 3: 抓日志佐证**

```bash
adb logcat -s HolePowerRing | grep -E "环配色|灵动岛显隐驱动"
```

Expected: 岛出现时可见「灵动岛显隐驱动: showing=true」，随后「环配色」日志中的 color 由黑色 `#ff000000` 变为 `#ffffffff`；岛消失后恢复。

- [ ] **Step 4: 验收通过后**

回报结果；是否提交、以及把 §16「验证状态」改为真机已验证，由用户决定（全程不自动提交）。
