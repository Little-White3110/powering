# 截图时隐藏电量环（独立开关）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 截图（及录屏）画面中不再包含电量环，设置页提供独立开关，开关热生效、无需重启 SystemUI。

**Architecture:** 不做"检测截图→临时隐藏"，而是给环的唯一渲染载体——独立窗口（`RingWindowController`，type=2006）——加上 `WindowManager.LayoutParams.FLAG_SECURE`。SurfaceFlinger 对安全层（secure layer）的规则是"物理屏幕正常合成、非特权采集一律排除"，因此截图合成天然不含环，零时序竞争。开关变化经既有配置链路（PrefsStore → 广播 → HookPrefs → RingState 配置签名分支）同步到窗口 flags，新增一条主线程回调 `onConfigApplied` 驱动 `updateViewLayout`。

**Tech Stack:** Kotlin、miuix（SwitchPreference）、现有 Xposed/LSPosed 架构（本功能核心改动不新增任何 Hook）、JUnit4 JVM 单测。

---

## 机制选型依据（逆向实证，2026-10-03）

写代码前先理解为什么这样做。以下结论来自对 `work/sysui/`（SystemUI 17.03.260226.r）的静态分析，工具为 `work/find_classes.py` / `dump_class.py` / `xref.py` / `method_refs.py`：

1. **本机截图管线的采集点**：`com.android.systemui.screenshot.ImageCaptureImpl.captureDisplay(ILandroid/graphics/Rect;)Landroid/graphics/Bitmap;`，内部调用 `android.view.IWindowManager.captureDisplay` + `android.window.ScreenCaptureInternal$CaptureArgs$Builder`（仅 `setSourceCrop`，**未请求捕获安全层**）。`xref.py` 反查该方法的调用方共三路：
   - `ScreenshotController$$ExternalSyntheticLambda1`（全屏截图）
   - `PolicyRequestProcessor$captureDisplay$2`（策略处理器）
   - `ScreenshotInteractor$requestPartialScreenshot$bitmap$1`（部分截图）
   
   即**一个采集点覆盖全屏/策略/部分三类截图**。系统级入口 `TakeScreenshotService` 已在 SystemUI 的 manifest 中注册（二进制 manifest 字符串池已确认）。

2. **为什么选 FLAG_SECURE 而不是 Hook 采集点**：
   - 采集走 `ScreenCaptureInternal.CaptureArgs`，调用方未置"捕获安全层"位 ⇒ SurfaceFlinger 会**排除**标记了 secure 的 layer。`FLAG_SECURE` 挂在窗口 LayoutParams 上是公开稳定 API，不新增任何版本敏感的 Hook 点——符合本项目"优先走系统原生路径"的约定。
   - 若走"Hook captureDisplay → before 里藏环"：Hook 回调运行在后台协程线程，且截图合成读的是**最后一次提交的帧**——View 级 invalidate 是异步的，来不及；SF 层事务虽可同步，但需要跨线程取窗口 SurfaceControl、处理多次采集计数，复杂度与失败模式都显著更高。该方案保留为附录备案，仅当真机验证 FLAG_SECURE 不生效时启用。
   - 副作用（可接受并在设置页文案说明）：录屏、投屏画面同样不含环（同一 SF 规则）；环在**物理屏幕上始终正常显示**。

3. **环的渲染载体唯一**：`SystemUiHooks.kt` 文件头注明"状态栏视图注入与挖孔覆盖层 onDraw 两套实验载体保留在代码库中但不启用"——即 `StatusBarInjectHook` 与 `CutoutRingHook` 均未安装，唯一的环窗口是 `RingWindowController.attach()` 添加的独立窗口。**只需改一个窗口的 flags。**

