# 下拉通知栏/控制中心时收起电量环 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用户下拉通知栏或控制中心时，挖孔电量环跟随面板拉起进度向内收缩并淡出；面板收起后弹回。设置页提供独立开关，默认关闭。

**Architecture:** 复用既有的单条收起通道 `RingState.collapseProgress`（0f 完整显示 / 1f 完全收缩），新增第三条通路：面板拉起 fraction。收起决策由沉浸收起、灵动岛、面板拉起三者合并（取最大值）。信号来源优先尝试中央分发点 `ShadeExpansionStateManager.onPanelExpansionChanged(FZZ)`，用一次性探针 Hook 在真机上确认真实触发方，再决定长期保留的信号；探针确认为误报或从不触发的候选一律删除。

**Tech Stack:** Kotlin、Xposed/LSPosed（LSPosed 编译桩 `xposedstub`，仅 `compileOnly`）、miuix（`SwitchPreference`）、JUnit4 JVM 单测。

---

## 可行性结论（先回答"能不能做到"）

**能做到，且与现有架构天然吻合。** 三点依据：

1. **载体层级决定了必须主动隐藏。** 本机实测（可行性分析报告 §11）：环窗口 `type=2006` / 层带 231000，`NotificationShade` 窗口 `ty=NOTIFICATION_SHADE` / `mBaseLayer=171000`（2026-10-03 `dumpsys window windows` 复核）。环**压在面板之上**，下拉时环会浮在通知/控制中心内容表面。环窗口带 `FLAG_NOT_TOUCHABLE`，不抢触摸、不影响下拉手势——所以这不是交互冲突问题，纯视觉问题，隐藏动作完全由我们自己驱动即可。
2. **收起通道已存在，新增一条通路成本极低。** `RingState.collapseTarget()` 现在合并"沉浸收起"和"灵动岛显示"两条布尔通路，输出 0f/1f；`animateCollapseTo()` 已支持"差值 <0.01 直接到位，否则 260ms `DecelerateInterpolator(1.5f)` 动画"。面板拉起的 `ShadeExpansionChangeEvent.fraction` 是连续的 0f..1f，直接喂进去就是"手指拖多少、环收多少"的跟随效果，无需新增动画器。
3. **宿主侧有明确的信号候选。** 见下表，全部在 SystemUI APK 自身的 dex 里（`work/sysui/classes*.dex`），可用 `SystemUiHooks.install(classLoader)` 传入的宿主 ClassLoader 直接 `findClassIfExists` 拿到，**不涉及插件 ClassLoader、不涉及 `ClassLoader.loadClass` 回调**——即不触碰 2026-10-01 那次 SystemUI ANR 死锁的红线（AGENTS.md 纪律 8）。

### 信号候选（2026-10-03 静态取证，`work/find_classes.py` / `dump_class.py` / 同-dex xref）

| 候选 | 所在 dex | 签名 | 语义 | 静态取证结果 | 评价 |
|---|---|---|---|---|---|
| `com.android.systemui.shade.ShadeExpansionStateManager` | classes2.dex | `onPanelExpansionChanged(float fraction, boolean expanded, boolean tracking)V` | 面板拉起进度中央分发点，逐帧携带 fraction | 同-dex 内**未**扫到直接调用方（推测经接口/跨 dex 调用）；它自身是 `ShadeExpansionListener.onPanelExpansionChanged$1` 的唯一分发者 | **首选**：一个点位覆盖 shade + QS，且带连续 fraction |
| 同上 | classes2.dex | `updateStateInternal(int)V`；字段 `expanded Z` / `fraction F` / `tracking Z` / `state I` | 状态机切换 | 被 `onPanelExpansionChanged(FZZ)` 调用 | 备用：布尔语义，无 fraction |
| `com.miui.systemui.shade.NotificationShadeWrapper` | classes3.dex | `onPanelExpanded(Z)V`、`updateExpanded()V`、`getExpanded()Z`、字段 `_expanded Z`/`expanded Z` | 实现 `IPanelExpansionObserver$NotificationPanelExpansionListener`，通知面板/控制中心各一实例 | `onPanelExpanded(Z)` → `updateExpanded()`；`onStateChanged(I)` → `updateExpanded()` | 次选：miui 侧真实通路，但要处理"两个实例"，且无 fraction |
| `com.miui.systemui.controlcenter.container.ControlCenterContainerController$onExpandChangeListener$1` | classes3.dex | `onExpandStateChanged(Lcom/android/systemui/plugins/miui/controlcenter/ControlCenterContent$ExpandState;)V` | 控制中心展开态 | 由 `ControlCenterContainerController` 持有 | 仅覆盖控制中心，参数是枚举需额外解析；不优先 |
| `com.android.systemui.shade.NotificationShadeWindowControllerImpl` | classes2.dex | `onShadeOrQsExpanded(Ljava/lang/Boolean;)V` | 名称即"shade 或 QS 已展开"的合并信号 | 唯一同-dex 调用方是 R8 合成 lambda `$$ExternalSyntheticLambda4.accept(Object)`，`$r8$classId` 多路复用 | 风险：可能只在流收集时派发，未必逐帧；布尔无 fraction |

**结论**：首选候选无法用纯静态手段证明"下拉时一定触发"，因此本计划把**真机探针**放在第一个任务，先取证再落地——这与仓库既有工作方式（先动态验证再写结论并回报告）一致。

## 风险与取舍

| 风险 | 说明 | 处置 |
|---|---|---|
| 探针信号误报 | 某候选可能在不该触发时触发（如锁屏、开机动画、控制中心与通知面板左右互切） | 探针只打日志不驱动环；确认为误报的候选**直接删除**，不留降级分支 |
| 逐帧回调拖慢 SystemUI | 首选点位在拖拽期间约每帧一次 | 回调内只做：类型转换 + 阈值比较（`abs(delta) < 0.02f` 直接 return）+ 已有动画器复用；不反射、不枚举、不分配日志字符串 |
| 锁屏/指纹面板/开机动画误触发 | 这些场景也走 shade 窗口体系 | 真机验证矩阵单列一项，逐项确认；有问题就在驱动侧加条件（如仅在 `screenOn && !keyguard` 时采信） |
| 与"截屏时隐藏环"叠加 | 下拉时收起环后，用指关节下拉截图会得到"没有环"的截图 | 属预期行为，在设置项 summary 里说明 |
| 沉浸收起期间下拉 | 下拉会让状态栏瞬时可见（沉浸信号回到 SHOWING），此时若面板 fraction 也驱动收起，可能出现"弹回又收起"的抖动 | fraction 与沉浸通路取 `max`，抖动由既有 260ms 动画吸收；真机验证矩阵专项观察 |
| 版本敏感 | 类名/签名仅适配系统界面 17.03.260226.r | 沿用既有降级方向：找不到类/方法 → 记日志安静放弃 → **环保持常显**（远比"该藏没藏"安全） |

