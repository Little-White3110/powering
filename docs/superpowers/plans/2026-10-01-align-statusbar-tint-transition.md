# 电量环变色过渡与状态栏反色动画对齐 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 环形电量的变色过渡与系统状态栏反色动画**同拍起步、同拍到位**，消除当前"颜色对了但快慢对不上"的观感。

**Architecture:** 把取色的时间基准从「电池图标 View 自己重绘的时机」换成「系统反色动画的权威时钟」`DarkIconDispatcherImpl.applyDarkIntensity(float)`——它是 `LightBarTransitionsController` 的 ValueAnimator 每帧回调，且逐帧写入 `mDarkIntensity` 后广播给所有 `DarkReceiver`。同时把过渡时长从「读一个不可靠的静态常量」换成「运行期捕获系统真正用的那个值」`DarkIconDispatcherImpl.getTintAnimationDuration()`。Task 1 先埋点实测，用事实决定 Task 3 的动画策略，而不是靠猜。

**Tech Stack:** Kotlin、Xposed API（`xposedstub` 仅 compileOnly）、`android.animation.ArgbEvaluator` / `ValueAnimator`、`work/probe.py` DEX 探针。

---

## 为什么是现在这个方案（问题定位）

上一版（`2026-10-01-follow-system-battery-icon-color.md`）的实现有两个**已确认的缺陷**：

### 缺陷 1：过渡时长读的是一个不可靠的来源

`BatteryColorHook.readTintDuration()` 读的是
`LightBarTransitionsController.DEFAULT_TINT_ANIMATION_DURATION`。

用 `work/probe.py static` 实测：

```
# Lcom/android/systemui/statusbar/phone/LightBarTransitionsController;  [classes2.dex]  static_values_off=0
   static DEFAULT_TINT_ANIMATION_DURATION = '<none>'
```

`static_values_off = 0` 意味着它**不是编译期常量**，而是在 `<clinit>` 里运行时赋值的。
在 SystemUI 刚启动、`SystemUiHooks.install()` 就去读这个静态字段，很可能拿到未初始化/占位值，
于是 `RingState` 静默回退到我们硬编码的 `DEFAULT_TINT_DURATION_MS = 250L`（RingState.kt:77）。
**250ms 是我们拍脑袋的数，跟系统没关系。**

### 缺陷 2：系统真正的时长是按设备档位分级的，我们根本没读对地方

用 `work/probe.py impls sysui getTintAnimationDuration` 查出 `DarkIntensityApplier` 的全部实现者：

```
[classes2.dex] Lcom/android/systemui/statusbar/phone/LightBarTransitionsController$DarkIntensityApplier;.getTintAnimationDuration()I
[classes2.dex] Lcom/android/systemui/navigationbar/TaskbarDelegate$3;.getTintAnimationDuration()I
[classes2.dex] Lcom/android/systemui/navigationbar/views/NavigationBarTransitions;.getTintAnimationDuration()I
[classes2.dex] Lcom/android/systemui/statusbar/phone/DarkIconDispatcherImpl;.getTintAnimationDuration()I   ← 状态栏用的就是它
```

再扫 `DarkIconDispatcherImpl.getTintAnimationDuration()` 的字段引用：

```
getTintAnimationDuration()I
    field: Lcom/miui/utils/ComputilityUtils;->Z isLowLevel
    field: Lcom/miui/utils/ComputilityUtils;->Z isMidLowLevel
    field: Lcom/miui/utils/ComputilityUtils;->Z isNormalLevel
    field: Lcom/miui/utils/ComputilityUtils;->Z isSubMidLevel
```

**状态栏的时长依赖 `ComputilityUtils` 的设备性能档位**（低端机动画更短），
是个**运行期按机型算出来的值**。任何编译期常量、任何硬编码默认值都不可能对得上。

`DarkIconDispatcherImpl` 同时就是状态栏的 applier（它自己声明了
`applyDarkIntensity(F)V` 和 `getTintAnimationDuration()I`），并且：