4. **配置热生效链路已存在**：设置页写 prefs → 发 `com.powerring.hole.CONFIG_CHANGED` 广播 → `BatteryObserver` 收到 → `HookPrefs.invalidate()` → 重读 provider → `RingState.invalidateAll()` → 下一次 `onCutoutDraw` 检出配置签名变化。窗口 flags 不能在绘制线程改，因此本计划给这条链补一个**主线程回调** `RingState.onConfigApplied`（与既有 `onCutoutResolved`/`onOrientationChanged` 同模式）。

## 文件结构总览

| 文件 | 动作 | 职责 |
|---|---|---|
| `app/src/test/java/com/powerring/hole/ring/SecureFlagTest.kt` | 新建 | flags 计算纯函数的 JVM 单测 |
| `app/src/main/java/com/powerring/hole/ring/RingConfig.kt` | 修改 | 新键常量 + `hideOnScreenshot` 字段 |
| `app/src/main/java/com/powerring/hole/ring/RingWindowController.kt` | 修改 | `secureFlagFor` 纯函数；attach 时按配置加 flag；`applyScreenshotHide()` 热切换 |
| `app/src/main/java/com/powerring/hole/core/HookPrefs.kt` | 修改 | 跨进程读取新键 |
| `app/src/main/java/com/powerring/hole/ui/ConfigProvider.kt` | 修改 | provider 暴露新列 |
| `app/src/main/java/com/powerring/hole/ui/PrefsStore.kt` | 修改 | 设置页读取新键 |
| `app/src/main/java/com/powerring/hole/ring/RingState.kt` | 修改 | 签名纳入新键 + `onConfigApplied` 回调 |
| `app/src/main/java/com/powerring/hole/hook/SystemUiHooks.kt` | 修改 | 接线回调 → 控制器 |
| `app/src/main/java/com/powerring/hole/ui/SettingsScreen.kt` | 修改 | 新增开关行 |
| `挖孔环形电量LSP模块-可行性分析报告.md` | 修改 | 新增 §17 记录机制与真机结论 |
| `AGENTS.md` | 修改 | 已知待验证项同步 |

不做的事：不改 `ConfigJson`（它只序列化外观项，行为开关不进导出契约）；不新增 Hook；不引入新的跨进程通信。

---

### Task 1: 配置键与 flags 计算纯函数（TDD）

**Files:**
- Create: `HolePowerRing/app/src/test/java/com/powerring/hole/ring/SecureFlagTest.kt`
- Modify: `HolePowerRing/app/src/main/java/com/powerring/hole/ring/RingConfig.kt`
- Modify: `HolePowerRing/app/src/main/java/com/powerring/hole/ring/RingWindowController.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.powerring.hole.ring

import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Test

class SecureFlagTest {

    @Test
    fun enabledAddsSecureFlag() {
        assertEquals(
            WindowManager.LayoutParams.FLAG_SECURE,
            secureFlagFor(0, hideOnScreenshot = true),
        )
    }

    @Test
    fun disabledClearsSecureFlag() {
        val withSecure = WindowManager.LayoutParams.FLAG_SECURE or 0x40
        assertEquals(0x40, secureFlagFor(withSecure, hideOnScreenshot = false))
    }

    @Test
    fun otherFlagsArePreserved() {
        val base = 0x40 or 0x10
        assertEquals(
            base or WindowManager.LayoutParams.FLAG_SECURE,
            secureFlagFor(base, hideOnScreenshot = true),
        )
    }

    @Test
    fun enabledIsIdempotent() {
        val once = secureFlagFor(0x40, hideOnScreenshot = true)
        assertEquals(once, secureFlagFor(once, hideOnScreenshot = true))
    }

    @Test
    fun defaultConfigEnablesHideOnScreenshot() {
        assertEquals(true, RingConfig.DEFAULT.hideOnScreenshot)
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd HolePowerRing && ./gradlew test --tests "com.powerring.hole.ring.SecureFlagTest"`（Windows 用 `gradlew.bat`）
Expected: **编译失败**，报 `unresolved reference: secureFlagFor` 与 `unresolved reference: hideOnScreenshot`

