# 电量环跟随系统电池图标颜色 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 未开启自定义颜色时，环形电量的五种状态（普通 / 低电量 / 省电模式 / 性能模式 / 充电中）全部改用系统原生电池图标的真实颜色；其中"普通"态跟随状态栏自适应反色连续变化，且过渡动画与状态栏同步、不突兀。

**Architecture:** 新增 `hook/BatteryColorHook`，Hook 系统状态栏电池图标 View（`MiuiBatteryMeterIconView`），在其 `onDarkChangeInternal()`（系统真正算出图标颜色的位置）之后反射读取 `mLightColor`/`mDarkColor`/`mDarkIntensity` 与四个语义色字段，连同 `mLow`/`mPowerSave`/`mPerformanceMode`/`mCharging` 状态位组成 `SystemBatteryColors` 快照交给 `RingState`。`RingState` 用 `ArgbEvaluator` 按 `mDarkIntensity`（系统自己的反色动画时钟）算出普通态目标色；因为反色强度逐帧连续推进，环色与状态栏**共用同一时间轴**，无需二次动画；仅在"强度不变但浅/深目标色被换掉"（主题/tint 区域切换）时才补一次等长 Argb 过渡。`RingRenderer` 在 `useCustomColor == false && palette.ready` 时优先使用调色板，否则回退现有 `MiuixPalette` 固定语义色——Hook 失效时行为与今天完全一致。

**Tech Stack:** Kotlin、Xposed API（`xposedstub` 仅 compileOnly）、`android.animation.ArgbEvaluator` / `ValueAnimator`、miuix（设置页文案）。

---

## 执行约定（用户既定偏好，承自 `2026-10-01-immersive-ring-collapse.md`）

- **不执行 `git commit`**，不做任何提交；改动留在工作区由用户自行 review 与提交。
- 仓库**没有自动化测试**（见 `AGENTS.md`），因此本计划用「`gradlew.bat assembleDebug` 编译通过 + 真机 logcat 核对」替代 TDD 的红/绿步骤。不要凭空捏造测试文件。
- adb 只读命令（`logcat`）可直接执行；安装 APK / 重启 SystemUI 优先走设置页内置的「重启系统界面」按钮，需要 adb 交互类操作时先征得用户同意。
- 所有 Hook 回调与反射读取必须 `try/catch (Throwable)` 并走 `ModuleLog`，绝不拖垮 SystemUI。
- 不要改 `apks/`、`local.properties`、`app/build/`、`work/__pycache__/`。

## 逆向结论（执行者必读，均已在 `work/sysui/classes2.dex`、`classes3.dex` 实证）

用 `work/dump_class.py` 与本次新增的 `work/method_refs.py` 复核过，签名以 **系统界面 17.03.260226.r** 为准。

| 事实 | 证据 |
|---|---|
| 状态栏电池根 View：`com.android.systemui.statusbar.views.MiuiBatteryMeterView`，内含 `public final MiuiBatteryMeterIconView mBatteryIconView` | `dump_class.py sysui com.android.systemui.statusbar.views.MiuiBatteryMeterView` |
| **图标真正上色的位置**：`MiuiBatteryMeterIconView.onDarkChangeInternal()`，方法体内 `call: ArgbEvaluator.evaluate` + `call: Drawable.setTintList` | `method_refs.py … MiuiBatteryMeterIconView onDarkChangeInternal` |
| 语义色字段（int）：`mBatteryLowColor`、`mBatteryPowerSaveColor`、`mBatteryPerformanceModeColor`、`mBatteryChargingColor` | 同上 |
| 反色字段：`mLightColor`（int）、`mDarkColor`（int）、`mDarkIntensity`（float）、`mTintColor`（int）、`mUseTint`（boolean）、`mDark`（int） | 同上 |
| 状态位（boolean）：`mLow`、`mPowerSave`、`mPerformanceMode`、`mCharging`、`mQuickCharging` | 同上 |
| 反色动画时钟：`LightBarTransitionsController.animateIconTint(FJJZ)` 用 `ValueAnimator` + `Interpolators.LINEAR` 推进 `mDarkIntensity`，时长来自 `DarkIntensityApplier.getTintAnimationDuration()`，另有常量 `DEFAULT_TINT_ANIMATION_DURATION` | `method_refs.py … LightBarTransitionsController animateIconTint/setIconsDark` |
| `MiuiBatteryMeterView.onDarkChanged(ArrayList, float, int)` 存 `mDarkIntensity`/`mTintAreas`/`mTintColor`，随后 `onDarkChangedInternal()` 用 `DarkIconDispatcherExt.getTint/getDarkIntensity` 上色 | `method_refs.py … MiuiBatteryMeterView` |
| 上述类**只在 SystemUI 宿主 dex**，`work/plugin/` 中不存在 | `find_classes.py plugin MiuiStatusBatteryContainer` → 0 命中 |

**Hook 纪律（承自 `ImmersiveProbeHook` 的教训）：** 只 Hook **类自身声明**的方法。`onDarkChanged`、`onDarkChangeInternal` 都在目标类的 `dump_class` 列表里，不是继承来的，`findAndHookMethod` 不会退化到父类。不要去 Hook `View.setVisibility` / `ImageView.setImageTintList` 之类基类方法。

---

## 文件结构总览

