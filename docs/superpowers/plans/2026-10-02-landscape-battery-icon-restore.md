# 横屏恢复原生电池图标 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **执行约束（务必遵守）**：
> - **不要 `git commit` / `git add`**，任何步骤都不提交（用户长期偏好）。
> - 只改本计划「Files」列出的文件；行号会漂移，用代码块里的**锚点文本**重新定位，不要信任行号。
> - 本仓库**没有自动化测试**（AGENTS.md 已确认）。验证方式为 `./gradlew assembleDebug` 编译 + 真机 logcat 判读，Task 6 给出完整清单。
> - Hook 侧任何新增逻辑（含反射、`view.display`）都必须包 `try/catch (Throwable)` + `ModuleLog`，绝不让异常逃逸进 SystemUI（AGENTS.md 铁律 1）。

**Goal:** 横屏时（挖孔跑到屏幕侧边、圆环必然画在窗口外不可见）不再强制隐藏状态栏原生电池图标，并新增一个默认开启的设置开关控制该行为。

**Architecture:** 判定收敛到 `RingState.shouldForceHideBattery(view)`——把「当前 View 所在屏幕是否横屏」纳入隐藏条件，判据取自被隐藏的那个 View 自己的 `display.rotation`（权威、无需维护状态）。旋转事件经 `BatteryObserver` 的 `ACTION_CONFIGURATION_CHANGED` 广播触发 `BatteryHideHook.refreshAll()` 重评估；`refreshAll` 除了恢复电池 View 的可见性，还会把容器 `MiuiStatusBatteryContainer` 的隐藏状态**交还给系统最近一次原始请求值**——这是必需的，因为 before-hook 改参后系统自身的 `mIsHideBattery` 已被写成 `true`，不主动交还就一直隐藏。

**Tech Stack:** Kotlin + Xposed/LSPosed API（仅 `xposedstub` 编译期）、Compose + miuix 设置页、跨进程配置走 ContentProvider。

---

## 已确认的设计决策

| 项 | 决策 | 理由 |
|---|---|---|
| 横屏判据 | `View.getDisplay().getRotation()` ∈ {`ROTATION_90`, `ROTATION_270`} | 用户确认「严格按屏幕横屏判定」。取 rotation 而非 `configuration.orientation`：状态栏窗口在横屏下 resources 方向不一定跟着翻，display rotation 才是挖孔换边的真相 |
| 为什么不泛化为「环不可见就恢复」 | 不做 | 该判据依赖环窗口持续 `onDraw`，横屏若不重绘就失效，真机验证成本高；用户选了简单确定的语义 |
| 新开关默认值 | 开启（`true`） | 与既有「电量指示永不丢失」安全策略一致（`BatteryHideHook` 文件头） |
| 开关生效条件 | 仅在「挖孔环形电量」+「隐藏状态栏电池图标」都开启时可交互 | 上层开关关掉时本项无意义，与页面既有 `enabled = config.ringEnabled` 模式一致 |
| 横屏时是否尝试让环也显示 | 不尝试 | 用户明确表示「横屏不显示挖孔电量是正常的」 |
| 交还容器的值 | 交还**系统最近一次原始请求值**，不自行置 `false` | 若系统因灵动岛正在显示而请求 `true`，强行置 `false` 会与岛冲突（违背 AGENTS.md 铁律 3「优先走系统原生路径」） |

## 根因（已读代码确认，供 subagent 建立上下文）

`HolePowerRing/app/src/main/java/com/powerring/hole/ring/RingState.kt:346`：

```kotlin
fun shouldForceHideBattery(): Boolean {
    val c = config
    return c.ringEnabled && c.hideBattery && cutoutEverResolved
}
```

`cutoutEverResolved` 由 `RingRenderer.draw()` 在首次几何解析成功时经 `markCutoutResolved()` 置真，**且永不复位**。横屏时 `CutoutGeometry` 取到的是侧边挖孔矩形，换算到顶部环窗口（`gravity=TOP`、高度≈安全区+26dp）的局部坐标后圆心落在窗口下方之外，环画不出来——但隐藏条件仍然成立，于是圆环和原生图标同时消失，横屏彻底没有电量指示。