```
applyDarkIntensity(F)V
    field: ...DarkIconDispatcherImpl;->F mDarkIntensity
    call  : ...DarkIconDispatcherImpl;.applyIconTint

applyIconTint()V
    field: ...DarkIconDispatcherImpl;->F mDarkIntensity
    field: ...DarkIconDispatcherImpl;->Landroid/util/ArrayMap; mReceivers
    call  : Lcom/android/systemui/plugins/DarkIconDispatcher$DarkReceiver;.onDarkChanged
    call  : Lcom/android/systemui/plugins/DarkIconDispatcher$DarkReceiver;.onLightDarkTintChanged
```

**这条链就是系统的动画时钟**：`LightBarTransitionsController` 的 ValueAnimator 每帧
调 `applyDarkIntensity(f)` → 写 `mDarkIntensity` → 广播给所有 receiver
（其中就包括 `MiuiBatteryMeterView.onDarkChanged`）。

我们目前挂在电池图标 View 的 `onDarkChangeInternal()` 上，那是**渲染端**，
中间还隔着 `DarkIconDispatcherExt.getDarkIntensity(ArrayList, View, float)`
（内部就是 `dispatcher.isInAreas(tintAreas, view) ? 全局强度 : 默认值`）这一层按区域取值的逻辑。
渲染端是否每帧被调用，取决于系统有没有真的让那个 View 重绘——这不是可靠的时间基准。

### 关于手工反汇编：不可信，不要基于它下结论

`work/probe.py insns` 能导出原始指令码，我据此解出
`getTintAnimationDuration()` 里有三个 `const/16 v0, #15`，看起来是 15ms。
**但解算出的 field_ids 索引和 `work/method_refs.py` 的结果对不上**
（前者解出框架字段 `ALL_SESSIONS`，后者解出 `ComputilityUtils.isLowLevel`），
说明自制的指令宽度表在某处错位了。该产物**只能当线索，不能当事实**。

因此本计划的 Task 1 是**先在真机上量**，Task 3/4 的实现对两种量测结果都成立。

---

## 执行约定（承自前两版计划）

- **不执行 `git commit`**，改动留在工作区由用户自行 review 与提交。
- 仓库**没有自动化测试**，用「`gradlew.bat assembleDebug` + 真机 logcat」验证。
- 所有 Hook 回调与反射必须 `try/catch (Throwable)` + `ModuleLog`（AGENTS.md 第 1 条）。
- 不要回滚工作区里别人的未提交改动（报告 §11 与 `docs/superpowers/plans/2026-10-01-ring-window-layer-priority.md`）。

## 文件结构总览

| 文件 | 操作 | 职责 |
|---|---|---|
| `HolePowerRing/app/src/main/java/com/powerring/hole/hook/BatteryColorHook.kt` | 修改 | 挂 `DarkIconDispatcherImpl` 与 `LightBarTransitionsController` 的时钟/时长 + 诊断埋点 |
| `HolePowerRing/app/src/main/java/com/powerring/hole/ring/BatteryPalette.kt` | 修改 | `SystemBatteryColors` 增加 `intensityFromClock` 标记 |
| `HolePowerRing/app/src/main/java/com/powerring/hole/ring/RingState.kt` | 修改 | 时长来源改为运行期捕获；过渡策略按实测结论调整 |
| `work/probe.py` | 已创建 | DEX 静态常量 / 实现者 / 指令码探针 |
| `挖孔环形电量LSP模块-可行性分析报告.md` | 修改 | §12 补充动画时钟链路与设备档位结论 |

---

### Task 1: 埋点实测动画时钟

**Files:**
- Modify: `HolePowerRing/app/src/main/java/com/powerring/hole/hook/BatteryColorHook.kt`

- [ ] **Step 1: 在 `BatteryColorHook` 里加常量与诊断状态**

在 `private const val LIGHT_BAR = ...` 这一行之后插入：