| 文件 | 操作 | 职责 |
|---|---|---|
| `HolePowerRing/xposedstub/src/main/java/de/robv/android/xposed/XposedHelpers.java` | 修改 | 补 `getFloatField` 编译桩（真实 Xposed API 本就有） |
| `HolePowerRing/app/src/main/java/com/powerring/hole/ring/BatteryPalette.kt` | 创建 | `BatteryPalette`（渲染用调色板）+ `SystemBatteryColors`（Hook 原始读数） |
| `HolePowerRing/app/src/main/java/com/powerring/hole/ring/RingState.kt` | 修改 | 持有调色板、普通态当前色、反色过渡动画 |
| `HolePowerRing/app/src/main/java/com/powerring/hole/ring/RingRenderer.kt` | 修改 | 选色优先级改为「自定义 > 系统调色板 > Miuix 回退」，补性能模式分支 |
| `HolePowerRing/app/src/main/java/com/powerring/hole/hook/BatteryColorHook.kt` | 创建 | 反射读取系统电池图标颜色与状态位 |
| `HolePowerRing/app/src/main/java/com/powerring/hole/hook/SystemUiHooks.kt` | 修改 | 注册 `BatteryColorHook` |
| `HolePowerRing/app/src/main/java/com/powerring/hole/ui/SettingsScreen.kt` | 修改 | 自定义颜色开关的副标题改为"跟随系统电池图标颜色" |
| `挖孔环形电量LSP模块-可行性分析报告.md` | 修改 | 新增 §12 记录取色链路与签名（AGENTS.md 强制要求） |
| `work/method_refs.py` | 已创建（本次调研） | DEX 方法体字段/方法引用扫描器，供换机型时复核签名 |

---

### Task 1: 编译桩补 `getFloatField`

**Files:**
- Modify: `HolePowerRing/xposedstub/src/main/java/de/robv/android/xposed/XposedHelpers.java:44-46`

- [ ] **Step 1: 在 `getBooleanField` 之后插入浮点字段读取桩**

在 `public static boolean getBooleanField(Object obj, String fieldName) { return false; }` 这一行之后、`callMethod` 之前插入：

```java
    public static float getFloatField(Object obj, String fieldName) {
        return 0f;
    }
```

改完后该区域应长这样：

```java
    public static boolean getBooleanField(Object obj, String fieldName) {
        return false;
    }

    public static float getFloatField(Object obj, String fieldName) {
        return 0f;
    }

    public static Object callMethod(Object obj, String methodName, Object... args) {
        return null;
    }
```

> 说明：`getFloatField(Object, String)` 是真实 XposedHelpers API 82 的方法，这里只是让编译桩与真实签名对齐；桩**仅 compileOnly**，运行时由 LSPosed 提供真实实现，方法体返回 0 不参与运行。

- [ ] **Step 2: 编译验证**

Run（Windows）:
```powershell
cd HolePowerRing
.\gradlew.bat :xposedstub:assembleDebug
```
Expected: `BUILD SUCCESSFUL`

---

### Task 2: 新增调色板数据类

**Files:**
- Create: `HolePowerRing/app/src/main/java/com/powerring/hole/ring/BatteryPalette.kt`

- [ ] **Step 1: 创建文件**

写入以下完整内容：

```kotlin
package com.powerring.hole.ring

/**
 * 环形电量使用的电池调色板。
 *
 * 所有颜色都来自系统状态栏原生电池图标（MiuiBatteryMeterIconView），
 * 由 [com.powerring.hole.hook.BatteryColorHook] 反射读出后写入 [RingState]。
 *
 * [ready] 为 false 表示还没读到系统颜色（Hook 没装上 / 机型类名不同 /
 * 图标 View 尚未创建），此时 [RingRenderer] 回退到 [MiuixPalette] 的固定语义色，
 * 行为与改造前完全一致。
 */
data class BatteryPalette(
    /** 是否已成功读到系统电池图标颜色 */
    val ready: Boolean = false,
    /** 普通态目标色（已按 darkIntensity 在浅色/深色前景之间插值） */
    val normal: Int = 0,
    /** 低电量色 */
    val low: Int = 0,
    /** 省电模式色 */
    val powerSave: Int = 0,
    /** 性能模式色 */
    val performance: Int = 0,
    /** 充电中色 */
    val charging: Int = 0,
    /** 当前是否处于充电态（含快充） */
    val chargingNow: Boolean = false,
    /** 当前是否处于性能模式 */
    val performanceNow: Boolean = false,
    /** 当前是否处于省电模式 */
    val powerSaveNow: Boolean = false,
    /** 当前是否为低电量 */
    val lowNow: Boolean = false,
    /** 状态栏反色强度 0..1，仅用于日志与诊断 */
    val darkIntensity: Float = 1f,
) {
    companion object {
        val EMPTY = BatteryPalette()
    }
}

/**
 * Hook 侧从系统电池图标读到的原始值。
 *
 * [light] / [dark] 是系统自己的浅色 / 深色两套前景色；
 * [darkIntensity] 是状态栏反色动画的进度（0 = 纯浅色，1 = 纯深色），
 * 它逐帧连续推进，是让环色与状态栏共用同一条时间轴的关键。
 */
data class SystemBatteryColors(
    /** 浅色前景（浅底深图标） */
    val light: Int = 0,
    /** 深色前景（深底浅图标） */
    val dark: Int = 0,
    /** tint 区域色，useTint 为真时优先于 light/dark */
    val tint: Int = 0,
    val useTint: Boolean = false,
    /** 低电量色 */
    val low: Int = 0,
    /** 省电模式色 */
    val powerSave: Int = 0,
    /** 性能模式色 */
    val performance: Int = 0,
    /** 充电中色 */
    val charging: Int = 0,
    val chargingNow: Boolean = false,
    val performanceNow: Boolean = false,
    val powerSaveNow: Boolean = false,
    val lowNow: Boolean = false,
    val darkIntensity: Float = 1f,
)
```

- [ ] **Step 2: 编译验证**

```powershell
cd HolePowerRing
.\gradlew.bat :app:compileDebugKotlin
```
Expected: `BUILD SUCCESSFUL`

---

### Task 3: RingState 接入调色板与反色过渡动画

**Files:**
- Modify: `HolePowerRing/app/src/main/java/com/powerring/hole/ring/RingState.kt:3-10`（import）
- Modify: `HolePowerRing/app/src/main/java/com/powerring/hole/ring/RingState.kt:72` 附近（新增字段）
- Modify: `HolePowerRing/app/src/main/java/com/powerring/hole/ring/RingState.kt:118` 附近（新增方法）

- [ ] **Step 1: 补 import**

文件头当前是：

```kotlin
import android.animation.ValueAnimator
import android.content.Context
import android.view.animation.DecelerateInterpolator
```

改为：

```kotlin
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.os.SystemClock
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
```

- [ ] **Step 2: 新增状态字段**