---

## 文件结构

| 文件 | 本次职责 |
|---|---|
| `ring/RingConfig.kt` | 新增配置字段 `restoreBatteryOnLandscape` + key 常量（配置契约的唯一来源） |
| `core/HookPrefs.kt` | SystemUI 侧从 provider 游标还原该布尔 |
| `ui/ConfigProvider.kt` | 模块侧把该 key 暴露为游标列 |
| `ui/PrefsStore.kt` | 设置页读写该 key |
| `ring/RingState.kt` | 横屏判定 `isLandscape(view)` + 隐藏条件收紧 + 方向变化回调 |
| `hook/BatteryHideHook.kt` | 按 View 评估隐藏；登记容器与其系统原始请求值；解除时主动交还 |
| `data/BatteryObserver.kt` | 监听 `ACTION_CONFIGURATION_CHANGED` 并转发方向变化 |
| `hook/SystemUiHooks.kt` | 把方向变化回调接到 `BatteryHideHook.refreshAll()` |
| `ui/SettingsScreen.kt` | 「开关」页新增 SwitchPreference |
| `挖孔环形电量LSP模块-可行性分析报告.md` | 回写结论（**新增 §14**；§13 已被 AGENTS.md 预留给电池取色链路，勿占用） |

---

### Task 1: 配置项打通（RingConfig → Provider → HookPrefs → PrefsStore）

**Files:**
- Modify: `HolePowerRing/app/src/main/java/com/powerring/hole/ring/RingConfig.kt`
- Modify: `HolePowerRing/app/src/main/java/com/powerring/hole/core/HookPrefs.kt`
- Modify: `HolePowerRing/app/src/main/java/com/powerring/hole/ui/ConfigProvider.kt`
- Modify: `HolePowerRing/app/src/main/java/com/powerring/hole/ui/PrefsStore.kt`

- [ ] **Step 1: `RingConfig` 加字段与 key**

在数据类里 `collapseOnIsland` 之后插入字段（保持与注释风格一致）：

```kotlin
    /** 灵动岛（超级岛）显示时，圆环像全屏沉浸时一样向内收缩并淡出 */
    val collapseOnIsland: Boolean = false,
    /** 横屏（挖孔换到侧边、环无法显示）时恢复显示状态栏原生电池图标 */
    val restoreBatteryOnLandscape: Boolean = true,
```

在 companion 里 `KEY_COLLAPSE_ON_ISLAND` 之后插入：

```kotlin
        const val KEY_COLLAPSE_ON_ISLAND = "collapse_on_island"
        const val KEY_RESTORE_BATTERY_ON_LANDSCAPE = "restore_battery_on_landscape"
```

- [ ] **Step 2: `HookPrefs.load()` 还原该布尔**

锚点：`cached = RingConfig(` 构造块内，紧跟 `collapseOnIsland = ...` 之后加一行：

```kotlin
            collapseOnIsland = toBool(values[RingConfig.KEY_COLLAPSE_ON_ISLAND], d.collapseOnIsland),
            restoreBatteryOnLandscape = toBool(
                values[RingConfig.KEY_RESTORE_BATTERY_ON_LANDSCAPE], d.restoreBatteryOnLandscape,
            ),
```

- [ ] **Step 3: `ConfigProvider` 暴露该列**

`valueOf()` 的 `when` 里，紧跟 `KEY_COLLAPSE_ON_ISLAND` 分支之后：

```kotlin
        RingConfig.KEY_COLLAPSE_ON_ISLAND ->
            prefs.getBoolean(key, RingConfig.DEFAULT.collapseOnIsland)
        RingConfig.KEY_RESTORE_BATTERY_ON_LANDSCAPE ->
            prefs.getBoolean(key, RingConfig.DEFAULT.restoreBatteryOnLandscape)
```

`companion object` 的 `ALL_KEYS` 数组里，`RingConfig.KEY_COLLAPSE_ON_ISLAND,` 之后：