- [ ] **Step 3: 在 RingConfig.kt 加键与字段**

`RingConfig` 数据类中，`collapseOnIsland` 字段之后插入：

```kotlin
    /**
     * 截图时不出现在画面里（窗口层 FLAG_SECURE）。
     * 同一规则使录屏/投屏画面也不含环；环在物理屏幕上始终正常显示。
     */
    val hideOnScreenshot: Boolean = true,
```

`companion object` 中，`KEY_COLLAPSE_ON_ISLAND` 之后插入：

```kotlin
        const val KEY_HIDE_ON_SCREENSHOT = "hide_on_screenshot"
```

- [ ] **Step 4: 在 RingWindowController.kt 加纯函数**

`object RingWindowController {` 之前（同文件顶层）加入：

```kotlin
/**
 * 按「截图时隐藏」开关计算窗口 flags：开启时叠加 FLAG_SECURE，关闭时清除该位，
 * 其余位原样保留。幂等，可对同一 flags 反复调用。
 */
internal fun secureFlagFor(flags: Int, hideOnScreenshot: Boolean): Int =
    if (hideOnScreenshot) flags or WindowManager.LayoutParams.FLAG_SECURE
    else flags and WindowManager.LayoutParams.FLAG_SECURE.inv()
```

- [ ] **Step 5: 运行测试确认通过**

Run: `cd HolePowerRing && ./gradlew test --tests "com.powerring.hole.ring.SecureFlagTest"`
Expected: PASS（5 个用例全绿；`FLAG_SECURE` 是编译期常量，JVM 测试无需 Android 运行时）

- [ ] **Step 6: Commit**

```bash
git add HolePowerRing/app/src/test/java/com/powerring/hole/ring/SecureFlagTest.kt \
  HolePowerRing/app/src/main/java/com/powerring/hole/ring/RingConfig.kt \
  HolePowerRing/app/src/main/java/com/powerring/hole/ring/RingWindowController.kt
git commit -m "feat(ring): 新增 hide_on_screenshot 配置键与窗口 FLAG_SECURE 计算"
```

---

### Task 2: 配置管线贯通（HookPrefs / ConfigProvider / PrefsStore）

**Files:**
- Modify: `HolePowerRing/app/src/main/java/com/powerring/hole/core/HookPrefs.kt:146`
- Modify: `HolePowerRing/app/src/main/java/com/powerring/hole/ui/ConfigProvider.kt:53` 与 `:97` 附近
- Modify: `HolePowerRing/app/src/main/java/com/powerring/hole/ui/PrefsStore.kt:30`

- [ ] **Step 1: HookPrefs.load() 读新列**

在 `HookPrefs.kt` 的 `RingConfig(...)` 构造处，`collapseOnIsland = ...` 一行之后插入：

```kotlin
            hideOnScreenshot = toBool(
                values[RingConfig.KEY_HIDE_ON_SCREENSHOT], d.hideOnScreenshot,
            ),
```

- [ ] **Step 2: ConfigProvider 暴露新列**

`valueOf()` 的 `when` 中，`KEY_COLLAPSE_ON_ISLAND` 分支之后插入：

```kotlin
        RingConfig.KEY_HIDE_ON_SCREENSHOT ->
            prefs.getBoolean(key, RingConfig.DEFAULT.hideOnScreenshot)
```

`ALL_KEYS` 数组中，`RingConfig.KEY_COLLAPSE_ON_ISLAND,` 之后插入一行：

```kotlin
            RingConfig.KEY_HIDE_ON_SCREENSHOT,
```

（漏掉这一步 provider 不返回该列，Hook 侧永远拿到默认值——验收清单 Step 5 会暴露为"开关关不掉"。）

- [ ] **Step 3: PrefsStore.load() 读新键**

`load()` 的 `RingConfig(...)` 构造处，`collapseOnIsland = ...` 一行之后插入（本文件惯例是字面量默认值，与 `KEY_COLLAPSE_ON_ISLAND` 行写法一致）：