```kotlin
    /** 状态栏反色动画的真实时钟：ValueAnimator 每帧回调它 */
    private const val DARK_DISPATCHER =
        "com.android.systemui.statusbar.phone.DarkIconDispatcherImpl"
```

在 `private var firstDumpLogged = false` 之后插入：

```kotlin
    /** 诊断：是否打印逐帧轨迹。默认开，验证完在 Task 5 关掉。 */
    @Volatile
    private var traceEnabled = true

    /** 诊断：上一次打点的墙钟时间，用来限流 */
    private var lastTraceMs = 0L

    /** 诊断：动画开始时的强度与时间，用于算实测时长 */
    private var traceStartMs = 0L
    private var traceStartValue = Float.NaN

    /** 诊断：捕获到的系统时长；没捕获到为 0 */
    @Volatile
    private var capturedDurationMs = 0

    /** 诊断：最近一次 animateIconTint 的实参，作为 getTintAnimationDuration 的兜底来源 */
    @Volatile
    private var capturedTarget = Float.NaN
    @Volatile
    private var capturedStartDelay = -1L
    @Volatile
    private var capturedDuration = -1L
```

- [ ] **Step 2: `install()` 改调新的挂载入口**

把 `install(classLoader: ClassLoader)` 里这一行：

```kotlin
        readTintDuration(classLoader)
```

替换为：

```kotlin
        hookDarkDispatcher(classLoader)
        hookTintAnimator(classLoader)
```

- [ ] **Step 3: 整个 `readTintDuration` 换成 `hookDarkDispatcher`**

把 `readTintDuration` 函数**整体删除**，换成下面这段（它挂的是权威时钟 + 真实时长）：

```kotlin
    /**
     * 挂状态栏反色动画的权威时钟与真实时长。
     *
     * `applyDarkIntensity(F)` 由 LightBarTransitionsController 的 ValueAnimator
     * 每帧回调，是系统自己的插值时钟；`getTintAnimationDuration()` 返回的是
     * 按 ComputilityUtils 设备档位算出的真实时长（不是任何编译期常量）。
     *
     * 两者都是类自身声明的方法，不会退化到父类。
     */
    private fun hookDarkDispatcher(classLoader: ClassLoader) {
        val cls = findClass(DARK_DISPATCHER, classLoader)
        if (cls == null) {
            ModuleLog.e(
                "未找到 $DARK_DISPATCHER，环色过渡将退回默认时长", null,
            )
            return
        }
        try {
            XposedHelpers.findAndHookMethod(cls, "applyDarkIntensity",
                Float::class.javaPrimitiveType, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        guard("applyDarkIntensity") {
                            val v = param.args.getOrNull(0) as? Float
                            if (v != null) onClockTick(v)
                        }
                    }
                })
            ModuleLog.i("电池图标颜色 Hook 已挂载 ${cls.name}.applyDarkIntensity（动画时钟）")
        } catch (t: Throwable) {
            ModuleLog.e("Hook applyDarkIntensity 失败", t)
        }
        try {
            XposedHelpers.findAndHookMethod(cls, "getTintAnimationDuration",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        guard("getTintAnimationDuration") {
                            val ms = param.result as? Int
                            if (ms != null && ms > 0 && ms != capturedDurationMs) {
                                capturedDurationMs = ms
                                RingState.setTintAnimationDuration(ms.toLong())
                                ModuleLog.i(
                                    "电池图标颜色 Hook：捕获系统反色动画时长 = ${ms}ms",
                                )
                            }
                        }
                    }
                })
            ModuleLog.i("电池图标颜色 Hook 已挂载 ${cls.name}.getTintAnimationDuration")
        } catch (t: Throwable) {
            ModuleLog.e("Hook getTintAnimationDuration 失败", t)
        }
    }

    /**
     * 时长的第二来源：`animateIconTint(F, J, J, Z)` 的实参就是系统这次动画
     * 真正用的 (目标强度, startDelay, duration)。
     *
     * 签名来自 work/method_refs.py 的实证——该方法体内同时出现
     * ValueAnimator.setStartDelay 与 setDuration，参数顺序按 AOSP 同名方法
     * `animateIconTint(float, long startDelay, long duration)` 对齐。
     *
     * 作用：当 getTintAnimationDuration() 没被调用过时（例如该次过渡走了
     * 非动画路径），这里仍能拿到 duration 作为兜底。
     */
    private fun hookTintAnimator(classLoader: ClassLoader) {
        val cls = findClass(LIGHT_BAR, classLoader) ?: return
        try {
            XposedHelpers.findAndHookMethod(
                cls, "animateIconTint",
                Float::class.javaPrimitiveType,
                java.lang.Long.TYPE,
                java.lang.Long.TYPE,
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        guard("animateIconTint") {
                            val target = param.args.getOrNull(0) as? Float
                            val delay = param.args.getOrNull(1) as? Long
                            val duration = param.args.getOrNull(2) as? Long
                            if (target != null && delay != null && duration != null) {
                                capturedTarget = target
                                capturedStartDelay = delay
                                capturedDuration = duration
                                if (duration > 0 && capturedDurationMs != duration.toInt()) {
                                    capturedDurationMs = duration.toInt()
                                    RingState.setTintAnimationDuration(duration)
                                    ModuleLog.i(
                                        "电池图标颜色 Hook：animateIconTint 实参 " +
                                            "target=$target delay=${delay}ms duration=${duration}ms",
                                    )
                                }
                            }
                        }
                    }
                },
            )
            ModuleLog.i("电池图标颜色 Hook 已挂载 ${cls.name}.animateIconTint（时长兜底）")
        } catch (t: Throwable) {
            ModuleLog.e("Hook animateIconTint 失败", t)
        }
    }
```