```kotlin
            RingConfig.KEY_COLLAPSE_ON_ISLAND,
            RingConfig.KEY_RESTORE_BATTERY_ON_LANDSCAPE,
```

- [ ] **Step 4: `PrefsStore.load()` 读该 key**

锚点：`collapseOnIsland = p.getBoolean(...)` 之后（本文件既有风格是写字面量默认值，保持一致）：

```kotlin
            collapseOnIsland = p.getBoolean(RingConfig.KEY_COLLAPSE_ON_ISLAND, false),
            restoreBatteryOnLandscape = p.getBoolean(
                RingConfig.KEY_RESTORE_BATTERY_ON_LANDSCAPE, true,
            ),
```

- [ ] **Step 5: 编译**

```bash
cd "HolePowerRing" && ./gradlew assembleDebug
```
预期：`BUILD SUCCESSFUL`。此时 `BatteryHideHook` 里出现 `RingState.shouldForceHideBattery()` 的旧签名调用**不会报错**（Task 2 才改签名），本 Task 不应有任何编译错误。若 `ConfigProvider` 的 `when` 报「分支不穷尽」，说明 Step 3 漏了。

- [ ] **Step 6: 抽查 diff**

主 agent 核对：4 个文件都只新增了上述内容、key 字符串三处完全一致（`restore_battery_on_landscape`）。**不提交。**

---

### Task 2: RingState 横屏判定与隐藏条件

**Files:**
- Modify: `HolePowerRing/app/src/main/java/com/powerring/hole/ring/RingState.kt`

- [ ] **Step 1: 加导入**

现有 import 区补充（`android.view.View` 已存在）：

```kotlin
import android.content.res.Configuration
import android.view.Surface
```

> **执行时更正（2026-10-02）**：原计划写 `import android.view.Display` + `Display.ROTATION_90/270`，
> 但本机 SDK 34~37 的 `android.jar` 桩里 `Display.ROTATION_*` 不可解析（`Unresolved reference`）。
> 改用 `android.view.Surface.ROTATION_90/270`——同为公开常量、编译期内联、数值一致（1/3），判据语义不变。

- [ ] **Step 2: 替换 `shouldForceHideBattery` 并新增 `isLandscape`**

锚点：文件现有的「是否强制隐藏状态栏原电池图标」注释 + 无参函数整段替换为：

```kotlin
    /**
     * 该 View 所在屏幕当前是否横屏。
     *
     * 判据取 `View.getDisplay().getRotation()`：横屏时挖孔安全区换到屏幕侧边，
     * 顶部环窗口的几何不再成立、圆环必然画在窗口外，此时不该继续隐藏原生图标。
     * 用 View 自己的 display 而不是全局状态：状态栏窗口与应用窗口的方向可能不一致。
     *
     * display 取不到（View 尚未 attach）时退回配置方向；再取不到按竖屏处理（保持现状）。
     */
    @Suppress("DEPRECATION")
    fun isLandscape(view: View?): Boolean {
        if (!config.restoreBatteryOnLandscape) return false
        return try {
            val rotation = view?.display?.rotation
            if (rotation != null) {
                rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270
            } else {
                val ctx = appContext ?: view?.context
                ctx?.resources?.configuration?.orientation == Configuration.ORIENTATION_LANDSCAPE
            }
        } catch (t: Throwable) {
            // 判定失败一律按竖屏走，宁可维持原有隐藏行为也不误改系统状态
            ModuleLog.e("横屏判定失败，按竖屏处理", t)
            false
        }
    }

    /**
     * 是否强制隐藏状态栏原电池图标：环开启 + 隐藏选项开启 + 挖孔确实存在，
     * 且当前不是横屏（横屏时环不可见，图标交还系统，见 [isLandscape]）。
     */
    fun shouldForceHideBattery(view: View?): Boolean {
        val c = config
        return c.ringEnabled && c.hideBattery && cutoutEverResolved && !isLandscape(view)
    }
```

- [ ] **Step 3: 新增方向变化回调**