```kotlin
            hideOnScreenshot = p.getBoolean(RingConfig.KEY_HIDE_ON_SCREENSHOT, true),
```

- [ ] **Step 4: 全量单测确认无回归**

Run: `cd HolePowerRing && ./gradlew test`
Expected: PASS（既有全部单测 + Task 1 新增 5 个）

- [ ] **Step 5: Commit**

```bash
git add HolePowerRing/app/src/main/java/com/powerring/hole/core/HookPrefs.kt \
  HolePowerRing/app/src/main/java/com/powerring/hole/ui/ConfigProvider.kt \
  HolePowerRing/app/src/main/java/com/powerring/hole/ui/PrefsStore.kt
git commit -m "feat(config): 贯通 hide_on_screenshot 的跨进程读取链路"
```

---

### Task 3: RingState 配置签名纳入新键 + 主线程应用回调

**Files:**
- Modify: `HolePowerRing/app/src/main/java/com/powerring/hole/ring/RingState.kt:133`（signature）、`:385` 附近（回调区）、`:459` 附近（onCutoutDraw）

- [ ] **Step 1: signature() 纳入新键**

`private fun RingConfig.signature()` 第一行改为（新增 `$hideOnScreenshot`）：

```kotlin
    private fun RingConfig.signature() =
        "$ringEnabled|$hideOnScreenshot|$strokeWidthDp|$offsetXDp|$offsetYDp|$scale|" +
            "$colorMode|$customColor|" +
            "$collapseOnImmersive|$collapseOnIsland|" +
            "${stateColors.normal},${stateColors.low},${stateColors.powerSave}," +
            "${stateColors.performance},${stateColors.charging}|" +
            CustomColors.encodeRanges(levelRanges)
```

- [ ] **Step 2: 声明 onConfigApplied 回调**

在 `onCutoutResolved` 声明之后（同款 `@Volatile var ... (() -> Unit)?` 模式）加入：

```kotlin
    /**
     * 配置签名变化的回调（主线程，onCutoutDraw 签名分支内触发）。
     * 窗口 flags 类配置（如截图隐藏的 FLAG_SECURE）必须在主线程
     * updateViewLayout，不能走绘制路径——由 RingWindowController 注册。
     */
    @Volatile
    var onConfigApplied: (() -> Unit)? = null
```

- [ ] **Step 3: onCutoutDraw 签名分支调用回调**

`onCutoutDraw` 中 `if (sig != lastConfigSig)` 分支改为：

```kotlin
        if (sig != lastConfigSig) {
            lastConfigSig = sig
            animateCollapseTo(collapseTarget())
            applyIslandColorFreeze()
            try {
                onConfigApplied?.invoke()
            } catch (t: Throwable) {
                ModuleLog.e("配置应用回调异常", t)
            }
            invalidateAll()
        }
```

（try/catch 是本仓库铁律：回调逃逸异常会打断挖孔 View 的 onDraw。该分支位于 `if (!c.ringEnabled || !screenOn) return` 之前，环关闭时开关同步依然可达。）

- [ ] **Step 4: 编译验证**

Run: `cd HolePowerRing && ./gradlew test`
Expected: PASS（纯接线，无行为变化）

- [ ] **Step 5: Commit**

```bash
git add HolePowerRing/app/src/main/java/com/powerring/hole/ring/RingState.kt
git commit -m "feat(ring): 配置签名纳入 hide_on_screenshot 并新增 onConfigApplied 回调"
```

---

### Task 4: 环窗口应用 FLAG_SECURE + 开关热切换

**Files:**
- Modify: `HolePowerRing/app/src/main/java/com/powerring/hole/ring/RingWindowController.kt`

- [ ] **Step 1: attach() 按当前配置计算 flags**

`attach()` 中 `params` 的 `flags = (...)` 赋值改为在既有六个 flag 的基础上套 [secureFlagFor]：