- [ ] **Step 4: 加逐帧打点**

在 `publish()` 函数之后插入：

```kotlin
    /**
     * 系统动画时钟每帧回调。
     *
     * 这里**只做诊断**（限流打印）。真正的取色仍由 publish() 走
     * onDarkChangeInternal —— 两者的频率差正是 Task 3 要量的东西。
     */
    private fun onClockTick(darkIntensity: Float) {
        if (!traceEnabled) return
        val now = android.os.SystemClock.uptimeMillis()
        if (traceStartMs == 0L || now - traceStartMs > 2000L) {
            traceStartMs = now
            traceStartValue = darkIntensity
            lastTraceMs = 0L
        }
        // 限流：每 60ms 打一行，够看清斜坡形状又不刷屏
        if (now - lastTraceMs < 60L) return
        lastTraceMs = now
        ModuleLog.i(
            "时钟轨迹: dt=${now - traceStartMs}ms value=$darkIntensity " +
                "(起点=$traceStartValue, 时长=${if (capturedDurationMs > 0) capturedDurationMs else "未捕获"})",
        )
    }
```

> 注意：`guard` 的 `block: () -> Unit` 不支持带标签的非局部返回，
> 所以所有取值都用 `if (x != null)` 的写法，**不要写 `return@guard`**。

- [ ] **Step 5: 补 `import java.lang.Long` 不需要，用全限定名**

Step 3 的 `java.lang.Long.TYPE` 已在代码里写成全限定名，无需加 import。
确认 `BatteryColorHook.kt` 顶部 import 列表未变动即可。

- [ ] **Step 6: 打包安装并采集**