在 `private val COLLAPSE_DURATION_MS = 260L` 这一行之后插入：

```kotlin

    // ---- 系统电池图标调色板（跟随原生图标颜色） ----

    /**
     * 系统电池图标调色板；[BatteryPalette.ready] 为 false 时
     * [RingRenderer] 回退到 [MiuixPalette] 的固定语义色。
     */
    @Volatile
    var batteryPalette: BatteryPalette = BatteryPalette.EMPTY
        private set

    /**
     * 普通态**当前实际显示**的颜色。
     * 与 [batteryPalette.normal] 的区别：本字段可能处于过渡动画中间值，
     * [RingRenderer] 在普通态分支读的是本字段。
     */
    @Volatile
    var normalColor: Int = 0xFFFFFFFF.toInt()
        private set

    /** 反色过渡时长，优先取系统 LightBarTransitionsController 的常量，保证节奏一致。 */
    @Volatile
    var tintAnimationDurationMs: Long = DEFAULT_TINT_DURATION_MS
        private set

    private var colorAnimator: ValueAnimator? = null
    private val argbEvaluator = ArgbEvaluator()
    private var lastSystemColors: SystemBatteryColors? = null
    private var lastDarkIntensity: Float = Float.NaN

    /** 诊断日志节流：反色动画期间 onDarkChanged 每帧回调，逐帧打日志会刷屏。 */
    private var lastColorLogMs = 0L
```

- [ ] **Step 3: 新增写入方法**

在 `fun setScreenOn(on: Boolean) { ... }` 这一整段之后（即 `ModuleLog.i("屏幕状态: screenOn=$on")` 与对应右花括号之后）插入：

```kotlin

    /** 由 BatteryColorHook 在读到系统常量后调用；非法值忽略。 */
    fun setTintAnimationDuration(ms: Long) {
        if (ms > 0) tintAnimationDurationMs = ms
    }

    /**
     * 写入系统电池图标的颜色与状态位。
     *
     * 普通态颜色的过渡分两种情况，这是"跟手又不突兀"的关键：
     * - [SystemBatteryColors.darkIntensity] 变化：说明系统反色动画正在推进，
     *   此时算出的目标色本身就是连续的，直接跟随，**不叠加**二次动画
     *   （叠加会慢一拍，反而与状态栏错位）；
     * - darkIntensity 没变、但浅/深色目标被换掉（主题切换、tint 区域变化）：
     *   用与系统相同长度的 Argb 过渡补一次，避免硬切。
     */
    fun setSystemBatteryColors(colors: SystemBatteryColors) {
        val last = lastSystemColors
        if (last == colors) return
        lastSystemColors = colors

        val intensity = colors.darkIntensity.coerceIn(0f, 1f)
        val intensityMoved = lastDarkIntensity.isNaN() || intensity != lastDarkIntensity
        lastDarkIntensity = intensity

        // 与系统 onDarkChangeInternal 一致：useTint 时用 tint 色作浅色端
        val base = if (colors.useTint && colors.tint != 0) colors.tint else colors.light
        val target = argbEvaluator.evaluate(intensity, base, colors.dark) as Int

        batteryPalette = BatteryPalette(
            ready = true,
            normal = target,
            low = colors.low,
            powerSave = colors.powerSave,
            performance = colors.performance,
            charging = colors.charging,
            chargingNow = colors.chargingNow,
            performanceNow = colors.performanceNow,
            powerSaveNow = colors.powerSaveNow,
            lowNow = colors.lowNow,
            darkIntensity = intensity,
        )

        if (intensityMoved) {
            colorAnimator?.cancel()
            colorAnimator = null
            normalColor = target
        } else {
            animateNormalColorTo(target)
        }

        // 反色动画期间本方法每帧被调用，日志必须节流，否则一秒能刷出几十行
        val now = SystemClock.uptimeMillis()
        if (now - lastColorLogMs >= 1000L) {
            lastColorLogMs = now
            ModuleLog.i(
                "环色跟随系统电池图标: di=$intensity normal=#${hex(target)} " +
                    "low=#${hex(colors.low)} save=#${hex(colors.powerSave)} " +
                    "perf=#${hex(colors.performance)} charge=#${hex(colors.charging)} " +
                    "flags(charge=${colors.chargingNow},perf=${colors.performanceNow}," +
                    "save=${colors.powerSaveNow},low=${colors.lowNow})",
            )
        }
        invalidateAll()
    }

    /** 照抄 startLevelAnimation 的「取消旧动画 → 平滑过渡」模式。 */
    private fun animateNormalColorTo(target: Int) {
        val start = normalColor
        colorAnimator?.let { if (it.isRunning) it.cancel() }
        if (start == target) return
        colorAnimator = ValueAnimator.ofObject(argbEvaluator, start, target).apply {
            duration = tintAnimationDurationMs
            // 系统 LightBarTransitionsController.animateIconTint 用的是 Interpolators.LINEAR，
            // 曲线形状保持一致，环与状态栏图标才会严丝合缝地同步变色
            interpolator = LinearInterpolator()
            addUpdateListener {
                normalColor = it.animatedValue as Int
                invalidateAll()
            }
        }.also { it.start() }
    }

    private fun hex(color: Int): String = String.format("%08X", color)
```

- [ ] **Step 4: 补常量**

`RingState` 当前**没有** `companion object`。在文件最后一个右花括号（`invalidateAll()` 结束处）之前插入：

```kotlin

    private companion object {
        /** 系统常量读不到时的兜底反色过渡时长 */
        const val DEFAULT_TINT_DURATION_MS = 250L
    }
```

- [ ] **Step 5: 编译验证**

```powershell
cd HolePowerRing
.\gradlew.bat :app:compileDebugKotlin
```
Expected: `BUILD SUCCESSFUL`，无 "unresolved reference" 报错。

---

### Task 4: RingRenderer 改用系统调色板

**Files:**
- Modify: `HolePowerRing/app/src/main/java/com/powerring/hole/ring/RingRenderer.kt:23`（新增常量）
- Modify: `HolePowerRing/app/src/main/java/com/powerring/hole/ring/RingRenderer.kt:70-83`（选色段）