```kotlin
            // 不获取焦点、不拦截任何触摸（整个窗口触摸穿透）；
            // FLAG_SECURE 由 secureFlagFor 按「截图时隐藏」开关叠加/清除，
            // 使 SurfaceFlinger 在截图/录屏合成时排除本窗口（物理屏幕不受影响）
            flags = secureFlagFor(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_SPLIT_TOUCH or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                HookPrefs.get().hideOnScreenshot,
            ),
```

文件头补 import（`RingState` 已示范 ring → core 依赖）：

```kotlin
import com.powerring.hole.core.HookPrefs
```

- [ ] **Step 2: 记录 attach 时已应用的状态**

`attach()` 成功路径（`attached = true` 之后、日志之前）加入：

```kotlin
            appliedHideOnScreenshot = if (HookPrefs.get().hideOnScreenshot) 1 else 0
```

并在对象字段区（`private var retried = false` 之后）加状态字段：

```kotlin
    /** 最近一次已应用到窗口的开关值；-1 表示尚未应用（attach 失败重试路径会重新同步） */
    @Volatile
    private var appliedHideOnScreenshot: Int = -1
```

- [ ] **Step 3: 新增 applyScreenshotHide()**

`attach()` 之后加入：

```kotlin
    /**
     * 把「截图时隐藏」开关同步到环窗口 flags。
     *
     * 调用链：配置变化 → HookPrefs.refresh() → RingState.invalidateAll() →
     * 下一次 onCutoutDraw 检出签名变化 → onConfigApplied（主线程）→ 本方法。
     * 再 post 一层是因为 onCutoutDraw 处于绘制中，updateViewLayout 会重入布局；
     * 状态去重放在 post 之前，避免每次重绘都调度空任务。
     */
    fun applyScreenshotHide() {
        val view = ringView ?: return
        val target = HookPrefs.get().hideOnScreenshot
        if (appliedHideOnScreenshot == if (target) 1 else 0) return
        view.post {
            try {
                val lp = view.layoutParams as? WindowManager.LayoutParams
                val wm = windowManager
                if (wm != null && lp != null) {
                    val old = lp.flags
                    lp.flags = secureFlagFor(old, target)
                    if (lp.flags != old) {
                        wm.updateViewLayout(view, lp)
                        ModuleLog.i("环窗口截图隐藏已${if (target) "开启" else "关闭"}（FLAG_SECURE）")
                    }
                    appliedHideOnScreenshot = if (target) 1 else 0
                }
            } catch (t: Throwable) {
                ModuleLog.e("同步环窗口截图隐藏开关失败", t)
            }
        }
    }
```

- [ ] **Step 4: 编译验证**

Run: `cd HolePowerRing && ./gradlew test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add HolePowerRing/app/src/main/java/com/powerring/hole/ring/RingWindowController.kt
git commit -m "feat(ring): 环窗口应用 FLAG_SECURE 并支持截图隐藏开关热切换"
```

---

### Task 5: SystemUiHooks 接线 + 设置页开关

**Files:**
- Modify: `HolePowerRing/app/src/main/java/com/powerring/hole/hook/SystemUiHooks.kt:85`
- Modify: `HolePowerRing/app/src/main/java/com/powerring/hole/ui/SettingsScreen.kt:233`

- [ ] **Step 1: SystemUiHooks 注册回调**

`Application.onCreate` 的 afterHookedMethod 中，`RingWindowController.attach(app)` 之后加入一行（与 `RingState.onCutoutResolved = { ... }` 同款接线模式）：

```kotlin
                        RingWindowController.attach(app)
                        RingState.onConfigApplied = { RingWindowController.applyScreenshotHide() }
```

（外层 try/catch 已存在，回调自身也已兜底，符合 Hook 异常铁律。）

- [ ] **Step 2: 设置页新增开关行**