## 文件结构总览

| 文件 | 动作 | 职责 |
|---|---|---|
| `HolePowerRing/app/src/main/java/com/powerring/hole/hook/ShadeCollapseHook.kt` | 新建 | 面板拉起探针（Task 2 起改为驱动信号，并删除未证实的候选） |
| `HolePowerRing/app/src/test/java/com/powerring/hole/ring/RingCollapseTest.kt` | 新建 | 三通路合并 + fraction 阈值去重的纯逻辑单测 |
| `HolePowerRing/app/src/main/java/com/powerring/hole/ring/RingCollapseLogic.kt` | 新建 | 把收起决策从 `RingState` 抽成无 Android 依赖的纯函数，供 JVM 单测 |
| `HolePowerRing/app/src/main/java/com/powerring/hole/ring/RingConfig.kt` | 修改 | `collapseOnShade` 字段 + `KEY_COLLAPSE_ON_SHADE` 常量 |
| `HolePowerRing/app/src/main/java/com/powerring/hole/ring/RingState.kt` | 修改 | 新增 `setShadeCollapse(Float)`；`collapseTarget()` 走纯函数；配置签名纳入新键 |
| `HolePowerRing/app/src/main/java/com/powerring/hole/core/HookPrefs.kt` | 修改 | 跨进程读取新键 |
| `HolePowerRing/app/src/main/java/com/powerring/hole/ui/ConfigProvider.kt` | 修改 | provider 暴露新列（`valueOf` + `ALL_KEYS`） |
| `HolePowerRing/app/src/main/java/com/powerring/hole/ui/PrefsStore.kt` | 修改 | 设置页读取新键 |
| `HolePowerRing/app/src/main/java/com/powerring/hole/ui/SettingsScreen.kt` | 修改 | 新增开关行 |
| `HolePowerRing/app/src/main/java/com/powerring/hole/hook/SystemUiHooks.kt` | 修改 | 安装探针/驱动 Hook |
| `挖孔环形电量LSP模块-可行性分析报告.md` | 修改 | 新增 §18（§13 已被"电池图标取色链路"预留，勿占用） |
| `AGENTS.md` | 修改 | 「已知待验证项」同步结论 |

不做的事：不改 `ConfigJson`（只导出外观项，行为开关不进契约）；不引入新的跨进程通信；不动 `apks/`；不把 Xposed 真实依赖改成 `implementation`。

**本计划全程不执行 `git commit`**（用户约定：改动完成后由用户自行决定提交时机）。每个任务末尾只做构建/测试验证。

---

### Task 1: 真机探针——确认面板拉起信号

目标：装一个只打日志、不改变环行为的 Hook，真机下拉一次，产出"哪个候选真的触发、参数长什么样"的证据。**本任务的探针代码在 Task 2 会被收敛（删除未证实候选），不要在 Task 1 就接 `RingState`。**

**Files:**
- Create: `HolePowerRing/app/src/main/java/com/powerring/hole/hook/ShadeCollapseHook.kt`
- Modify: `HolePowerRing/app/src/main/java/com/powerring/hole/hook/SystemUiHooks.kt`（安装调用）

- [ ] **Step 1: 新建探针文件**

写入 `HolePowerRing/app/src/main/java/com/powerring/hole/hook/ShadeCollapseHook.kt`：