- [ ] **Step 1: 新增底槽透明度常量**

在 `private const val LOW_BATTERY_THRESHOLD = 15` 之后插入：

```kotlin

    /**
     * 跟随系统电池图标颜色时，底槽取进度色该比例的透明度。
     * 与自定义色分支的 0x26（约 15%）保持同一量级，视觉上与原生图标一致。
     */
    private const val TRACK_ALPHA = 0x33
```

- [ ] **Step 2: 替换选色段**

把 `RingRenderer.draw()` 中这一段：

```kotlin
        // 颜色选择：自定义色（覆盖一切）> 低电红 > 充电蓝 > 省电琥珀 > 跟随状态栏图标色
        val progressColor = scaleAlpha(when {
            config.useCustomColor -> config.customColor
            state.level <= LOW_BATTERY_THRESHOLD -> MiuixPalette.errorColor(darkIcons)
            state.charging -> MiuixPalette.primaryColor(darkIcons)
            state.powerSave -> MiuixPalette.POWER_SAVE_AMBER
            else -> MiuixPalette.foregroundColor(darkIcons)
        }, expand)
        // 自定义色时底槽也用该色的低透明度版本（alpha ~15%），视觉更统一
        val trackColor = scaleAlpha(if (config.useCustomColor) {
            (0x26 shl 24) or (progressColor and 0x00FFFFFF)
        } else {
            MiuixPalette.trackColor(darkIcons)
        }, expand)
```

整体替换为：

```kotlin
        // 颜色优先级：自定义色（覆盖一切）> 系统电池图标调色板 > 内置语义色回退。
        // followSystem 为假时行为与改造前逐像素一致（Hook 失效 / 开启自定义色的兜底）。
        val palette = state.batteryPalette
        val followSystem = !config.useCustomColor && palette.ready
        val rawProgress = when {
            config.useCustomColor -> config.customColor
            // 顺序与系统 MiuiBatteryMeterIconView.getProgressStatus() 的状态集一致：
            // 充电（含快充）> 性能模式 > 省电模式 > 低电量 > 普通
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
        // 自定义色 / 跟随系统色时，底槽取进度色的低透明度版本，视觉更统一
        val rawTrack = when {
            config.useCustomColor -> (0x26 shl 24) or (rawProgress and 0x00FFFFFF)
            followSystem -> (TRACK_ALPHA shl 24) or (rawProgress and 0x00FFFFFF)
            else -> MiuixPalette.trackColor(darkIcons)
        }
        val progressColor = scaleAlpha(rawProgress, expand)
        val trackColor = scaleAlpha(rawTrack, expand)
```

> 注意：这里刻意**先算未乘 `expand` 的 `rawProgress`/`rawTrack`，再各自 `scaleAlpha`**。
> 若沿用旧写法从已淡出的 `progressColor` 派生底槽，`expand` 会被乘两次，收缩时底槽会比进度弧淡得更快。

- [ ] **Step 3: 确认诊断日志仍可用**

`draw()` 中原有的 `if (!diagLogged) { ... }` 段落引用 `progressColor`，未受影响，无需改动。

- [ ] **Step 4: 编译验证**

```powershell
cd HolePowerRing
.\gradlew.bat :app:compileDebugKotlin
```
Expected: `BUILD SUCCESSFUL`

---

### Task 5: 新增 BatteryColorHook

**Files:**
- Create: `HolePowerRing/app/src/main/java/com/powerring/hole/hook/BatteryColorHook.kt`

- [ ] **Step 1: 创建文件**

写入以下完整内容：