`SettingsScreen.kt` 的 `SwitchTabContent` 中，「有岛时隐藏圆环」的 `SwitchPreference(...)` 与卡片收尾之间（`:233` 的右括号之后）插入：

```kotlin
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    SwitchPreference(
                        checked = config.hideOnScreenshot,
                        onCheckedChange = { enabled ->
                            PrefsStore.setBoolean(
                                ctx, RingConfig.KEY_HIDE_ON_SCREENSHOT, enabled,
                            )
                            update(config.copy(hideOnScreenshot = enabled))
                        },
                        title = "截图时隐藏圆环",
                        summary = "截图与录屏画面中不再包含电量环（屏幕上仍正常显示）；开关即时生效，无需重启系统界面",
                        enabled = config.ringEnabled,
                    )
```

- [ ] **Step 3: 组装 debug 包**

Run: `cd HolePowerRing && ./gradlew test assembleDebug`
Expected: `BUILD SUCCESSFUL`，产物 `app/build/outputs/apk/debug/app-debug.apk`

- [ ] **Step 4: Commit**

```bash
git add HolePowerRing/app/src/main/java/com/powerring/hole/hook/SystemUiHooks.kt \
  HolePowerRing/app/src/main/java/com/powerring/hole/ui/SettingsScreen.kt
git commit -m "feat(ui): 设置页新增「截图时隐藏圆环」独立开关"
```

---

### Task 6: 真机验收（必须全部执行，不通过不算完成）

前置：`adb install -r HolePowerRing/app/build/outputs/apk/debug/app-debug.apk` → LSPosed 管理器启用模块（作用域 `com.android.systemui`）→ `adb shell killall com.android.systemui`。日志窗口：`adb logcat -s HolePowerRing`。

- [ ] **Step 1: 基线** — 启动日志出现「环形电量独立窗口已添加」，环正常显示，状态栏无异常
- [ ] **Step 2: 默认开（截图）** — 电源键+音量下截图 → 相册查看：**截图中无环**，其余内容正常（挖孔区域、状态栏图标不受影响）
- [ ] **Step 3: 其他入口** — 本机提供的其他截图方式（三指下滑/通知栏开关/局部截图）逐一验证成品图无环
- [ ] **Step 4: 录屏（预期副作用确认）** — 录屏 10 秒 → 回放无环。此项是 FLAG_SECURE 的固有语义，确认可接受；若不可接受，在设置页 summary 与报告 §17 记录，后续拆分开关
- [ ] **Step 5: 热关闭** — 设置页关闭「截图时隐藏圆环」→ 等待 1 秒（logcat 出现「环窗口截图隐藏已关闭」）→ 再截图 → **环出现在截图中**；SystemUI 无重启、界面无闪烁
- [ ] **Step 6: 热恢复** — 重新打开开关 → 截图 → 环再次消失
- [ ] **Step 7: 兜底判定** — 若 Step 5 热关闭不生效：重启 SystemUI 后复测并记录（说明 flags 同步链路断点，回到代码排查 `onConfigApplied` 是否触发——logcat 无「配置已重新加载」则查广播链，有重载但无 flags 日志则查签名分支）；若热生效正常则此步跳过
- [ ] **Step 8: 结论落档** — 把每步结果记入可行性报告 §17（下一 Task 的文档骨架已留好槽位）

---

### Task 7: 文档同步（结论变更必须回写，仓库约定）

**Files:**
- Modify: `挖孔环形电量LSP模块-可行性分析报告.md`（§16 之后新增 §17）
- Modify: `AGENTS.md`（「已知待验证项」清单）

- [ ] **Step 1: 可行性报告新增 §17**