```kotlin
package com.powerring.hole.hook

import com.powerring.hole.core.ModuleLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers

/**
 * 面板拉起探针（临时取证用，不驱动环）。
 *
 * 目的：确认下拉通知栏 / 控制中心时，宿主侧哪个候选真的被调用、参数语义如何，
 * 再决定长期保留的信号（可行性分析报告 §18）。
 *
 * 静态取证（2026-10-03，work/sysui）：
 * - com.android.systemui.shade.ShadeExpansionStateManager.onPanelExpansionChanged(FZZ)
 *   字段 expanded/fraction/tracking/state，是 ShadeExpansionListener 的中央分发点；
 * - com.miui.systemui.shade.NotificationShadeWrapper.onPanelExpanded(Z) → updateExpanded()，
 *   字段 _expanded/expanded，通知面板与控制中心各一个实例；
 * - com.android.systemui.shade.NotificationShadeWindowControllerImpl.onShadeOrQsExpanded(Boolean)
 *   唯一同-dex 调用方是 R8 合成 lambda，派发时机存疑。
 *
 * 纪律：回调内只做字符串比较与日志，绝不反射枚举、绝不安装 Hook、绝不驱动 UI 逻辑；
 * 全部逻辑包 try/catch (Throwable) + ModuleLog，异常不得逃逸到 SystemUI。
 */
object ShadeCollapseHook {

    private const val STATE_MANAGER =
        "com.android.systemui.shade.ShadeExpansionStateManager"
    private const val SHADE_WRAPPER =
        "com.miui.systemui.shade.NotificationShadeWrapper"
    private const val SHADE_WINDOW_CTRL =
        "com.android.systemui.shade.NotificationShadeWindowControllerImpl"

    /** 诊断日志上限，避免拖拽期间逐帧刷屏 */
    private const val MAX_DIAG_LINES = 200

    private var diagLines = 0
    private val lastByKey = HashMap<String, String>()

    fun install(classLoader: ClassLoader) {
        try {
            hookStateManager(classLoader)
            hookWrapper(classLoader)
            hookWindowController(classLoader)
        } catch (t: Throwable) {
            ModuleLog.e("面板探针安装异常", t)
        }
    }

    private fun hookStateManager(classLoader: ClassLoader) {
        val cls = runCatching {
            XposedHelpers.findClassIfExists(STATE_MANAGER, classLoader)
        }.getOrNull()
        if (cls == null) {
            ModuleLog.i("面板探针：未找到 $STATE_MANAGER")
            return
        }
        try {
            XposedHelpers.findAndHookMethod(
                cls, "onPanelExpansionChanged",
                Float::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val fraction = param.args[0] as Float
                            val expanded = param.args[1] as Boolean
                            val tracking = param.args[2] as Boolean
                            val inst = System.identityHashCode(param.thisObject)
                            diag(
                                "stateManager",
                                "inst=$inst fraction=$fraction expanded=$expanded " +
                                    "tracking=$tracking",
                            )
                        } catch (t: Throwable) {
                            ModuleLog.e("面板探针 stateManager 异常", t)
                        }
                    }
                },
            )
            ModuleLog.i("面板探针已挂载 ${cls.name}.onPanelExpansionChanged")
        } catch (t: Throwable) {
            ModuleLog.e("面板探针挂载 onPanelExpansionChanged 失败", t)
        }
    }

    private fun hookWrapper(classLoader: ClassLoader) {
        val cls = runCatching {
            XposedHelpers.findClassIfExists(SHADE_WRAPPER, classLoader)
        }.getOrNull()
        if (cls == null) {
            ModuleLog.i("面板探针：未找到 $SHADE_WRAPPER")
            return
        }
        try {
            XposedHelpers.findAndHookMethod(
                cls, "onPanelExpanded",
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val expanded = param.args[0] as Boolean
                            val inst = System.identityHashCode(param.thisObject)
                            val field = runCatching {
                                XposedHelpers.getBooleanField(param.thisObject, "expanded")
                            }.getOrDefault(false)
                            diag("wrapper", "inst=$inst arg=$expanded 调用后 expanded=$field")
                        } catch (t: Throwable) {
                            ModuleLog.e("面板探针 wrapper 异常", t)
                        }
                    }
                },
            )
            ModuleLog.i("面板探针已挂载 ${cls.name}.onPanelExpanded")
        } catch (t: Throwable) {
            ModuleLog.e("面板探针挂载 onPanelExpanded 失败", t)
        }
    }

    private fun hookWindowController(classLoader: ClassLoader) {
        val cls = runCatching {
            XposedHelpers.findClassIfExists(SHADE_WINDOW_CTRL, classLoader)
        }.getOrNull()
        if (cls == null) {
            ModuleLog.i("面板探针：未找到 $SHADE_WINDOW_CTRL")
            return
        }
        try {
            XposedHelpers.findAndHookMethod(
                cls, "onShadeOrQsExpanded", java.lang.Boolean::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            diag("windowCtrl", "arg=${param.args[0]}")
                        } catch (t: Throwable) {
                            ModuleLog.e("面板探针 windowCtrl 异常", t)
                        }
                    }
                },
            )
            ModuleLog.i("面板探针已挂载 ${cls.name}.onShadeOrQsExpanded")
        } catch (t: Throwable) {
            ModuleLog.e("面板探针挂载 onShadeOrQsExpanded 失败", t)
        }
    }

    /** 有上限、按来源去重的诊断日志。 */
    private fun diag(source: String, detail: String) {
        if (diagLines >= MAX_DIAG_LINES) return
        if (lastByKey[source] == detail) return
        lastByKey[source] = detail
        diagLines++
        ModuleLog.i("面板探针[$source]: $detail")
    }
}
```

> 注意：`wrapper` 分支读的是该类自己的 `expanded Z` 字段（`_expanded` 是 Kotlin backing field，语义存疑，故读公开的那个）。`getBooleanField` 用 `runCatching` 包着，取不到时回落 `false`——若日志里 `调用后 expanded=` 恒为 `false` 而 `arg=` 有 true/false 翻转，说明该字段不是权威状态，Task 2 收敛时改读 `arg` 即可。

- [ ] **Step 2: 在 SystemUiHooks 安装探针**

修改 `HolePowerRing/app/src/main/java/com/powerring/hole/hook/SystemUiHooks.kt`，在截图采集探针之后、`Application.onCreate` Hook 之前插入：

```kotlin
        // 面板拉起探针（临时取证：确认下拉通知栏/控制中心的真实可用信号，不驱动环）
        try {
            ShadeCollapseHook.install(classLoader)
        } catch (t: Throwable) {
            ModuleLog.e("面板探针安装异常", t)
        }
```

- [ ] **Step 3: 构建并安装**

```bash
cd HolePowerRing
./gradlew assembleDebug
./gradlew installDebug
```

Expected: `BUILD SUCCESSFUL`；APK 落在 `app/build/outputs/apk/debug/app-debug.apk`。若模块尚未在 LSPosed 中启用，需用户先在 LSPosed 管理器勾选（作用域 `com.android.systemui`）。

- [ ] **Step 4: 重启 SystemUI 并开日志观察**

```bash
adb -s adb-6f1adaee-K5bSQq._adb-tls-connect._tcp shell killall com.android.systemui
adb -s adb-6f1adaee-K5bSQq._adb-tls-connect._tcp logcat -c
adb -s adb-6f1adaee-K5bSQq._adb-tls-connect._tcp logcat -s HolePowerRing
```

Expected: SystemUI 重启后先看到 `面板探针已挂载 com.android.systemui.shade.ShadeExpansionStateManager.onPanelExpansionChanged` 之类的挂载行。

- [ ] **Step 5: 请用户执行取证动作（需用户同意，设备是其主力机）**

请用户依次做一次，每次之间回到桌面：

1. 从状态栏左侧下拉**通知面板**，停留 2 秒，上滑收起；
2. 从状态栏右侧下拉**控制中心**，停留 2 秒，上滑收起；
3. 下拉通知面板后**横向滑动切到控制中心**（走 `ShadeSwitchControllerImpl` 通路）；
4. 锁屏状态下**下拉一次**，再上滑收起；
5. 打开一个App进入**沉浸全屏**（状态栏自动收起）后再下拉。

同时抓一份静态证据备用（面板展开态时的窗口可见性，与收起态对比）：

```bash
adb -s adb-6f1adaee-K5bSQq._adb-tls-connect._tcp shell "dumpsys window windows | grep -A 40 'Window{.*NotificationShade}' | grep -E 'mHasSurface|isVisible'"
```

Expected（判读标准）：
- 步骤 1/2 期间 `面板探针[stateManager]` 出现 `fraction` 从 0 递增、`expanded=true`、`tracking=true`，收起时反向回到 `expanded=false` → **首选信号成立**；
- 只出现 `wrapper` 或 `windowCtrl` 而 `stateManager` 全程静默 → 首选信号在本机不触发，改用被证实的那个（`windowCtrl` 为布尔则放弃 fraction 跟随，退化为 0f/1f 硬切换）；
- 三者都静默 → 本功能不做，把探针删除并回写报告"信号不可用"。