锚点：紧跟既有的 `var onCutoutResolved: (() -> Unit)? = null` 声明之后（同一「挖孔几何首次解析成功的回调」注释块下方），加入：

```kotlin
    /**
     * 屏幕方向变化回调。与 [onCutoutResolved] 同一模式：由 SystemUiHooks 接到
     * `BatteryHideHook.refreshAll()`，data 层不直接依赖 hook 层。
     */
    @Volatile
    var onOrientationChanged: (() -> Unit)? = null

    /** 由 BatteryObserver 在 ACTION_CONFIGURATION_CHANGED 时调用。 */
    fun notifyOrientationChanged() {
        invalidateAll()
        try {
            onOrientationChanged?.invoke()
        } catch (t: Throwable) {
            ModuleLog.e("方向变化回调异常", t)
        }
    }
```

- [ ] **Step 4: 编译（预期失败）**

```bash
cd "HolePowerRing" && ./gradlew assembleDebug
```
预期：**编译失败**，报错形如 `No value passed for parameter 'view: View?'`，指向 `BatteryHideHook.kt` 的两处调用。这正是 Task 3 要修的——签名改造与调用点必须在相邻 Task 落地。

- [ ] **Step 5: 抽查 diff。不提交。**

---

### Task 3: BatteryHideHook 按 View 评估 + 交还容器隐藏状态

**Files:**
- Modify: `HolePowerRing/app/src/main/java/com/powerring/hole/hook/BatteryHideHook.kt`

- [ ] **Step 1: 加导入与容器登记表**

import 区补：

```kotlin
import android.os.Handler
import android.os.Looper
```

锚点：`private val knownViews` 声明之后加入登记表（**注意用 `WeakHashMap` 而非 `newSetFromMap`**，需要存值）：

```kotlin
    /**
     * 已见到的 `MiuiStatusBatteryContainer` -> **系统最近一次原始请求**的隐藏值。
     *
     * before-hook 会把 `setIsHideBattery(false)` 改成 `true`，系统自己的
     * `mIsHideBattery` 因此被写成 true；解除强制时必须把这个原始值交还回去，
     * 否则图标不会重新出现（本表就是为这一步服务的）。
     */
    private val containerRequests: MutableMap<View, Boolean> = WeakHashMap()
```

- [ ] **Step 2: 重写 `refreshAll`，并新增 `releaseContainer`**

把现有的 `fun refreshAll()`（含其 KDoc「挖孔几何首次解析成功后…」）整段替换为：

```kotlin
    /**
     * 重新评估所有已存在的电池 View / 容器：挖孔几何就绪、或屏幕方向变化后调用。
     *
     * 方向变化时系统不会主动再调 `setIsHideBattery`，容器会一直停在我们改出来的
     * `true` 上，所以解除强制必须显式交还；重入是安全的——回调里
     * [RingState.shouldForceHideBattery] 此刻为 false，before-hook 不再改参。
     */
    fun refreshAll() {
        val views = synchronized(knownViews) { knownViews.toList() }
        val containers = synchronized(containerRequests) { containerRequests.toList() }
        val main = Handler(Looper.getMainLooper())
        containers.forEach { (container, requested) ->
            main.post { releaseContainer(container, requested) }
        }
        views.forEach { v ->
            if (v.isAttachedToWindow) v.post { applyHide(v) }
        }
    }

    /** 把系统最近一次原始请求值交还给容器自身的方法；仍要求隐藏时（如在显示灵动岛）不越权。 */
    private fun releaseContainer(container: View, requested: Boolean) {
        if (RingState.shouldForceHideBattery(container)) return
        if (!container.isAttachedToWindow) return
        try {
            XposedHelpers.callMethod(container, "setIsHideBattery", java.lang.Boolean.valueOf(requested))
            ModuleLog.i("已交还电池容器隐藏状态: requested=$requested")
        } catch (t: Throwable) {
            ModuleLog.e("交还电池容器隐藏状态失败", t)
        }
    }
```

- [ ] **Step 3: before-hook 记录原始值并传入容器 View**