```markdown
## 17. 截图时隐藏电量环（2026-10-03）

### 17.1 信号源调研（静态分析）

- 目标 ROM 的截图采集点为 `com.android.systemui.screenshot.ImageCaptureImpl.captureDisplay(ILandroid/graphics/Rect;)`，
  经 `IWindowManager.captureDisplay` + `ScreenCaptureInternal$CaptureArgs`（仅 setSourceCrop，未请求捕获安全层）。
- xref 反查调用方三路：`ScreenshotController`（全屏）、`PolicyRequestProcessor`（策略）、
  `ScreenshotInteractor$requestPartialScreenshot`（部分截图）——单点覆盖全部截图形态。
- 系统级入口 `TakeScreenshotService` 已在 SystemUI manifest 注册；MIUI 侧
  `com.miui.systemui.screenshot.ScreenshotHelper.takeScreenshot()` 仅广播
  `android.intent.action.CAPTURE_SCREENSHOT`，最终仍汇入上述管线。

### 17.2 机制选型

选定：环窗口（type=2006，唯一启用载体）加 `WindowManager.LayoutParams.FLAG_SECURE`。
SurfaceFlinger 对安全层的规则是物理屏幕正常合成、非特权采集排除——截图/录屏/投屏
天然不含环，零时序竞争、零新增 Hook 点。

放弃：Hook `captureDisplay` 前置藏环。原因：Hook 回调在后台协程线程，截图合成读
最后一次提交的帧，View 级 invalidate 来不及；SF 层事务方案需跨线程取
SurfaceControl 且要处理多路采集计数，复杂度高。（备案见计划文档附录。）

副作用：录屏与投屏画面同样不含环（同一 SF 规则），设置页文案已注明。

### 17.3 真机验证结论（填入 Task 6 实测结果）

- [待真机验证] 默认开启时截图无环
- [待真机验证] 开关热生效（关闭后截图重新含环）
- [待真机验证] 部分截图/录屏行为
```

- [ ] **Step 2: AGENTS.md「已知待验证项」追加**

在清单末尾（「自定义配色四模式」条目之后）加入：

```markdown
- **截图时隐藏电量环（2026-10-03）**：机制为环窗口 FLAG_SECURE（SurfaceFlinger 非特权采集排除安全层），代码与 5 条 JVM 单测已通过，**真机验收尚未执行**（逐步清单见可行性分析报告 §17.3 与计划文档 Task 6）。逆向依据：采集点 `ImageCaptureImpl.captureDisplay` 未请求捕获安全层
```

（Task 6 验收通过后，把本条与 §17.3 的待验证标记改为实测结论；若验证失败，§17.2 附记失败现象并按附录备案改走 Hook 方案。）

- [ ] **Step 3: Commit**

```bash
git add "挖孔环形电量LSP模块-可行性分析报告.md" AGENTS.md
git commit -m "docs: 可行性报告新增 §17 截图隐藏机制与选型依据"
```

---

## 附录：备案方案（仅当 Task 6 证明 FLAG_SECURE 不生效时启用）

Hook `com.android.systemui.screenshot.ImageCaptureImpl` 的 `captureDisplay(ILandroid/graphics/Rect;)Landroid/graphics/Bitmap;`（类名/签名已在本 ROM 验证；按仓库铁律包裹 try/catch，找不到类时降级关闭功能）：

- **before**：采集计数 +1；首次计数时执行 SF 层隐藏——环窗口的 `SurfaceControl` 在 `attach()` 成功后于主线程经 `view.rootSurfaceControl`（API 29+）取一次并缓存；Hook 回调线程直接 `SurfaceControl.Transaction().hide(sc).apply()`。`Transaction` 线程安全，且事务先于截图请求进入 SF 主线程队列（FIFO），能赶在合成之前生效。**必须走 SF 层**：截图合成读取的是最后一次提交的帧，View 级 invalidate 异步，必然来不及。
- **after**：计数 −1；归零后向主线程 post 延时（常量 1500ms）恢复：`Transaction.show(sc).apply()`，并把计数复位逻辑用同步块保护（两路采集可能并发）。
- 该方案的收益仅剩"环在屏幕上随截图瞬间消失"的视觉反馈，成本是版本敏感的 Hook 点与并发计数——这正是首选方案避开的东西。