- [ ] **Step 6: 记录证据**

把五个步骤的原始日志（至少每个候选的首末各 3 行）粘进本计划末尾的「## 探针取证结果」小节，标注每段对应的用户动作。后续 Task 2/Task 6 的决策以这份记录为准，不凭印象。

---

### Task 2: 收起决策纯逻辑（TDD）

目标：把"三通路合并"的规则从 `RingState`（依赖 Android，JVM 测不了）抽成纯函数并先写失败测试。**本任务先落地逻辑层，Task 3 才接线。**

**Files:**
- Create: `HolePowerRing/app/src/test/java/com/powerring/hole/ring/RingCollapseTest.kt`
- Create: `HolePowerRing/app/src/main/java/com/powerring/hole/ring/RingCollapseLogic.kt`
- Modify: `HolePowerRing/app/src/main/java/com/powerring/hole/ring/RingConfig.kt`

- [ ] **Step 1: 写失败测试**

写入 `HolePowerRing/app/src/test/java/com/powerring/hole/ring/RingCollapseTest.kt`：

```kotlin
package com.powerring.hole.ring

import org.junit.Assert.assertEquals
import org.junit.Test

class RingCollapseTest {

    @Test
    fun `all paths off returns zero`() {
        assertEquals(
            0f,
            RingCollapseLogic.target(
                statusBarCollapsed = false, collapseOnImmersive = false,
                islandShowing = false, collapseOnIsland = false,
                shadeFraction = 0f, collapseOnShade = false,
            ),
            0.0001f,
        )
    }

    @Test
    fun `immersive path collapses fully when enabled`() {
        assertEquals(
            1f,
            RingCollapseLogic.target(
                statusBarCollapsed = true, collapseOnImmersive = true,
                islandShowing = false, collapseOnIsland = false,
                shadeFraction = 0f, collapseOnShade = true,
            ),
            0.0001f,
        )
    }

    @Test
    fun `island path collapses fully when enabled`() {
        assertEquals(
            1f,
            RingCollapseLogic.target(
                statusBarCollapsed = false, collapseOnImmersive = true,
                islandShowing = true, collapseOnIsland = true,
                shadeFraction = 0.2f, collapseOnShade = true,
            ),
            0.0001f,
        )
    }

    @Test
    fun `shade fraction is followed when enabled`() {
        assertEquals(
            0.6f,
            RingCollapseLogic.target(
                statusBarCollapsed = false, collapseOnImmersive = true,
                islandShowing = false, collapseOnIsland = true,
                shadeFraction = 0.6f, collapseOnShade = true,
            ),
            0.0001f,
        )
    }

    @Test
    fun `shade switch off ignores fraction`() {
        assertEquals(
            0f,
            RingCollapseLogic.target(
                statusBarCollapsed = false, collapseOnImmersive = true,
                islandShowing = false, collapseOnIsland = true,
                shadeFraction = 0.9f, collapseOnShade = false,
            ),
            0.0001f,
        )
    }

    @Test
    fun `shade takes the strongest path`() {
        // 沉浸已收起（1f），面板只拖到 0.3f：应保持 1f，不能把环弹回来
        assertEquals(
            1f,
            RingCollapseLogic.target(
                statusBarCollapsed = true, collapseOnImmersive = true,
                islandShowing = false, collapseOnIsland = false,
                shadeFraction = 0.3f, collapseOnShade = true,
            ),
            0.0001f,
        )
    }

    @Test
    fun `fraction out of range is clamped`() {
        assertEquals(
            1f,
            RingCollapseLogic.target(
                statusBarCollapsed = false, collapseOnImmersive = false,
                islandShowing = false, collapseOnIsland = false,
                shadeFraction = 1.7f, collapseOnShade = true,
            ),
            0.0001f,
        )
        assertEquals(
            0f,
            RingCollapseLogic.target(
                statusBarCollapsed = false, collapseOnImmersive = false,
                islandShowing = false, collapseOnIsland = false,
                shadeFraction = -0.5f, collapseOnShade = true,
            ),
            0.0001f,
        )
    }

    @Test
    fun `tiny change is ignored but a real move is accepted`() {
        assertEquals(false, RingCollapseLogic.shouldEmit(0.50f, 0.51f))
        assertEquals(true, RingCollapseLogic.shouldEmit(0.50f, 0.53f))
        assertEquals(true, RingCollapseLogic.shouldEmit(0.50f, 0.00f))
    }
}
```

- [ ] **Step 2: 运行确认失败**

```bash
cd HolePowerRing
./gradlew test --tests "com.powerring.hole.ring.RingCollapseTest"
```

Expected: 编译失败，`unresolved reference: RingCollapseLogic`。

- [ ] **Step 3: 写最小实现**

写入 `HolePowerRing/app/src/main/java/com/powerring/hole/ring/RingCollapseLogic.kt`：

```kotlin
package com.powerring.hole.ring

/**
 * 圆环收起决策（无 Android 依赖，便于 JVM 单测）。
 *
 * 三条通路取最强值：沉浸收起与灵动岛是布尔（要么 0 要么 1），
 * 面板拉起携带连续 fraction，可跟随手指进度。
 */
internal object RingCollapseLogic {

    /** 小于该值的进度变化不值得重绘（拖拽期间每帧回调） */
    const val EPSILON = 0.02f

    fun target(
        statusBarCollapsed: Boolean,
        collapseOnImmersive: Boolean,
        islandShowing: Boolean,
        collapseOnIsland: Boolean,
        shadeFraction: Float,
        collapseOnShade: Boolean,
    ): Float {
        val byImmersive = if (statusBarCollapsed && collapseOnImmersive) 1f else 0f
        val byIsland = if (islandShowing && collapseOnIsland) 1f else 0f
        val byShade = if (collapseOnShade) shadeFraction.coerceIn(0f, 1f) else 0f
        return maxOf(byImmersive, byIsland, byShade)
    }

    fun shouldEmit(current: Float, incoming: Float): Boolean =
        kotlin.math.abs(current - incoming) >= EPSILON
}
```

- [ ] **Step 4: 运行确认通过**

```bash
cd HolePowerRing
./gradlew test --tests "com.powerring.hole.ring.RingCollapseTest"
```

Expected: `BUILD SUCCESSFUL`，8 个测试全绿。

- [ ] **Step 5: 给 RingConfig 增加开关**