锚点：`install()` 内 `findAndHookMethod(containerClass, "setIsHideBattery", ...)` 的匿名 `XC_MethodHook`，把 `beforeHookedMethod` 整段替换为：

```kotlin
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            try {
                                val container = param.thisObject as? View
                                val requested = param.args[0] as? Boolean ?: false
                                if (container != null) {
                                    synchronized(containerRequests) {
                                        containerRequests[container] = requested
                                    }
                                }
                                if (RingState.shouldForceHideBattery(container)) {
                                    param.args[0] = java.lang.Boolean.TRUE
                                }
                            } catch (t: Throwable) {
                                // 异常时放行系统原值：宁可短暂露出原生图标，也不能卡住 SystemUI
                                ModuleLog.e("setIsHideBattery 前置处理异常，放行系统值", t)
                            }
                        }
```

- [ ] **Step 4: `applyHide` 传入 view**

锚点：`applyHide()` 内

```kotlin
        val force = RingState.shouldForceHideBattery()
```
改为
```kotlin
        val force = RingState.shouldForceHideBattery(view)
```

- [ ] **Step 5: 更新文件头 KDoc 的安全策略段**

锚点：类注释里「安全策略：只有挖孔几何已确认可用…」整段替换为：

```kotlin
 * 安全策略：只有挖孔几何已确认可用（[RingState.cutoutEverResolved]）才隐藏，
 * 且横屏（挖孔换到侧边、环画不出来）时把图标交还系统——环不可见就绝不丢失电量指示。
 * 用户关闭开关、或从横屏回到竖屏时，容器与 View 两层都要重新评估并主动交还。
```

- [ ] **Step 6: 编译**

```bash
cd "HolePowerRing" && ./gradlew assembleDebug
```
预期：`BUILD SUCCESSFUL`（Task 2 Step 4 的签名报错此时应全部消除）。

- [ ] **Step 7: 抽查 diff。不提交。**

---

### Task 4: 旋转触发重评估（广播 + 接线）

**Files:**
- Modify: `HolePowerRing/app/src/main/java/com/powerring/hole/data/BatteryObserver.kt`
- Modify: `HolePowerRing/app/src/main/java/com/powerring/hole/hook/SystemUiHooks.kt`

- [ ] **Step 1: BatteryObserver 注册 CONFIG_CHANGED**

锚点：`receiver.onReceive` 的 `when` 里，`Intent.ACTION_SCREEN_OFF -> ...` 之后插入分支：

```kotlin
                    Intent.ACTION_SCREEN_OFF -> RingState.setScreenOn(false)
                    Intent.ACTION_CONFIGURATION_CHANGED -> {
                        // 屏幕旋转后系统不会再调 setIsHideBattery，必须自己重评估
                        RingState.notifyOrientationChanged()
                    }
```

锚点：`val filter = IntentFilter().apply { ... }` 里，`addAction(Intent.ACTION_SCREEN_OFF)` 之后：

```kotlin
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_CONFIGURATION_CHANGED)
```

- [ ] **Step 2: SystemUiHooks 接线**

锚点：`RingState.onCutoutResolved = { BatteryHideHook.refreshAll() }` 之后加一行：

```kotlin
            RingState.onCutoutResolved = { BatteryHideHook.refreshAll() }
            RingState.onOrientationChanged = { BatteryHideHook.refreshAll() }
```

- [ ] **Step 3: 编译**

```bash
cd "HolePowerRing" && ./gradlew assembleDebug
```
预期：`BUILD SUCCESSFUL`。

- [ ] **Step 4: 抽查 diff。不提交。**

---

### Task 5: 设置页开关

**Files:**
- Modify: `HolePowerRing/app/src/main/java/com/powerring/hole/ui/SettingsScreen.kt`

- [ ] **Step 1: 在「隐藏状态栏电池图标」之后插入新开关**

锚点：`SwitchTabContent` 内，`hideBattery` 那个 `SwitchPreference` 之后的 `HorizontalDivider` 之后插入：