```kotlin
package com.powerring.hole.hook

import android.os.Handler
import android.os.Looper
import com.powerring.hole.core.ModuleLog
import com.powerring.hole.ring.RingState
import com.powerring.hole.ring.SystemBatteryColors
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference
import java.util.ArrayList

/**
 * 让环形电量跟随系统原生电池图标的颜色。
 *
 * 取色链路（类名/字段名见可行性分析报告 §12，仅适配系统界面 17.03.260226.r）：
 * ```
 * LightBarTransitionsController.animateIconTint  // 反色动画时钟，LINEAR 插值
 *   └─ MiuiBatteryMeterView.onDarkChanged(ArrayList, float, int)  // 每帧回调 darkIntensity
 *        └─ MiuiBatteryMeterIconView.onDarkChangeInternal()        // 系统在此算出最终颜色
 * ```
 * 本 Hook 在 `onDarkChangeInternal()` **之后**读字段——此刻系统的
 * `mLightColor / mDarkColor / mDarkIntensity / mBattery*Color / mLow …` 全部是最新值，
 * 直接复用即可，不必重算系统那套优先级。
 *
 * 所有反射与回调都包 try/catch：任何一步失败都只是让环退回内置语义色，
 * 绝不把异常抛进 SystemUI。
 */
object BatteryColorHook {

    private const val METER_VIEW =
        "com.android.systemui.statusbar.views.MiuiBatteryMeterView"
    private const val ICON_VIEW =
        "com.android.systemui.statusbar.views.MiuiBatteryMeterIconView"
    private const val LIGHT_BAR =
        "com.android.systemui.statusbar.phone.LightBarTransitionsController"

    /** 读不到系统常量时的兜底反色过渡时长 */
    private const val DEFAULT_TINT_DURATION_MS = 250L

    /**
     * 兜底轮询间隔。系统回调正常时每次 publish 都会被数据类相等性挡掉、
     * 不触发任何重绘，2 秒一次的开销可以忽略；它的作用是覆盖
     * 「图标 View 在 Hook 装上之前就已经是当前状态、之后不再回调」的情况。
     */
    private const val POLL_INTERVAL_MS = 2000L

    @Volatile
    private var installed = false

    @Volatile
    private var pollScheduled = false

    /** 状态栏电池根 View（弱引用，View 销毁后自动失效） */
    private var meterRef: WeakReference<Any>? = null

    /** 真正上色的图标 View（弱引用） */
    private var iconRef: WeakReference<Any>? = null

    /** 状态位组合，用于日志去重（darkIntensity 每帧变，不能进 key） */
    private var lastFlagKey = ""

    private var firstDumpLogged = false

    /** 「颜色尚未就绪」只报一次，避免兜底轮询刷屏 */
    @Volatile
    private var notReadyLogged = false

    private val handler = Handler(Looper.getMainLooper())

    fun install(classLoader: ClassLoader) {
        if (installed) return
        installed = true

        readTintDuration(classLoader)

        val iconCls = findClass(ICON_VIEW, classLoader)
        if (iconCls == null) {
            ModuleLog.e(
                "未找到 $ICON_VIEW，环形电量颜色回退到内置语义色",
                null,
            )
            return
        }
        hookIcon(iconCls)
        findClass(METER_VIEW, classLoader)?.let { hookMeter(it) }
        schedulePoll()
    }

    // ---- 安装 ----

    private fun findClass(name: String, classLoader: ClassLoader): Class<*>? =
        runCatching { XposedHelpers.findClassIfExists(name, classLoader) }.getOrNull()

    /**
     * 读取系统自己的反色过渡时长，让环的 Argb 过渡与状态栏图标同长。
     * 读不到就用 250ms 兜底，不影响功能。
     */
    private fun readTintDuration(classLoader: ClassLoader) {
        val value = runCatching {
            val cls = findClass(LIGHT_BAR, classLoader) ?: return@runCatching null
            val field = cls.getDeclaredField("DEFAULT_TINT_ANIMATION_DURATION")
            field.isAccessible = true
            field.getInt(null)
        }.getOrNull()
        val ms = value?.toLong()?.takeIf { it > 0 } ?: DEFAULT_TINT_DURATION_MS
        RingState.setTintAnimationDuration(ms)
        ModuleLog.i("电池图标颜色 Hook：反色过渡时长 = ${ms}ms")
    }

    /** 图标 View：系统真正算出颜色的位置。 */
    private fun hookIcon(cls: Class<*>) {
        try {
            XposedHelpers.findAndHookMethod(cls, "onDarkChangeInternal", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!guard("onDarkChangeInternal")) return
                    iconRef = WeakReference(param.thisObject)
                    publish()
                }
            })
            ModuleLog.i("电池图标颜色 Hook 已挂载 ${cls.name}.onDarkChangeInternal")
        } catch (t: Throwable) {
            ModuleLog.e("Hook ${cls.name}.onDarkChangeInternal 失败", t)
        }
    }

    /** 电池根 View：反色动画每帧回调 + 五类状态变化回调。 */
    private fun hookMeter(cls: Class<*>) {
        try {
            XposedHelpers.findAndHookMethod(
                cls,
                "onDarkChanged",
                ArrayList::class.java,
                Float::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!guard("onDarkChanged")) return
                        meterRef = WeakReference(param.thisObject)
                        publish()
                    }
                },
            )
            ModuleLog.i("电池图标颜色 Hook 已挂载 ${cls.name}.onDarkChanged")
        } catch (t: Throwable) {
            ModuleLog.e("Hook ${cls.name}.onDarkChanged 失败", t)
        }

        hookState(cls, "onPerformanceModeChanged", Boolean::class.javaPrimitiveType)
        hookState(cls, "onPowerSaveChanged", Boolean::class.javaPrimitiveType)
        hookState(
            cls, "onChargeStateChanged",
            Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
        )
        hookState(
            cls, "onBatteryLevelChanged",
            Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
        )
        hookState(
            cls, "onLightDarkTintChanged",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
        )
    }

    private val stateCallback = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            if (!guard("state")) return
            publish()
        }
    }

    /**
     * 挂一个「回调里只需调 publish」的 Hook。
     * 显式拼参数数组而不是用 `*types` 展开：Java 侧签名是
     * `findAndHookMethod(Class, String, Object...)`，回调必须排在参数类型之后。
     */
    private fun hookState(cls: Class<*>, name: String, vararg types: Any?) {
        val args = arrayOfNulls<Any>(types.size + 1)
        types.copyInto(args)
        args[types.size] = stateCallback
        try {
            XposedHelpers.findAndHookMethod(cls, name, *args)
        } catch (t: Throwable) {
            ModuleLog.e("Hook $name 失败", t)
        }
    }

    // ---- 取色 ----

    /**
     * 取当前有效的图标 View。
     *
     * 弱引用被回收时（例如状态栏视图树重建、主题切换），从电池根 View 的
     * mBatteryIconView 字段重新取一次，不必干等下一次系统回调。
     */
    private fun resolveIcon(): Any? {
        iconRef?.get()?.let { return it }
        val meter = meterRef?.get() ?: return null
        val icon = runCatching {
            XposedHelpers.getObjectField(meter, "mBatteryIconView")
        }.getOrNull()
        if (icon != null) iconRef = WeakReference(icon)
        return icon
    }

    private fun publish() {
        val icon = resolveIcon() ?: return
        val colors = read(icon) ?: return

        if (!firstDumpLogged) {
            firstDumpLogged = true
            ModuleLog.i(
                "电池图标取色首次成功: light=#${hex(colors.light)} dark=#${hex(colors.dark)} " +
                    "tint=#${hex(colors.tint)} useTint=${colors.useTint} " +
                    "low=#${hex(colors.low)} powerSave=#${hex(colors.powerSave)} " +
                    "performance=#${hex(colors.performance)} charging=#${hex(colors.charging)} " +
                    "darkIntensity=${colors.darkIntensity} icon=${icon.javaClass.name}",
            )
        }
        val flagKey = "charge=${colors.chargingNow},perf=${colors.performanceNow}," +
            "save=${colors.powerSaveNow},low=${colors.lowNow}"
        if (flagKey != lastFlagKey) {
            lastFlagKey = flagKey
            ModuleLog.i("电池图标状态变化: $flagKey")
        }

        RingState.setSystemBatteryColors(colors)
    }

    private fun read(icon: Any): SystemBatteryColors? = try {
        val light = XposedHelpers.getIntField(icon, "mLightColor")
        val dark = XposedHelpers.getIntField(icon, "mDarkColor")
        // 这两个是系统真正用于上色的前景色；为 0 说明图标还没初始化完。
        // 只报一次，否则 2 秒兜底轮询会把日志刷满。
        if (light == 0 || dark == 0) {
            if (!notReadyLogged) {
                notReadyLogged = true
                ModuleLog.e(
                    "系统电池图标颜色未就绪(light=$light dark=$dark)，本次取色跳过", null,
                )
            }
            return null
        }
        SystemBatteryColors(
            light = light,
            dark = dark,
            tint = XposedHelpers.getIntField(icon, "mTintColor"),
            useTint = XposedHelpers.getBooleanField(icon, "mUseTint"),
            low = XposedHelpers.getIntField(icon, "mBatteryLowColor"),
            powerSave = XposedHelpers.getIntField(icon, "mBatteryPowerSaveColor"),
            performance = XposedHelpers.getIntField(icon, "mBatteryPerformanceModeColor"),
            charging = XposedHelpers.getIntField(icon, "mBatteryChargingColor"),
            chargingNow = XposedHelpers.getBooleanField(icon, "mQuickCharging") ||
                XposedHelpers.getBooleanField(icon, "mCharging"),
            performanceNow = XposedHelpers.getBooleanField(icon, "mPerformanceMode"),
            powerSaveNow = XposedHelpers.getBooleanField(icon, "mPowerSave"),
            lowNow = XposedHelpers.getBooleanField(icon, "mLow"),
            darkIntensity = XposedHelpers.getFloatField(icon, "mDarkIntensity"),
        )
    } catch (t: Throwable) {
        ModuleLog.e("读取系统电池图标颜色失败", t)
        null
    }

    // ---- 兜底轮询与工具 ----

    private fun schedulePoll() {
        if (pollScheduled) return
        pollScheduled = true
        handler.postDelayed(object : Runnable {
            override fun run() {
                pollScheduled = false
                if (guard("poll")) publish()
                schedulePoll()
            }
        }, POLL_INTERVAL_MS)
    }

    /** 所有 Hook 回调的统一兜底：异常绝不允许逃逸到 SystemUI。 */
    private inline fun guard(where: String, block: () -> Unit): Boolean = try {
        block()
        true
    } catch (t: Throwable) {
        ModuleLog.e("电池图标取色($where) 异常", t)
        false
    }

    private fun hex(color: Int): String = String.format("%08X", color)
}
```