修改 `HolePowerRing/app/src/main/java/com/powerring/hole/ring/RingConfig.kt`：在 `collapseOnIsland` 字段之后插入

```kotlin
    /**
     * 下拉通知栏/控制中心时，圆环跟随面板拉起进度向内收缩并淡出。
     * 默认关闭：环窗口（层带 231000）压在面板（171000）之上，
     * 开启后面板展开期间环不可见，指关节截图会同时不含环。
     */
    val collapseOnShade: Boolean = false,
```

在常量区 `KEY_COLLAPSE_ON_ISLAND` 之后插入

```kotlin
        const val KEY_COLLAPSE_ON_SHADE = "collapse_on_shade"
```

---

### Task 3: RingState 接线 + 配置链路

**Files:**
- Modify: `HolePowerRing/app/src/main/java/com/powerring/hole/ring/RingState.kt`（约 44-62、126-139、354-381 行）
- Modify: `HolePowerRing/app/src/main/java/com/powerring/hole/core/HookPrefs.kt:143-149`
- Modify: `HolePowerRing/app/src/main/java/com/powerring/hole/ui/ConfigProvider.kt:47-72`、`:94-100`
- Modify: `HolePowerRing/app/src/main/java/com/powerring/hole/ui/PrefsStore.kt:24-53`

- [ ] **Step 1: RingState 新增 fraction 字段**

在 `RingState.kt` 的「收起通路」区块，`statusBarCollapsed` 声明之后插入：

```kotlin
    /** 最近一次上报的面板（通知栏/控制中心）拉起进度，0f 收起 / 1f 完全展开 */
    @Volatile
    private var shadeCollapseFraction: Float = 0f
```

- [ ] **Step 2: collapseTarget 改走纯函数**

把 `RingState.kt` 中的 `collapseTarget()` 整体替换为：

```kotlin
    /** 三条收起通路合并成一个目标值：沉浸收起、灵动岛显示、面板拉起，取最强者。 */
    private fun collapseTarget(): Float = RingCollapseLogic.target(
        statusBarCollapsed = statusBarCollapsed,
        collapseOnImmersive = config.collapseOnImmersive,
        islandShowing = islandShowing,
        collapseOnIsland = config.collapseOnIsland,
        shadeFraction = shadeCollapseFraction,
        collapseOnShade = config.collapseOnShade,
    )
```

- [ ] **Step 3: animateCollapseTo 的门控纳入新开关**

把 `animateCollapseTo` 里的 `noCollapseFeature` 一行替换为：

```kotlin
        val noCollapseFeature = !config.collapseOnImmersive && !config.collapseOnIsland &&
            !config.collapseOnShade
```

- [ ] **Step 4: 新增驱动入口 setShadeCollapse**

在 `setIslandShowing` 之后插入（与既有两个 setter 同构，回调内不反射、不重活）：

```kotlin
    /**
     * 通知栏/控制中心拉起进度（0f..1f）。
     * 由 ShadeCollapseHook 在 SystemUI 主线程逐帧驱动；变化小于阈值时忽略，
     * 避免拖拽期间每帧无谓重绘。fraction 语义下 1f 目标本身即"已到位"，
     * animateCollapseTo 的 <0.01 分支会让它直接落值、不再走 260ms 动画。
     */
    fun setShadeCollapse(fraction: Float) {
        val clamped = fraction.coerceIn(0f, 1f)
        if (!RingCollapseLogic.shouldEmit(shadeCollapseFraction, clamped)) return
        shadeCollapseFraction = clamped
        val target = collapseTarget()
        ModuleLog.i("面板拉起进度: fraction=$clamped 收起目标=$target")
        animateCollapseTo(target)
    }
```

- [ ] **Step 5: 配置签名纳入新键**

把 `RingState.kt` 中 `RingConfig.signature()` 的收起那行替换为：

```kotlin
            "$collapseOnImmersive|$collapseOnIsland|$collapseOnShade|" +
```

- [ ] **Step 6: HookPrefs 读取新键**

`core/HookPrefs.kt` 中，在 `collapseOnIsland = toBool(...)` 之后插入：

```kotlin
            collapseOnShade = toBool(values[RingConfig.KEY_COLLAPSE_ON_SHADE], d.collapseOnShade),
```

- [ ] **Step 7: ConfigProvider 暴露新列**

`ui/ConfigProvider.kt` 的 `valueOf` 里，在 `KEY_COLLAPSE_ON_ISLAND` 分支之后插入：

```kotlin
        RingConfig.KEY_COLLAPSE_ON_SHADE ->
            prefs.getBoolean(key, RingConfig.DEFAULT.collapseOnShade)
```

同文件 `ALL_KEYS` 里，在 `RingConfig.KEY_COLLAPSE_ON_ISLAND,` 之后插入：

```kotlin
            RingConfig.KEY_COLLAPSE_ON_SHADE,
```

- [ ] **Step 8: PrefsStore 读取新键**

`ui/PrefsStore.kt` 的 `load()` 里，在 `collapseOnIsland = ...` 之后插入：

```kotlin
            collapseOnShade = p.getBoolean(RingConfig.KEY_COLLAPSE_ON_SHADE, false),
```

- [ ] **Step 9: 跑全量单测**

```bash
cd HolePowerRing
./gradlew test
```

Expected: `BUILD SUCCESSFUL`，含新增 `RingCollapseTest` 在内的全部 JVM 单测通过。

---

### Task 4: 探针收敛为驱动信号

依据 Task 1 Step 6 的记录，**只保留被真机证实的那个候选**，其余删除。下面给出首选候选（`ShadeExpansionStateManager`）成立时的最终形态；若取证结果指向别的候选，按下文「变体」改写，结构不变。

**Files:**
- Modify: `HolePowerRing/app/src/main/java/com/powerring/hole/hook/ShadeCollapseHook.kt`

- [ ] **Step 1: 改写为驱动版本**

用以下内容整体替换 `ShadeCollapseHook.kt`（类名与文件头保留，注释按取证结论修正）：