```powershell
cd HolePowerRing
.\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

> 重启 SystemUI：优先用设置页内置的「重启系统界面」按钮（HyperOS 上
> `adb shell killall com.android.systemui` 会被 SELinux 拒绝，实测
> `Operation not permitted`；adb 用户也没有 `su`）。

重启后采集：

```powershell
adb logcat -c
# 在设备上下拉通知栏再上滑收起，反复 3 次
adb logcat -d -s HolePowerRing
```

- [ ] **Step 7: 判读采集结果**

在 logcat 里找这两类行：

```
HolePowerRing: 电池图标颜色 Hook：捕获系统反色动画时长 = <N>ms
HolePowerRing: 时钟轨迹: dt=<t>ms value=<v> (起点=<v0>, 时长=<N>)
HolePowerRing: 环色跟随系统电池图标: di=<v> normal=#<色值> ...
```

记下四个事实，Task 3/4 的实现分支取决于它们：

| 记号 | 看什么 | 影响 |
|---|---|---|
| **A** | `捕获系统反色动画时长 = ?ms` | Task 4 的时长兜底值。若这行没出现，说明 `getTintAnimationDuration()` 从没被调用，要退回 `animateIconTint` 实参 |
| **B** | `时钟轨迹` 行数与 dt 跨度 | 时钟是否真的逐帧推进。若一次过渡只有 0~2 行，说明系统其实是离散跳变 |
| **C** | `环色跟随系统电池图标` 的 `di=` 变化条数 | **关键**：如果 di 只跳 0→1 两三次，说明 `onDarkChangeInternal` 不是逐帧触发，那就是当前过渡对不齐的根因 |
| **D** | 首行 `di=` 与首行 `时钟轨迹 value=` 的时间差 | 环色相对状态栏的滞后量 |

把这四个值原样记进 Task 5 的验证报告里。

---

### Task 2: 给调色板快照加"时钟来源"标记

**Files:**
- Modify: `HolePowerRing/app/src/main/java/com/powerring/hole/ring/BatteryPalette.kt`

- [ ] **Step 1: `SystemBatteryColors` 增加标记位**

在 `SystemBatteryColors` 的 `val darkIntensity: Float = 1f,` 之后、`}` 之前插入：

```kotlin
    /**
     * 本次 darkIntensity 来自系统的动画时钟（applyDarkIntensity 逐帧回调），
     * 而不是电池图标 View 自己的重绘时机。false 表示是离散的跳变。
     */
    val fromClock: Boolean = false,