> **性能说明**：`onDarkChanged` 只在反色动画推进期间（≤ 一次过渡时长）或状态跳变时回调，
> `read()` 每次约 12 次字段读取，`RingState` 侧用数据类相等性挡掉重复值，
> 2 秒兜底轮询也只有 12 次读取——都可以忽略，不要为此再加缓存层。
>
> **`meterRef` 的用途**：不只是持有引用。`resolveIcon()` 在图标 View 的弱引用失效时
> 会经它从电池根 View 的 `mBatteryIconView` 字段自愈（状态栏视图树重建后不必等下一次回调）。

- [ ] **Step 2: 编译验证**

```powershell
cd HolePowerRing
.\gradlew.bat :app:compileDebugKotlin
```
Expected: `BUILD SUCCESSFUL`。若报 `unresolved reference: getFloatField`，说明 Task 1 没做完，回去补桩。

---

### Task 6: 在 SystemUiHooks 注册

**Files:**
- Modify: `HolePowerRing/app/src/main/java/com/powerring/hole/hook/SystemUiHooks.kt:31-45`

- [ ] **Step 1: 插入注册代码**

把 `SystemUiHooks.install()` 中这一段：

```kotlin
        // 状态栏图标明暗色（机型支持时）
        try {
            SystemTintHook.install(classLoader)
        } catch (t: Throwable) {
            ModuleLog.e("明暗色 Hook 安装异常", t)
        }

        // 电池图标隐藏
        try {
            BatteryHideHook.install(classLoader)
            // 挖孔几何确认通常晚于电池 View attach，就绪后补一次隐藏评估
            RingState.onCutoutResolved = { BatteryHideHook.refreshAll() }
        } catch (t: Throwable) {
            ModuleLog.e("电池图标隐藏 Hook 安装异常", t)
        }
```

替换为：

```kotlin
        // 状态栏图标明暗色（机型支持时）
        try {
            SystemTintHook.install(classLoader)
        } catch (t: Throwable) {
            ModuleLog.e("明暗色 Hook 安装异常", t)
        }

        // 电池图标取色（必须在隐藏之前装：隐藏只是置 GONE，View 仍在收系统回调）
        try {
            BatteryColorHook.install(classLoader)
        } catch (t: Throwable) {
            ModuleLog.e("电池图标取色 Hook 安装异常", t)
        }

        // 电池图标隐藏
        try {
            BatteryHideHook.install(classLoader)
            // 挖孔几何确认通常晚于电池 View attach，就绪后补一次隐藏评估
            RingState.onCutoutResolved = { BatteryHideHook.refreshAll() }
        } catch (t: Throwable) {
            ModuleLog.e("电池图标隐藏 Hook 安装异常", t)
        }
```

- [ ] **Step 2: 整体打包编译**

```powershell
cd HolePowerRing
.\gradlew.bat assembleDebug
```
Expected: `BUILD SUCCESSFUL`，产物在 `HolePowerRing\app\build\outputs\apk\debug\app-debug.apk`

---

### Task 7: 设置页文案

**Files:**
- Modify: `HolePowerRing/app/src/main/java/com/powerring/hole/ui/SettingsScreen.kt:382`

- [ ] **Step 1: 改副标题**

把

```kotlin
                        summary = "关闭时使用系统语义色（充电蓝/低电红/省电琥珀）",
```

改为

```kotlin
                        summary = "关闭时跟随系统电池图标颜色（普通/低电/省电/性能/充电）",
```

- [ ] **Step 2: 重新打包**

```powershell
cd HolePowerRing
.\gradlew.bat assembleDebug
```
Expected: `BUILD SUCCESSFUL`

---

### Task 8: 真机验证

**前置：** 用户已连接设备、LSPosed 已启用本模块。