```kotlin
package com.powerring.hole.hook

import com.powerring.hole.core.ModuleLog
import com.powerring.hole.ring.RingState
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers

/**
 * 面板拉起收起信号：下拉通知栏/控制中心时驱动圆环收缩。
 *
 * 信号源（Task 1 真机探针取证，详见可行性分析报告 §18）：
 * `com.android.systemui.shade.ShadeExpansionStateManager.onPanelExpansionChanged(
 *     float fraction, boolean expanded, boolean tracking)`
 * 该方法是 `ShadeExpansionListener` 的中央分发点，通知面板与控制中心共用，
 * 逐帧携带 0f..1f 进度；位于 SystemUI APK 自身的 classes2.dex，
 * 用宿主 ClassLoader 直接取类，不触碰插件 ClassLoader、不在 loadClass 回调里干活。
 *
 * 降级方向：类/方法找不到 → 记日志安静放弃 → 环保持常显。
 * 纪律：回调内只做取值、阈值判断与驱动，绝不反射枚举、绝不安装 Hook；
 * 全部包 try/catch (Throwable) + ModuleLog，异常不得逃逸到 SystemUI。
 */
object ShadeCollapseHook {

    private const val STATE_MANAGER =
        "com.android.systemui.shade.ShadeExpansionStateManager"

    @Volatile
    private var installed = false

    /** 上一次驱动出去的进度，用于变化去重与日志节流 */
    private var lastFraction = Float.MIN_VALUE

    fun install(classLoader: ClassLoader) {
        if (installed) return
        installed = true
        val cls = runCatching {
            XposedHelpers.findClassIfExists(STATE_MANAGER, classLoader)
        }.getOrNull()
        if (cls == null) {
            ModuleLog.e("未找到 $STATE_MANAGER，下拉收起圆环未生效（环保持常显）", null)
            return
        }
        try {
            XposedHelpers.findAndHookMethod(
                cls, "onPanelExpansionChanged",
                Float::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val fraction = param.args[0] as Float
                            val expanded = param.args[1] as Boolean
                            // 面板已完全收起：强制归零，避免收尾帧 fraction 残留在 0.0x
                            val target = if (expanded) fraction else 0f
                            if (target == lastFraction) return
                            lastFraction = target
                            RingState.setShadeCollapse(target)
                        } catch (t: Throwable) {
                            ModuleLog.e("面板收起信号回调异常", t)
                        }
                    }
                },
            )
            ModuleLog.i("面板收起信号已挂载 ${cls.name}.onPanelExpansionChanged")
        } catch (t: Throwable) {
            ModuleLog.e("Hook onPanelExpansionChanged 失败（环保持常显）", t)
        }
    }
}
```

`SystemUiHooks.kt` 里 Task 1 Step 2 的安装调用保留，只把注释从"探针（临时取证）"改为"面板拉起收起信号（驱动 `RingState.setShadeCollapse`）"。

两层去重是有意为之、不是重复劳动：Hook 侧 `target == lastFraction` 拦掉"完全相同值"的连续帧（省掉跨对象调用与日志格式化）；`RingState.setShadeCollapse` 里的 `RingCollapseLogic.shouldEmit`（`EPSILON=0.02f`）拦掉"有微小抖动但视觉无差别"的变化，并保证沉浸/灵动岛两条布尔通路跳变时仍能被驱动（它们不经过 Hook 侧这层）。

- [ ] **Step 2: 变体（仅当取证结果不是首选候选时执行）**

- 取证显示只有 `NotificationShadeWrapper` 触发：保留 `hookWrapper` 分支，去掉状态管理器；从字段读 `expanded`（`XposedHelpers.getBooleanField(param.thisObject, "expanded")`），驱动 `RingState.setShadeCollapse(if (expanded) 1f else 0f)`；`lastFraction` 仍是同一个 Float 去重器，两个实例（通知/控制中心）共用它即可，因为语义是"任一面板展开就收"。
- 取证显示只有 `NotificationShadeWindowControllerImpl.onShadeOrQsExpanded(Boolean)` 触发：同样退化为 0f/1f，参数直接 `(param.args[0] as? Boolean) == true`。
- 三个候选都不触发：删除 `ShadeCollapseHook.kt` 与 `SystemUiHooks` 的安装调用，回写报告"本机型无可用宿主信号，功能不做"。此路径下 Task 2/3 的纯逻辑与配置项**一并回退**（不留无人使用的开关）。

- [ ] **Step 3: 编译**

```bash
cd HolePowerRing
./gradlew assembleDebug
```

Expected: `BUILD SUCCESSFUL`。

---

### Task 5: 设置页开关

**Files:**
- Modify: `HolePowerRing/app/src/main/java/com/powerring/hole/ui/SettingsScreen.kt:223-233`

- [ ] **Step 1: 在「有岛时隐藏圆环」之后插入开关行**

`SettingsScreen.kt` 中，`collapseOnIsland` 那个 `SwitchPreference` 之后已有 `HorizontalDivider`，在其后插入：

```kotlin
                    SwitchPreference(
                        checked = config.collapseOnShade,
                        onCheckedChange = { enabled ->
                            PrefsStore.setBoolean(
                                ctx, RingConfig.KEY_COLLAPSE_ON_SHADE, enabled,
                            )
                            update(config.copy(collapseOnShade = enabled))
                        },
                        title = "下拉面板时隐藏圆环",
                        summary = "下拉通知栏或控制中心时，圆环跟随面板拉起进度向内收缩并淡出，面板收起后弹回；开启期间下拉状态的截屏同样不含圆环",
                        enabled = config.ringEnabled,
                    )
```

沿用同卡片既有写法（miuix `SwitchPreference` + `PrefsStore.setBoolean` + `update(config.copy(...))`），不混入 Material3 组件。

- [ ] **Step 2: 构建安装**

```bash
cd HolePowerRing
./gradlew assembleDebug installDebug
```

Expected: `BUILD SUCCESSFUL`，`Install ... Success`。

---

### Task 6: 真机验证矩阵

设备：`adb-6f1adaee-K5bSQq._adb-tls-connect._tcp`（25102RKBEC / 系统界面 17.03.260226.r）。配置改动**必须重启 SystemUI 才生效**（配置在进程内只读加载）。

- [ ] **Step 1: 重启 SystemUI 并开日志**

```bash
adb -s adb-6f1adaee-K5bSQq._adb-tls-connect._tcp shell killall com.android.systemui
adb -s adb-6f1adaee-K5bSQq._adb-tls-connect._tcp logcat -c
adb -s adb-6f1adaee-K5bSQq._adb-tls-connect._tcp logcat -s HolePowerRing
```

Expected: 无 `AndroidRuntime`/`FATAL` 异常；出现 `面板收起信号已挂载 ...onPanelExpansionChanged`。

- [ ] **Step 2: 开关关闭态回归（默认值）**