```kotlin
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    SwitchPreference(
                        checked = config.restoreBatteryOnLandscape,
                        onCheckedChange = { enabled ->
                            PrefsStore.setBoolean(
                                ctx, RingConfig.KEY_RESTORE_BATTERY_ON_LANDSCAPE, enabled,
                            )
                            update(config.copy(restoreBatteryOnLandscape = enabled))
                        },
                        title = "横屏时恢复电池图标",
                        summary = "横屏时挖孔换到侧边、圆环无法贴合，此时恢复显示原生电池图标，避免电量指示丢失；关闭后横屏同样保持隐藏",
                        enabled = config.ringEnabled && config.hideBattery,
                    )
```

- [ ] **Step 2: 「关于」页提示补一句（同文件 `AboutTabContent` 的提示文案）**

锚点：提示 `Text` 内容里 `"状态栏自动收起（沉浸模式）时圆环同步收缩隐藏。"` 之后：

```kotlin
                        text = "提示：所有调节即时生效；息屏/AOD 期间自动隐藏圆环以防烧屏，" +
                            "状态栏自动收起（沉浸模式）时圆环同步收缩隐藏。" +
                            "横屏时圆环无法贴合挖孔，默认会自动恢复原生电池图标。" +
                            "若调节后环与挖孔有偏差，优先用「缩放」对齐半径，再用偏移微调中心。",
```

- [ ] **Step 3: 编译**

```bash
cd "HolePowerRing" && ./gradlew assembleDebug
```
预期：`BUILD SUCCESSFUL`，无未使用变量/缺失分支告警新增。

- [ ] **Step 4: 抽查 diff（确认 UI 用 miuix `SwitchPreference`、未引入 Material3 组件）。不提交。**

---

### Task 6: 真机验证与文档回写

**Files:**
- Modify: `挖孔环形电量LSP模块-可行性分析报告.md`（新增 §14）

- [ ] **Step 1: 装机（产物已就绪）**

```bash
cd "HolePowerRing" && ./gradlew installDebug
```
需用户手动完成后续动作（记忆库约束：本机 `adb shell su` 不可用，杀 SystemUI 属设备状态变更，**须先征得用户同意**）：LSPosed 里确认模块作用域仍勾选系统界面 → 重启 SystemUI。

- [ ] **Step 2: 抓日志基线（只读，可直接执行；本机有两条 adb 连接，必须带 `-s`）**

```bash
adb -s 6f1adaee logcat -c
adb -s 6f1adaee logcat -s HolePowerRing | grep -E "配置已重新加载|交还电池容器|已隐藏状态栏电池图标|横屏判定"
```
Expected: 看到一行 `配置已重新加载: RingConfig(... restoreBatteryOnLandscape=true ...)`，确认新字段确实从 provider 读到了 `true`（若为 `false` 说明 Task 1 的三处 key 字符串不一致）。

- [ ] **Step 3: 竖屏回归（确认没改坏既有行为）**

预期：竖屏下原生电池图标隐藏、圆环正常显示；日志出现「已隐藏状态栏电池图标」，且**没有**「已交还电池容器隐藏状态: requested=false」。

- [ ] **Step 4: 横屏用例（核心验收）**

旋转到横屏（用户手动旋转即可；若要用 adb 写设置 `settings put system user_rotation 1`，**先征求用户同意**）。

预期：
1. 圆环不显示（与改动前一致，符合用户预期）；
2. **原生电池图标重新出现**；
3. 日志出现 `已交还电池容器隐藏状态: requested=false`，或「已隐藏状态栏电池图标」不再复现。

若图标仍不出现：说明隐藏实际由容器层 `mIsHideBattery` 决定而 `setIsHideBattery(false)` 未生效，需按 Step 6 的兜底分支处理。

- [ ] **Step 5: 横→竖回到竖屏**

预期：圆环恢复显示、原生图标再次隐藏（`refreshAll` 在两个方向都要生效，日志两次都可见）。

- [ ] **Step 6: 开关关闭态（横屏依旧隐藏）**