- [ ] **Step 1: 安装并重启 SystemUI**

```powershell
cd HolePowerRing
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

> 重启 SystemUI 优先用设置页内置的「重启系统界面」按钮；若用户同意用 adb：
> `adb shell killall com.android.systemui`。**未经同意不要执行。**

- [ ] **Step 2: 抓日志确认 Hook 装上**

```powershell
adb logcat -c
adb logcat -s HolePowerRing | Select-Object -First 60
```

必须出现（缺一即为失败，回到对应 Task 排查）：

```
HolePowerRing: 电池图标颜色 Hook：反色过渡时长 = <数字>ms
HolePowerRing: 电池图标颜色 Hook 已挂载 com.android.systemui.statusbar.views.MiuiBatteryMeterIconView.onDarkChangeInternal
HolePowerRing: 电池图标颜色 Hook 已挂载 com.android.systemui.statusbar.views.MiuiBatteryMeterView.onDarkChanged
HolePowerRing: 电池图标取色首次成功: light=#........ dark=#........ ... icon=com.android.systemui.statusbar.views.MiuiBatteryMeterIconView
HolePowerRing: 环色跟随系统电池图标: di=1.0 normal=#........ ...
```

若看到 `未找到 ...MiuiBatteryMeterIconView`，说明机型 SystemUI 版本不同，
需用 `work/find_classes.py` 重新检索并同步更新本计划与报告 §12 的类名。

- [ ] **Step 3: 五种状态逐一肉眼核对**

| 场景 | 期望 |
|---|---|
| 普通显示，浅色状态栏（如展开通知栏） | 环色 = 系统电池图标色（深色），且下拉展开过程中环色**连续渐变**、与图标同步，无跳变 |
| 普通显示，深色状态栏（回到桌面） | 环色 = 系统电池图标色（浅色） |
| 电量 ≤ 15% | 环色 = 系统低电红 |
| 打开省电模式 | 环色 = 系统省电色 |
| 打开性能模式 | 环色 = 系统性能模式色（**改造前无此分支，本次新增**） |
| 插上充电器 | 环色 = 系统充电色 |
| 关闭「自定义环颜色」→ 打开 | 环色 = 自定义色，**其余五种状态全部让位** |
| 打开「自定义环颜色」→ 关闭 | 立刻恢复跟随系统图标色（无需重启 SystemUI） |

- [ ] **Step 4: 过渡动画专项核对**

下拉通知栏 / 上滑收起，让状态栏在明暗之间切换。判据：

1. 环色变化与状态栏电池图标**同拍起步、同拍到位**，中途没有一帧的旧色残留；
2. 没有"先跳到目标色再淡入"这种双段式（说明二次动画被错误叠加，检查 Task 3 的 `intensityMoved` 分支）；
3. `adb logcat -s HolePowerRing` 中 `环色跟随系统电池图标` **每秒最多 1 行**（Task 3 已做 1 秒节流）；
   若看到连续刷屏，说明节流失效或 Task 3 的代码没贴全。

- [ ] **Step 5: 性能与稳定性核对**

```powershell
adb shell dumpsys gfxinfo com.android.systemui | Select-String "Janky|Total frames"
```
Expected: 切换状态栏明暗时 Janky frames 增量不应明显上升（环只是改 Paint 颜色，无新增分配）。

再确认日志里**没有**新的 `E/` 异常行：

```powershell
adb logcat -s HolePowerRing:E
```
Expected: 无新增 `Hook ... 失败` / `读取系统电池图标颜色失败` / `反常` 类错误。

---

### Task 9: 更新可行性分析报告

**Files:**
- Modify: `挖孔环形电量LSP模块-可行性分析报告.md`（在 `## 附录 A：关键类索引（逆向实证）` 之前插入新的一节）

> **编号提醒**：报告里已经有一节 `## 11. 窗口层级实测（2026-10-01）`（未提交的既有工作，
> 对应 `docs/superpowers/plans/2026-10-01-ring-window-layer-priority.md`）。
> 本次新增的是 **§12**，不要占用 §11。

- [ ] **Step 1: 插入 §12**

在 `## 附录 A：关键类索引（逆向实证）` 这一行之前（即现有 §11 的末尾之后）插入：

```markdown
## 12. 电量环跟随系统电池图标颜色（2026-10-01 逆向 + 实施）

### 12.1 目标

未开启自定义颜色时，环形电量不再使用模块自带的 miuix 语义色
（充电蓝 / 低电红 / 省电琥珀），而是直接复用系统原生电池图标的五种状态色
（普通 / 低电量 / 省电模式 / 性能模式 / 充电中）。

### 12.2 取色链路（系统界面 17.03.260226.r，`work/sysui/`）

```
LightBarTransitionsController.animateIconTint(FJJZ)     // 反色动画时钟，Interpolators.LINEAR
  └─ DarkIntensityApplier.applyDarkIntensity(F)
       └─ MiuiBatteryMeterView.onDarkChanged(ArrayList, float, int)   // 每帧回调 darkIntensity
            ├─ MiuiBatteryMeterView.onDarkChangedInternal()           // 文本/充电闪电上色
            └─ MiuiBatteryMeterIconView.onDarkChangeInternal()        // 图标本体最终上色
                 └─ ArgbEvaluator.evaluate(...) + Drawable.setTintList(...)