用户操作：下拉通知面板、下拉控制中心、进入沉浸全屏。
Expected: 环行为与改动前完全一致（下拉时环保持显示；沉浸收起照常生效）。这一项确认"默认关=零行为变化"。

- [ ] **Step 3: 开关开启态主功能**

用户在设置页打开「下拉面板时隐藏圆环」，重启 SystemUI。
用户操作：慢速下拉通知面板（半途停住）、继续拉满、上滑收起；控制中心重复一遍。
Expected:
- 拖动过程中环随进度连续收缩+淡出（半途停住时环停在中间状态，不跳变）；
- 完全展开时 `collapseProgress=1f`，面板收起后回到 `0f`；
- 日志 `面板拉起进度: fraction=... 收起目标=...` 变化节奏与手指一致，无每秒数十条刷屏（阈值去重生效）。

- [ ] **Step 4: 叠加场景**

| 场景 | 用户操作 | Expected |
|---|---|---|
| 沉浸 + 下拉 | 图库沉浸收起（环已收）→ 下拉面板 → 收起面板 → 退出沉浸 | 全程不弹回（取最强者），退出沉浸后回到 0f |
| 灵动岛 + 下拉 | 开启「有岛时隐藏圆环」，触发一个岛活动后下拉 | 环保持收起，无闪烁 |
| 横屏 | 横屏下拉面板 | 不崩溃；横屏本就不画环（`restoreBatteryOnLandscape`），行为不变 |
| 截屏叠加 | 开启「截图时隐藏」+「下拉面板时隐藏」，面板展开时指关节下拉截屏 | 截图无环（预期），物理屏也无环；文案已说明 |

- [ ] **Step 5: 误触发排查**

用户操作：开机亮屏进桌面、锁屏、解锁、下拉通知后再横向切控制中心、音量键唤起音量面板。
Expected: 除"下拉面板/控制中心"外，其它场景环不误收。若锁屏下拉误收且不可接受，在驱动侧补条件（如仅 `RingState.screenOn && !keyguard` 时采信），并把结论回报告。

- [ ] **Step 6: 性能核对**

```bash
adb -s adb-6f1adaee-K5bSQq._adb-tls-connect._tcp shell "dumpsys gfxinfo com.android.systemui | head -20"
```

用户操作：反复下拉/收起面板 20 次后执行上面命令。
Expected: `Janky frames` 无可感知恶化；SystemUI 无 ANR、无重启。

---

### Task 7: 文档回填

- [ ] **Step 1: 可行性分析报告新增 §18**

在 `挖孔环形电量LSP模块-可行性分析报告.md` 的 `## 17. 截图时隐藏电量环...` 之后、`## 附录 A` 之前，新增一节，标题：

```markdown
## 18. 下拉通知栏/控制中心时收起电量环（2026-10-03）
```

必须写入的内容（按 Task 1/4/6 的实际取证填写，不留占位）：
1. 载体层带关系：环窗口 `type=2006`/231000 高于 `NotificationShade`/171000，因此下拉时环浮在面板之上，需主动隐藏；`FLAG_NOT_TOUCHABLE` 保证不影响下拉手势。
2. 候选信号对照表（本计划「信号候选」那张表）与**真机探针取证结果**：每个候选在五个场景下的实际触发情况、参数取值样例（粘原始日志行）。
3. 最终采用的点位与完整签名、参数语义（`fraction` 取值范围、`expanded`/`tracking` 何时翻转、通知面板与控制中心是否共用同一实例）。
4. 被删除的候选及删除原因（误报/从不触发）。
5. 降级方向与版本敏感说明；叠加场景（沉浸、灵动岛、截图、锁屏）实测结论。
6. 若 Step 5 发现误触发，把处置条件也写进来。
7. 顺手补上 AGENTS.md 中标注"尚未补写"的 **§13 电池图标取色链路**条目（内容见 AGENTS.md 同条），避免引用继续悬空。

- [ ] **Step 2: AGENTS.md 更新**

在「已知待验证项」列表末尾追加一条，格式对齐既有条目（结论 + 日期 + 真机状态 + 报告章节 + 换机型注意）。**尖括号部分是执行时要替换成实际结论的槽位，不得原样提交**；若 Task 4 最终采用的不是状态管理器，类名与方法签名按取证结果改写：

```markdown
- **下拉面板收起电量环：已确认（2026-10-03，真机验证通过）**。设置键 `collapse_on_shade`（默认关）。信号源 `com.android.systemui.shade.ShadeExpansionStateManager.onPanelExpansionChanged(float, boolean, boolean)`，逐帧 fraction 驱动 `RingState.setShadeCollapse`；`NotificationShadeWrapper.onPanelExpanded` 与 `NotificationShadeWindowControllerImpl.onShadeOrQsExpanded` 被否决（原因按 Task 1 取证记录写）。换机型须先用探针确认真实信号（方法见可行性分析报告 §18）
```

若真机验证尚未通过，该条按「待验证」写并明确列出未验证项，不要提前记成已确认。同一步顺带把 Step 1 里补写的 **§13 电池图标取色链路**与 AGENTS.md 中"该链路在可行性分析报告中的章节尚未补写"那句悬空引用对齐——补写后把该句改成正常指向 §13。

- [ ] **Step 3: 交叉检查**

确认：`RingConfig` 新字段名 `collapseOnShade`、键名 `collapse_on_shade`、`RingState.setShadeCollapse(Float)`、`RingCollapseLogic.target/shouldEmit/EPSILON` 在 Hook、RingState、测试、UI、报告、AGENTS.md 六处拼写完全一致；报告里的类名/签名与代码里 `findAndHookMethod` 的参数一字不差。

---

## 探针取证结果

（Task 1 Step 6 填写，作为 Task 4 决策依据）

### 执行记录（2026-10-03）