设置页「开关」页把「横屏时恢复电池图标」关掉 →（`CONFIG_CHANGED` 广播会热加载，无需重启 SystemUI）→ 横屏。
预期：图标不再恢复、保持隐藏。若图标仍恢复，说明 `HookPrefs` 没读到新值，回到 Task 1 Step 3 核对 `ALL_KEYS` 与 `valueOf()` 两处。

- [ ] **Step 7: 灵动岛共存用例**

横屏触发一个灵动岛（充电/通知胶囊）。
预期：岛正常显示、电池图标由系统自己隐藏（`releaseContainer` 交还的是系统请求值，不会与岛抢）；岛消失后图标恢复。

- [ ] **Step 8: 回写可行性分析报告**

在文件末尾 `## 12.` / `## 13.` 之后**新增 §14**（§13 已被 AGENTS.md 预留给「电池图标取色链路」，不得占用）。内容按 Step 2–7 的实测结果填写，骨架：

```markdown
## 14. 横屏电池图标恢复（2026-10-02，25102RKBEC / 17.03.260226.r）

**现象**：横屏时挖孔安全区换到屏幕侧边，顶部环窗口（type=2006，高度≈安全区+26dp）的
局部坐标下圆心落在窗口之外，圆环不可见；但 `RingState.cutoutEverResolved` 一旦置真
永不复位，隐藏条件继续成立 → 横屏完全没有电量指示。

**判据**：`view.display.rotation` ∈ {`Surface.ROTATION_90`, `Surface.ROTATION_270`}
（不要用 `Display.ROTATION_*`：本机 SDK 桩里不可解析）。
- 实测 rotation 值：____（换机型须重测）
- 未采用 `configuration.orientation`：状态栏窗口的 resources 方向不一定跟随

**关键实现事实**：before-hook 把 `setIsHideBattery(false)` 改成 `true` 之后，
系统自身的 `mIsHideBattery` 已经是 true；旋转时系统**不会**再调这个方法，
因此解除强制必须显式 `callMethod(container, "setIsHideBattery", 系统最近一次原始请求值)`。
只把电池 View 置回可见不足以恢复（容器仍是隐藏的）。原始请求值来源：
`WeakHashMap<MiuiStatusBatteryContainer, Boolean>`，在 before-hook 改参**之前**记录。

**触发通道**：`ACTION_CONFIGURATION_CHANGED`（BatteryObserver 既有主线程接收器）
→ `RingState.notifyOrientationChanged()` → `BatteryHideHook.refreshAll()`。
配置项 `restore_battery_on_landscape` 默认 true。

**实测结论**：竖屏隐藏 / 横屏恢复 / 横→竖再隐藏 三项，逐条写实际观察到的日志行。
```

- [ ] **Step 9: 同步 AGENTS.md**

`AGENTS.md` 的「已知待验证项」里，若 Step 4/6 有任一项未达预期，追加一行说明当前只在本机验证、以及未覆盖分支；全部通过则把结论并入该列表（一句话即可，不新开章节）。

- [ ] **Step 10: 收尾**

不要提交。把改动文件清单交给用户，由用户决定何时 commit。

---

## Self-Review 记录

- **需求覆盖**：① 横屏恢复原生电池图标 → Task 2/3/4；② 加开关 → Task 1（配置链路）+ Task 5（UI）；③ 环横屏行为不变 → Task 6 Step 4 显式验收。
- **占位符扫描**：无 TBD/TODO；Task 6 Step 8 的下划线是**待真机实测填入的数据位**，不是实现占位。
- **类型一致性**：`shouldForceHideBattery(view: View?)`（Task 2 定义，Task 3 三处调用）、`isLandscape(view: View?)`（仅 RingState 内部使用）、`restoreBatteryOnLandscape` / `KEY_RESTORE_BATTERY_ON_LANDSCAPE` = `"restore_battery_on_landscape"`（Task 1 四处、Task 5 一处引用）、`onOrientationChanged` / `notifyOrientationChanged()`（Task 2 定义，Task 4 两处使用）、`containerRequests`（Task 3 Step 1 定义，Step 2/3 使用）——签名与命名全部对齐。