```

`LightBarTransitionsController` 持有 `public static final int DEFAULT_TINT_ANIMATION_DURATION`
与 `mDarkIntensity`/`mNextDarkIntensity`/`mTintAnimator`；具体时长来自
`DarkIntensityApplier.getTintAnimationDuration()`。

### 12.3 关键类与字段

`com.android.systemui.statusbar.views.MiuiBatteryMeterView`（LinearLayout）：

| 成员 | 说明 |
|---|---|
| `public final MiuiBatteryMeterIconView mBatteryIconView` | 真正上色的图标 View |
| `public final MiuiHollowBatteryMeterIconView mHollowBatteryIconView` | 镂空样式图标（**本次未接入**） |
| `onDarkChanged(ArrayList, float, int)` | 反色动画每帧回调，存 `mDarkIntensity`/`mTintAreas`/`mTintColor` |
| `onLightDarkTintChanged(int, int, boolean)` | 浅/深前景色变更 |
| `onPerformanceModeChanged(boolean)` / `onPowerSaveChanged(boolean)` / `onChargeStateChanged(boolean, boolean)` / `onBatteryLevelChanged(int, boolean, boolean)` | 四类状态变更 |

`com.android.systemui.statusbar.views.MiuiBatteryMeterIconView`（ImageView）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `mLightColor` / `mDarkColor` | int | 浅色 / 深色两套前景色 |
| `mDarkIntensity` | float | 反色动画进度 0..1 |
| `mTintColor` / `mUseTint` | int / boolean | tint 区域色及其开关 |
| `mBatteryLowColor` / `mBatteryPowerSaveColor` / `mBatteryPerformanceModeColor` / `mBatteryChargingColor` | int | 四个语义色 |
| `mLow` / `mPowerSave` / `mPerformanceMode` / `mCharging` / `mQuickCharging` | boolean | 状态位 |

状态枚举：`com.android.systemui.statusbar.util.MiuiBatteryUtils$BatteryStatus`
（`CHARGING`、`CHARGING_DARK`、`LOW`、`NORMAL`、`NORMAL_DARK`、`PERFORMANCE_MODE`、
`PERF_CHARGE_MODE`、`PERF_QC_MODE`、`POWER_SAVE`、`QUICK_CHARGING` 等 14 项）。
`getProgressStatus()` / `getBackgroundStatus()` 依据上述状态位返回该枚举。

### 12.4 本模块的取色策略

1. Hook `MiuiBatteryMeterIconView.onDarkChangeInternal()`（之后）与
   `MiuiBatteryMeterView.onDarkChanged(ArrayList,float,int)`（之后）；
2. 反射读取上表字段，组装 `SystemBatteryColors` 交给 `RingState`；
3. 普通态颜色 = `ArgbEvaluator.evaluate(mDarkIntensity, lightColor, darkColor)`
   —— **与系统同一时间轴**，因此不需要自建动画；
4. 仅当 `darkIntensity` 未变而浅/深目标色被换掉（主题切换 / tint 区域变化）时，
   才补一次时长等于 `DEFAULT_TINT_ANIMATION_DURATION` 的 Argb 过渡；
5. 任一步失败 → `BatteryPalette.ready = false` → 渲染回退 miuix 固定语义色，
   行为与改造前完全一致。

### 12.5 已知限制

- 只接入了 `MiuiBatteryMeterIconView`；镂空样式
  `MiuiHollowBatteryMeterIconView` 的字段名无 `m` 前缀、且没有 `lightColor/darkColor`
  （改用 `batteryLevelWhite`/`batteryLevelTransWhite`/`batteryLevelTransDark`
  与私有 `getBatteryLevelColor()`），需要另一套读取逻辑，本次未做。
- 上述类只存在于 SystemUI 宿主 dex，灵动岛插件 `miui.systemui.plugin` 中没有，
  因此 Hook 必须装在宿主 ClassLoader 上。
- 版本敏感：类名、字段名、方法签名随 SystemUI 版本变化，换机型必须先用
  `work/dump_class.py` / `work/method_refs.py` 复核。

复核命令：

```bash
cd work
python dump_class.py sysui "com.android.systemui.statusbar.views.MiuiBatteryMeterIconView"
python method_refs.py sysui "com.android.systemui.statusbar.views.MiuiBatteryMeterIconView" onDarkChangeInternal
```
```

- [ ] **Step 2: 同步更新「已知待验证项」**

打开 `AGENTS.md`，在「已知待验证项」列表末尾追加一条：

```markdown
- **电池图标取色链路：已确认（2026-10-01）**。`MiuiBatteryMeterIconView.onDarkChangeInternal()` 是系统给电池图标上色的唯一位置，`mLightColor`/`mDarkColor`/`mDarkIntensity` + 四个 `mBattery*Color` 字段可直接反射读取；反色动画时钟在 `LightBarTransitionsController.animateIconTint`。详见可行性分析报告 §12。**未覆盖**镂空样式 `MiuiHollowBatteryMeterIconView`。
```

- [ ] **Step 3: 确认改动集完整**

```powershell
git status --short
git diff --stat
```

Expected: 本次新增/修改的文件是「文件结构总览」里列出的那几项，外加调研用的
`work/method_refs.py`。**工作区本来就带着另一批未提交的改动**（报告 §11 与
`docs/superpowers/plans/2026-10-01-ring-window-layer-priority.md`），那是别人的活儿，
**不要顺手回滚，也不要一起提交**。

若执行过程中在 `work/` 下留下了 `refs_icon.txt` / `refs_meter.txt` 之类的扫描输出，
删掉它们，它们是临时产物：

```powershell
Remove-Item work\refs_icon.txt, work\refs_meter.txt -ErrorAction SilentlyContinue
git status --short
```

---

## 自检记录

- **需求覆盖**：「五种状态跟随电池图标颜色」→ Task 4 的 `followSystem` 五个分支 + Task 5 的四个语义色字段；「普通态跟随状态栏自适应反色」→ Task 3 的 `ArgbEvaluator.evaluate(intensity, light, dark)`；「过渡一致不突兀」→ Task 3 的 `intensityMoved` 分支（共用系统时间轴）+ Task 5 的时长读取；「自定义色优先」→ Task 4 `config.useCustomColor` 仍在最前；「Hook 失效不劣化」→ `BatteryPalette.ready` 回退路径。
- **类型一致性**：`BatteryPalette`（渲染侧，8 个颜色/状态字段）与 `SystemBatteryColors`（Hook 侧，10 个原始字段）在 Task 2 定义，Task 3 / 5 分别按同名语义构造，Task 4 只读 `BatteryPalette`；`RingState.normalColor` 与 `BatteryPalette.normal` 职责已区分（前者是显示值，后者是目标值）。
- **无占位符**：每个代码步骤都给出可直接粘贴的完整代码块，每条命令都给出期望输出。