- Task 1/2/3/5 已由 subagent 落地，主 agent 抽查 diff 通过；`./gradlew test assembleDebug` BUILD SUCCESSFUL，JVM 单测 46 条全绿（新增 `RingCollapseTest` 8 条）。
- **Task 4 的驱动部分已提前并入 Task 1 的探针**（发起时决定，理由见上文「我对计划做的一处偏离」）：`ShadeCollapseHook` 里 `ShadeExpansionStateManager.onPanelExpansionChanged` 分支为驱动通路，另两个候选（`NotificationShadeWrapper.onPanelExpanded`、`NotificationShadeWindowControllerImpl.onShadeOrQsExpanded`）保持仅诊断日志。因 `collapse_on_shade` 默认 false，本轮装机包对现有行为零影响。
- subagent 发现并修正了计划 Task 4 Step 1 的一处缺陷：原代码在开关关闭时仍会调 `setShadeCollapse`，等于整场拖拽每帧一条日志 + 一次 `invalidateAll()` 空重绘。已改为 `if (!RingState.config.collapseOnShade) { diag(...); return }`。**Task 4 收敛时保留这个门控**，并把两个诊断分支删除。
- debug 包已通过无线 adb 装机（`Installed on 1 device`，`lastUpdateTime=2026-10-03 15:53:32`）。
- 重启 SystemUI 需用户手动：本机 `adb shell killall com.android.systemui` 报 `Operation not permitted`（KSU 未授权 shell），改由设置页 root 重启按钮或 KSU 管理器完成。

### 第二轮：真机取证推翻首选信号（2026-10-03）

用户实测「通知中心可以、控制中心不行」，抓取 logcat + `work/sysui` 静态复核，结论：

- **`ShadeExpansionStateManager.onPanelExpansionChanged(FZZ)` 整场 0 次触发**（虽挂载成功）——本机通知面板走传统 MIUI View 管线，这个 AOSP scene/flow 点位是死代码。已从 `ShadeCollapseHook` **删除**。
- 首轮环实际由 `NotificationShadeWrapper.onPanelExpanded(Z)` 驱动，**全程只有单个实例**（inst=138290662）触发 ⇒ **控制中心根本不经过它**，这就是"控制中心不行"的根因。降级为仅取证分支。
- **改用 `NotificationShadeWindowControllerImpl.onShadeOrQsExpanded(java.lang.Boolean)` 作驱动**：它与 wrapper 近乎同步（24/25 次）且存在"只触发它、不触发 wrapper"的拍点 ⇒ 是超集；静态佐证其调用方 `$$ExternalSyntheticLambda4.accept` 读 `NotificationShadeWindowState.qsExpanded` 并 `apply(...)`，即 shade/QS 的**合并布尔**。装箱 Boolean，`findAndHookMethod` 用 `javaObjectType`。
- **语义从"跟随 fraction"降级为"0/1 硬切换"**：本机没有可用的连续 fraction 源，环行为是"面板展开即收到 1f、收起即弹回 0f"，观感仍非硬闪（走 `animateCollapseTo` 的 260ms `DecelerateInterpolator`）。`setShadeCollapse` 保留 Float 形参以便将来控制中心侧确认到 fraction 后复用。
- 新增两处**仅取证**候选，用来回答"控制中心能不能拿到连续 fraction"：`ControlCenterExpandControllerDelegate.onExpansionChanged(F)`（classes3，静态是接收端、无同-dex 调用方）、`NotificationPanelExpandController.notifyExpandHeightChanged(FFZZ)`（classes2，体内只派发 onExpandHeightChanged、不派发 onExpansionChanged）。
- **顺带修掉开关热翻转的两处残留缺陷**（首轮门控引入）：`ShadeCollapseHook` 无条件记录 `lastSystemExpanded`；新增 `RingState.onShadeCollapseToggled` 回调，翻转时清驱动侧 `lastFraction` 去重缓存并按已观测展开态补一次驱动（否则"面板开着切进开关→环不跟手 / 环无端收起不回弹"）。
- 第二轮 debug 包已装（`lastUpdateTime=2026-10-03 16:15:57`），`./gradlew test` 46 条全绿。**待用户重启 SystemUI 后验证控制中心能否收起环**，并采集两处 fraction 取证信号。

### 第三轮：控制中心真信号定案 = onVisibleChanged 布尔（2026-10-03）

第二轮驱动 `onShadeOrQsExpanded` 装机后用户实测"控制中心还是不行"，第三轮取证（重启后 pid 8042，logcat）定论：

- **`onShadeOrQsExpanded` 只覆盖通知面板、下拉控制中心时一次都不触发**（尽管名字带 "Qs"）——上轮"它是超集"的静态推断被真机数据彻底推翻。
- **控制中心唯一可靠信号 = `ControlCenterExpandControllerDelegate.onVisibleChanged(Z)`**：实测三组开合全部成对 `v=true → v=false`（16:27:09.911→14.118、16.013→20.674、22.481→26.485），且这三次控制中心展开期间驱动通路（onShadeOrQsExpanded）确实 0 事件 ⇒ 根因坐实。
- `onExpansionChanged(F)` 能给连续 fraction，但"收起是否回落 0f"两轮都因日志量撞满上限未证实；拿它驱动有"环卡在不可见"风险（违背"多显示远比该藏没藏安全"原则），**弃用**。

**最终方案（已实现并装机 `lastUpdateTime=2026-10-03 16:29:28`，46 单测全绿，未提交）**：`ShadeCollapseHook` 重写为**两路布尔求或**——通知路 `onShadeOrQsExpanded(Boolean)` + 控制中心路 `onVisibleChanged(Z)`，各自更新 `notifExpanded`/`ccExpanded`（与开关无关始终跟踪），`combinedFraction() = if (notifExpanded || ccExpanded) 1f else 0f`，门控后才驱动 `RingState.setShadeCollapse`。删除三个已完成使命的取证分支（wrapper / ccFraction / notifHeight）。`syncFromSystem` 改为读两路已跟踪状态补驱动（开关热打开时若面板已开，环立即收起）。行为是布尔（展开即收、收起即弹回，260ms 动画缓冲）。

**待办**：用户重启 SystemUI，开启开关后分别下拉通知栏与控制中心，验证两者都能收环、收起都能弹回；通过后进入 Task 7 文档回填（报告 §18 + §13 + AGENTS.md）。

| 场景 | 用户动作 | 日志证据 | 判读 |
|---|---|---|---|
| 待填 | | | |

## 执行进度

| 组 | 主题 | 状态 | 备注 |
|---|---|---|---|
| A | Task 1 真机探针取证 | 代码已装，待取证 | 探针与驱动已合并；需用户重启 SystemUI 后下拉 |
| B | Task 2-3 纯逻辑与配置链路 | 完成 | 46 条单测全绿 |
| C | Task 4-5 信号收敛与设置页 | Task 5 完成；Task 4 驱动已前置、待收敛删除诊断分支 | 依赖组 A 结论 |
| D | Task 6 真机验证矩阵 | 未开始 | |
| E | Task 7 文档回填 | 未开始 | |