```

- [ ] **Step 2: `BatteryPalette` 增加同名字段**

在 `BatteryPalette` 的 `val darkIntensity: Float = 1f,` 之后、`) {` 之前插入：

```kotlin
    /** true = darkIntensity 来自逐帧动画时钟，false = 离散跳变 */
    val intensityFromClock: Boolean = false,
```

- [ ] **Step 3: 编译验证**

```powershell
cd HolePowerRing
.\gradlew.bat :app:compileDebugKotlin
```
Expected: `BUILD SUCCESSFUL`（新增字段都有默认值，现有构造点无需改）

---

### Task 3: 取色时间基准改挂动画时钟

> **这个任务无条件执行。** 即使量测记号 **C** 显示 `onDarkChangeInternal` 本来就是逐帧的，
> 多挂一条时钟路径也只是多一次相等性去重（`RingState` 会挡掉重复值），
> 不会带来额外重绘；但一旦发现渲染路径不是逐帧的，它就是唯一的解法。
> 所以不需要先判断 C 的结果再决定做不做。

**Files:**
- Modify: `HolePowerRing/app/src/main/java/com/powerring/hole/hook/BatteryColorHook.kt`

- [ ] **Step 1: `publish()` 增加时钟来源参数**

把 `publish()` 的函数头与首两行：

```kotlin
    private fun publish() {
        val icon = resolveIcon() ?: return
        val colors = read(icon) ?: return
```

改为：

```kotlin
    private fun publish(fromClock: Boolean = false) {
        val icon = resolveIcon() ?: return
        val colors = read(icon) ?: return
        val snapshot = if (fromClock) colors.copy(fromClock = true) else colors
```

并把该函数末尾的：

```kotlin
        RingState.setSystemBatteryColors(colors)
```

改为：

```kotlin
        RingState.setSystemBatteryColors(snapshot)
```

（`firstDumpLogged` / `flagKey` 那两段日志仍用 `colors`，不受影响。）

- [ ] **Step 2: 钩住 mDarkIntensity 字段的变化**

在 `hookDarkDispatcher` 里，`applyDarkIntensity` 那个 `afterHookedMethod` 的 `guard` 块末尾追加一行：

```kotlin
                            if (v != null) {
                                onClockTick(v)
                                publish(fromClock = true)
                            }
```

- [ ] **Step 3: 保持原有回调不变**

`hookIcon` / `hookMeter` / `stateCallback` / 2 秒兜底轮询里的 `publish()` 保持无参调用，
走 `fromClock = false` 的离线路径。这样两条路径互补：

- **时钟路径**：`applyDarkIntensity` 每帧到，颜色跟系统同拍；
- **渲染路径**：`onDarkChangeInternal` / `onBatteryLevelChanged` 等，颜色跟着图标 View 实际重绘走。

`RingState` 侧靠数据类相等性去重，两条路径喂同一个值时不会重复触发。

- [ ] **Step 4: 编译验证**

```powershell
cd HolePowerRing
.\gradlew.bat :app:compileDebugKotlin
```
Expected: `BUILD SUCCESSFUL`

---

### Task 4: 过渡时长与动画策略

> **前置**：Task 1 的量测结果记号 **A** 和 **C**。

**Files:**
- Modify: `HolePowerRing/app/src/main/java/com/powerring/hole/ring/RingState.kt:74-77`（常量）
- Modify: `HolePowerRing/app/src/main/java/com/powerring/hole/ring/RingState.kt:180-245`（写入与动画）

- [ ] **Step 1: 常量改名为"兜底"并调整数值**

把：

```kotlin
    /** 读不到系统常量时的兜底反色过渡时长 */
    private const val DEFAULT_TINT_DURATION_MS = 250L
```

改为：

```kotlin
    /**
     * 连 `DarkIconDispatcherImpl.getTintAnimationDuration()` 都拿不到时的最后兜底。
     * 250ms 是 MIUI 状态栏图标反色的常见观感值，仅在系统完全不可读时使用；
     * 正常路径下 [tintAnimationDurationMs] 会被系统真实时长覆盖。
     */
    private const val FALLBACK_TINT_DURATION_MS = 250L
```

并把 `RingState.kt:100` 的：

```kotlin
    var tintAnimationDurationMs: Long = DEFAULT_TINT_DURATION_MS
```

改为：

```kotlin
    var tintAnimationDurationMs: Long = FALLBACK_TINT_DURATION_MS
```

- [ ] **Step 2: `setTintAnimationDuration` 记录来源**

把：

```kotlin
    /** 由 BatteryColorHook 在读到系统常量后调用；非法值忽略。 */
    fun setTintAnimationDuration(ms: Long) {
        if (ms > 0) tintAnimationDurationMs = ms
    }
```

改为：

```kotlin
    /**
     * 由 BatteryColorHook 在捕获到系统真实时长后调用；非法值忽略。
     *
     * 来源是 `DarkIconDispatcherImpl.getTintAnimationDuration()`，
     * 按 `ComputilityUtils` 设备档位计算，不是编译期常量。
     */
    fun setTintAnimationDuration(ms: Long) {
        if (ms <= 0) return
        if (ms != tintAnimationDurationMs) {
            tintAnimationDurationMs = ms
            ModuleLog.i("环色过渡时长更新为 ${ms}ms（取自系统）")
        }
    }
```

- [ ] **Step 3: 动画分支改用时钟标记**

把 `setSystemBatteryColors` 里的这两行：

```kotlin
        val intensity = colors.darkIntensity.coerceIn(0f, 1f)
        val intensityMoved = lastDarkIntensity.isNaN() || intensity != lastDarkIntensity
        lastDarkIntensity = intensity
```

改为：

```kotlin
        val intensity = colors.darkIntensity.coerceIn(0f, 1f)
        // 时钟路径每帧来，此时 intensity 本身就是连续的插值结果；
        // 渲染路径是离散跳变，需要自己补一次等长过渡。
        val continuous = colors.fromClock
        val intensityMoved = lastDarkIntensity.isNaN() || intensity != lastDarkIntensity
        lastDarkIntensity = intensity
```

再把紧跟其后的：

```kotlin
        if (intensityMoved) {
            colorAnimator?.cancel()
            colorAnimator = null
            normalColor = target
        } else {
            animateNormalColorTo(target)
        }
```

改为：

```kotlin
        if (continuous || intensityMoved) {
            // 连续路径：目标色每帧都在变，直接跟随系统时钟，不叠加二次动画
            colorAnimator?.cancel()
            colorAnimator = null
            normalColor = target
        } else {
            animateNormalColorTo(target)
        }
```

- [ ] **Step 4: 去掉重复的节流日志前缀**

`setSystemBatteryColors` 末尾的节流日志里，`di=` 后面补上来源，方便验证时一眼分辨两条路径：

把：

```kotlin
            ModuleLog.i(
                "环色跟随系统电池图标: di=$intensity normal=#${hex(target)} " +
```

改为：

```kotlin
            ModuleLog.i(
                "环色跟随系统电池图标: di=$intensity src=${if (continuous) "clock" else "view"} " +
                    "normal=#${hex(target)} " +
```

- [ ] **Step 5: 编译验证**

```powershell
cd HolePowerRing
.\gradlew.bat assembleDebug
```
Expected: `BUILD SUCCESSFUL`，产物在 `HolePowerRing\app\build\outputs\apk\debug\app-debug.apk`

---

### Task 5: 真机验证并关闭诊断

**Files:**
- Modify: `HolePowerRing/app/src/main/java/com/powerring/hole/hook/BatteryColorHook.kt`

- [ ] **Step 1: 装包 + 重启 SystemUI**

```powershell
cd HolePowerRing
adb install -r app\build\outputs\apk\debug\app-debug.apk
```
然后用设置页的「重启系统界面」按钮重启（adb killall 被 SELinux 拒绝，见 Task 1 Step 6）。

- [ ] **Step 2: 验证过渡对齐**

```powershell
adb logcat -c
# 设备上下拉通知栏 / 上滑收起，反复 3 次
adb logcat -d -s HolePowerRing
```

判据：

1. 出现 `电池图标颜色 Hook：捕获系统反色动画时长 = <N>ms`，且 `环色过渡时长更新为 <N>ms（取自系统）`——两次数字必须一致，且 **N 不是 250**（是 250 说明系统值没抓到，检查 Task 1 Step 7 记号 A）；
2. `环色跟随系统电池图标` 行里 `src=clock` 的条目在一次过渡内**连续出现多条**（不是只有首尾两条）；
3. `src=clock` 的第一条与最后一条之间的时间跨度 ≈ N ms；
4. 肉眼：环色与状态栏电池图标**同拍起步、同拍到位**，中途无旧色残留帧、无"先跳后淡入"的双段式。

- [ ] **Step 3: 关闭逐帧诊断**

把 `traceEnabled = true` 改为：

```kotlin
    /** 诊断：是否打印逐帧轨迹。默认关，排查时置 true。 */
    @Volatile
    private var traceEnabled = false
```

同时把 `onClockTick` 函数体开头改为早退，避免残留开销：

```kotlin
    private fun onClockTick(darkIntensity: Float) {
        if (!traceEnabled) return
        if (darkIntensity == traceStartValue && traceStartMs != 0L) return
        val now = android.os.SystemClock.uptimeMillis()
        ...
    }
```

- [ ] **Step 4: 重新打包安装，确认无刷屏**

```powershell
cd HolePowerRing
.\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```
重启 SystemUI 后再拉一次通知栏，确认 logcat 里**没有** `时钟轨迹` 行。

- [ ] **Step 5: 性能核对**

```powershell
adb shell dumpsys gfxinfo com.android.systemui | Select-String "Janky|Total frames"
```
Expected: 状态栏明暗切换时 Janky frames 增量不明显（环只改 Paint 颜色，无对象分配增长）。

---

### Task 6: 更新报告

**Files:**
- Modify: `挖孔环形电量LSP模块-可行性分析报告.md`（在 §12 内追加 `### 12.6 动画时钟与过渡时长`）

- [ ] **Step 1: 追加 §12.6**

在 §12 的 `### 12.5 已知限制` 之前插入：

```markdown
### 12.6 动画时钟与过渡时长（2026-10-01 补充）

**变色过渡对不齐的根因**：取色的时间基准错了 + 时长读错了地方。

**时钟**：`LightBarTransitionsController` 的 ValueAnimator（`Interpolators.LINEAR`）
每帧回调 applier 的 `applyDarkIntensity(float)`，applier 写 `mDarkIntensity`
后再经 `applyIconTint()` 广播给所有 `DarkReceiver.onDarkChanged(...)`。

状态栏的 applier 是 `com.android.systemui.statusbar.phone.DarkIconDispatcherImpl`
（同时实现 `DarkIntensityApplier` 的 `applyDarkIntensity` 与 `getTintAnimationDuration`）。
**挂在电池图标 View 的 `onDarkChangeInternal()` 上是不行的**——那是渲染端，
中间还隔着 `DarkIconDispatcherExt.getDarkIntensity(ArrayList, View, float)`
（内部为 `dispatcher.isInAreas(tintAreas, view) ? 全局强度 : 默认值`），
View 是否每帧重绘不是可靠的时间基准。

**时长**：`DarkIconDispatcherImpl.getTintAnimationDuration()` 的返回值依赖
`com.miui.utils.ComputilityUtils` 的 `isLowLevel` / `isMidLowLevel` /
`isSubMidLevel` / `isNormalLevel`——**按设备性能档位分级、运行期计算**。

⚠️ `LightBarTransitionsController.DEFAULT_TINT_ANIMATION_DURATION` **不是编译期常量**：
`work/probe.py static` 实测该类的 `static_values_off = 0`，它在 `<clinit>` 里运行时赋值。
早期实现反射读它并硬编码 250ms 兜底，是过渡时长对不齐的直接原因，已废弃。

**教训**：MIUI 的动画时长几乎都是运行期按档位/场景算出来的，
不要试图用静态常量或硬编码去"对齐"，要在运行期捕获系统真正用的值。

**工具**：`work/probe.py`（静态常量值 / 方法实现者 / 原始指令码）、
`work/method_refs.py`（方法体内字段与方法引用）。
⚠️ 指令码宽度表未与 ART 校验，手工反汇编解出的字段索引可能与
`method_refs.py` 冲突，**只当线索不当事实**，结论以真机 logcat 为准。
```

- [ ] **Step 2: 复核改动集**

```powershell
git status --short
git diff --stat
```
Expected: 只多出本计划「文件结构总览」列的文件；工作区里报告 §11 与
`docs/superpowers/plans/2026-10-01-ring-window-layer-priority.md` 是别人的未提交改动，**不要回滚也不要一起提交**。

---

## 自检记录

- **需求覆盖**：「过渡时间与系统对齐」→ Task 3（时间基准换到 `applyDarkIntensity`）+ Task 4（时长换成系统真实值 + 分支策略）。
- **不靠猜**：Task 1 先量，Task 3/4 的分支由量测结果决定；Task 1 明确指出手工反汇编不可信并给了两个正交证据（`static_values_off=0`、`getTintAnimationDuration` 依赖 `ComputilityUtils` 档位），这两条都来自 `work/probe.py` 与 `work/method_refs.py` 的可复现输出。
- **类型一致性**：`SystemBatteryColors.fromClock`（Task 2 定义）→ `BatteryPalette.intensityFromClock`（Task 2 定义）→ `RingState` 的 `continuous` 变量（Task 4 Step 3 消费）三者语义一致；`BatteryColorHook.publish(fromClock: Boolean = false)`（Task 3）与 4 处既有无参调用兼容。
- **无占位符**：每个代码步骤给出可直接粘贴的完整代码块，每条命令给出期望输出；Task 1 Step 7 用表格列出四种量测结果各自影响哪个后续任务，不留"看情况再定"。
