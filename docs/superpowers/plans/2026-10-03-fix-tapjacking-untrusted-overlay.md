# 修复"点按劫持防护"全局拦截（环窗口被输入系统判为不受信覆盖层） 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 开启环形电量后，真机上**任意位置点按**都会弹「已拦截点按劫持行为」并丢失点击。消除该拦截，同时不丢失已有能力：环仍压在灵动岛之上（报告 §11 层带结论）、截图时仍隐藏环（§17 结论，真机已验证）。

> 注：执行时核对——**「下拉通知栏收起环」（报告 §18）已实现并真机验证**（`ShadeCollapseHook` / `RingCollapseLogic` 在树内，§18 已占）。故本计划结论落 **§19**；验收矩阵第 6 项为**必测项**（该功能已上线，不是未来项）。

**Architecture:** 取证结论是「环窗口在 InputDispatcher 里是**不受信覆盖层**」，且本机拦截是**全局**的（不限于环覆盖的那 222px），说明 MIUI 在输入层对不受信覆盖层做了整机级拦截。因此修复分三层，按"改动小 → 语义干净"排序：
1. **去掉 `FLAG_SECURE`**——它在本机被实证挡不住特权截图（§17.2），却被证实会进输入层的 `fl=`，是昨天新加的、与"开始拦截"时间吻合的唯一变量；
2. **把环窗口收小**到只包挖孔的方形区域——`frame` 是"是否遮挡下方窗口"的几何判据，缩小遮蔽面积是**不依赖任何厂商私有标志**的健壮修复；
3. **拿 `TRUSTED_OVERLAY`**（本机所有系统级窗口都有，唯独我们没有）——若前两步不够，再走这条路，并预留类型切换 / SystemServer Hook 两级后备。

**Tech Stack:** Kotlin、`WindowManager.LayoutParams` 反射、Xposed/LSPosed（后备路径）、JUnit4 JVM 单测（纯几何计算）。

---

> **实际结局（2026-10-03 收尾回填）**：三层里**只有第三层生效**，且入口与本文设想不同。
> - Step A（删 `FLAG_SECURE`）**证伪**——`dumpsys` 确认 `fl=` 已无 SECURE，拦截照旧。移除本身保留，理由改为「§17.2 已证它对截图无用」。
> - Step B（收窄窗口）**未执行**：它要动的正是下面那条风险，且被 Step C 抢先解决。
> - Step C（拿 `TRUSTED_OVERLAY`）**成功**，但不是反射 `systemUiVisibility`——本机 `LayoutParams` 上**没有** `SYSTEM_FLAG_TRUSTED_OVERLAY`（真机 `NoSuchFieldException`），真名是 `PRIVATE_FLAG_TRUSTED_OVERLAY = 0x20000000`，且框架自带公开方法 `setTrustedOverlay()`。`addView` 前调它即可，**窗口类型/尺寸一行没改**，层带与绘制坐标都不受影响。
> - **新增风险（本文漏了）**：`adb install -r` 不保证重启 SystemUI，本项目因此白跑一轮"验证"。判据见 AGENTS.md「构建与验证」下的重启困境说明。
> 结论与取证全文见可行性分析报告 §19。

## 一、可行性结论（先回答"能不能做到"）

**能做到。** 依据全部来自 2026-10-03 真机取证（设备 `25102RKBEC / myron`，adb-serial `adb-6f1adaee-K5bSQq._adb-tls-connect._tcp`），非推测：

### 1.1 决定性证据：`dumpsys input` 的 InputDispatcher 窗口列表

```
11: name=ff8a64b HolePowerRingWindow, inputConfig=NOT_FOCUSABLE | NOT_TOUCHABLE,
    alpha=0.799805, frame=[0,0][1200,222], touchableRegion=[0,0][1200,222],
    ownerUid=10217, touchOcclusionMode=USE_OPACITY
12: name=1c7711 AntiMistakeTouchView, inputConfig=NOT_FOCUSABLE | NOT_TOUCHABLE | TRUSTED_OVERLAY,
    alpha=0.700195, frame=[0,2578][1200,2608], ownerUid=10144, touchOcclusionMode=USE_OPACITY
13: name=df9c949 NotificationShade, inputConfig=TRUSTED_OVERLAY | WATCH_OUTSIDE_TOUCH | DISABLE_USER_ACTIVITY, alpha=1
14: name=7698eb4 StatusBar, inputConfig=NOT_FOCUSABLE | TRUSTED_OVERLAY, alpha=1
```

**我们的环窗口是全列表里唯一一个 `frame` 落在屏幕内、却没有 `TRUSTED_OVERLAY` 的窗口。** 其余每一个系统级窗口——`StatusBar`、`NotificationShade`、`NavigationBar0`、同为 `ty=2006` 的 `AntiMistakeTouchView`、以及同进程的 `DynamicIslandWindow`——在 `dumpsys window` 里都带 `pfl=TRUSTED_OVERLAY`。这与用户报的现象（任意位置点按都被拦）构成同一件事的两端。

### 1.2 `ty=2006` 本身不阻断信任——同层带有先例

`AntiMistakeTouchView` 与环窗口**窗口类型完全相同**（`dumpsys window` 均为 `ty=SYSTEM_OVERLAY`、`mToken type=2006`、`mBaseLayer=231000`），差别只在：它有 `pfl=TRUSTED_OVERLAY`，我们没有。所以层带/类型不是障碍，**2006 是可以被授信的**。

### 1.3 权限不是障碍——宿主进程持有授信所需权限

`dumpsys package com.android.systemui`：

| 权限 | 状态 |
|---|---|
| `android.permission.ADD_TRUSTED_DISPLAY` | `install permissions: granted=true` |
| `android.permission.INTERNAL_SYSTEM_WINDOW` | `install permissions: granted=true` |
| `android.permission.SYSTEM_ALERT_WINDOW` | `granted=true`（也是 `dumpsys window` 里环窗口显示的 `appop`） |

框架给窗口打 `TRUSTED_OVERLAY` 的门槛条件是 `INTERNAL_SYSTEM_WINDOW` / `ADD_TRUSTED_DISPLAY` 之一，环窗口是 **SystemUI 自己 `addView` 的**，天然满足。

### 1.4 灵动岛没走任何私有 API——说明"授信"不需要额外动作

`work/dump_class.py plugin miui.systemui.dynamicisland.window.DynamicIslandWindowController`（classes2.dex）显示：岛窗口只用普通的 `WindowManager.LayoutParams` 字段（`private final lp / lpChanged`），**没有任何 `setPrivateFlag` / 反射标志位调用**。它的授信是框架按类型+权限自动给的。⇒ 我们与岛的差异不在"岛做了额外的事"，而在**我们选的 2006 落在了厂商默认不授信的区间**（后备路径 2 正是利用这一点）。

### 1.5 `FLAG_SECURE` 是本次改动里唯一的新变量，且它已被自证无用

`git show 987f29f`（2026-10-03 06:06，feat: 截图时隐藏电量环）给环窗口叠加了 `FLAG_SECURE`。而 AGENTS.md「已知待验证项」与可行性分析报告 §17.2 已经**实证 FLAG_SECURE 在本机挡不住系统截图**（`fl=` 含 SECURE 仍被采到），真正生效的是 `Transaction.setSkipScreenshot` + 采集期 `setVisibility(false)`。

即：**`FLAG_SECURE` 对截图隐藏零贡献，却把 `SECURE` 写进了输入层可见的 `fl=`。** 用户报"开环后劫持拦截"与这次提交时间吻合，删掉它属于"移除无效且有副作用的位"，不是砍功能。

### 1.6 一个未解但可绕开的疑点：环窗口 `alpha=0.799805`

我们代码里从未设置窗口 alpha。整机窗口列表中只有两个非 1.0 的 alpha：环（0.799805）与 `AntiMistakeTouchView`（0.700195）。⇒ 厂商会把「无 `TRUSTED_OVERLAY` 的覆盖层」压到某个 alpha，或按各自配置设置。这条正好佐证 1.1：**厂商对未授信覆盖层有特殊处置**。计划里把"alpha 是否随 SECURE/授信状态变化"列为观测项，但不把它当作要修复的目标——窗口收小 + 授信后它自然无关。

### 1.7 结论

拦截的直接判据是「**不受信 + 有面积 + 有内容**」的覆盖层。三条通路都能削弱这个判据，且互不冲突：删 `SECURE` 去掉"隐藏内容"的表征、缩窗口去掉"面积"、拿 `TRUSTED_OVERLAY` 去掉"不受信"。**逐条上、每步用 `dumpsys` 复核**，不做打包式修改。

## 二、风险与取舍

| 风险 | 说明 | 处置 |
|---|---|---|
| 缩窗口与 §11 层带结论冲突 | 只改 `width/height/gravity`，**不动 `type`/`mBaseLayer`** | 层带仍 231000 > 灵动岛 191000，压岛结论不变；真机 A/B 复验环仍在岛之上 |
| 缩窗口切掉偏移/外沿后的半环 | 设置页允许最大 20dp 偏移（`OFFSET_MAX`），环半径外沿需余量 | 用 `CutoutGeometry` 的实际几何算包围盒并加 `EXTRA_BOTTOM_DP` 同级余量，越界由单测覆盖；窗口高度已有 insets 动态修正通路可复用 |
| 删 `FLAG_SECURE` 后某些采集路径漏出环 | §17 里 `captureDisplay` Hook 兜底给 AOSP 管线 ROM | 主效机制（`setSkipScreenshot` + 采集期 `setVisibility`）不动；截图六项验收复跑一次确认环仍被藏 |
| `TRUSTED_OVERLAY` 拿不到 | `pfl` 由 WMS 设置，无公开 API | 三级后备见 §五决策树，逐级验证，**不一次全上** |
| 改成 `ty=2000` 后层级被岛反超 | 2000 的层带是本机实测值，不是 ROM 契约 | 仅当 Step A/B 都无效才启用；启用后**必须** `dumpsys window windows | grep mBaseLayer` 重测 2000 是否 > 191000，若否则放弃此后备 |
| Hook `WindowManagerService` 扩大作用域 | 模块 `module_scope` 只有 `com.android.systemui`，WMS 在 system_server | 后备 3 需新增作用域并同步 `arrays.xml` + LSPosed 重新启用；属重操作，须单独获批，且 `beforeHookedMethod` 内只做一次赋值并包 `try/catch (Throwable)`（AGENTS.md 纪律 1/8） |
| 全局拦截与环无关（误诊） | 若删 SECURE + 缩窗口 + 授信三步后仍拦 | 说明来源不是本模块。用"关模块开关 → 重启 SystemUI → 环消失"做二分确认，再转查其它覆盖层（`gxzw_touch` / `gxzw_anim` 等第三方窗口在列表里同样可疑） |

**绝不做的事：** 不用"砍功能"绕开缺陷（不改只读、不隐藏信息）；不在 `ClassLoader.loadClass` 回调里反射枚举或装 Hook；不改 `apks/`；不把 `xposedstub` 改成 `implementation`；不引入新的进程间通信。

## 三、文件结构总览

| 文件 | 动作 | 职责 |
|---|---|---|
| `ring/RingWindowController.kt` | 修改 | 删 `secureFlagFor` 的 SECURE 叠加；窗口尺寸改为按挖孔几何计算；（Step C）尝试反射设 `systemUiVisibility` |
| `ring/RingWindowGeometry.kt` | 新建 | 无 Android 依赖的纯函数：由 cutout 宽高 + 环参数 + 偏移算窗口 `w/h/x/y`，供 JVM 单测 |
| `ring/SecureFlagTest.kt` | **删除** | 被测函数 `secureFlagFor` 随 Task 2 一起删除，文件失去对象 |
| `core/ModuleLog.kt` 调用点 | 修改 | attach 成功日志补打 `flags/systemUiVisibility/w/h`，便于 `dumpsys` 对照 |
| `挖孔环形电量LSP模块-可行性分析报告.md` | 修改 | **新增 §19**：点按劫持与覆盖层授信（§13 留给取色链路、§18 已被下拉收起占用，勿占错） |
| `AGENTS.md` | 修改 | 「已知待验证项」加一条结论 |
| `docs/superpowers/specs/…` | 不改 | 本轮无新契约 |

不改：`ScreenshotCaptureHook.kt`、`RingState.kt` 的隐藏/自愈逻辑、设置页开关语义（`hide_on_screenshot` 保持默认开、热生效）。

**本计划全程不执行 `git commit`**（用户约定：改动完成后由用户自行决定提交时机）。

---

### Task 1: 取证基线——把"当前状态"钉成可比对的证据

目标：在动任何代码前，留一份可 diff 的基线。**纯读取操作，不需要用户同意**（AGENTS.md/记忆：只读取证可直接做）。

**Files:** 无（只产出临时取证文件，落在 `%TEMP%`，不入仓库）

- [x]tep 1: 抓三个快照并存档到临时目录
  - `dumpsys window windows`（关注 `HolePowerRingWindow` 的 `fl=` / `pfl=`（应无）/ `alpha=` / `frame=` / `mBaseLayer=`）
  - `dumpsys input`（关注 `inputConfig=` 是否含 `TRUSTED_OVERLAY`、`frame=`、`alpha=`）
  - `dumpsys package com.android.systemui | grep -E "ADD_TRUSTED_DISPLAY|INTERNAL_SYSTEM_WINDOW"`
- [x]tep 2: 记录复现动作与提示原文（用户提供现象：任意位置点按均弹「已拦截点按劫持行为」）
- [x]tep 3: 输出对照表，作为后续每个 Step 的验收基准

### Task 2: Step A —— 去掉 `FLAG_SECURE`

目标：移除唯一新增变量，同时它对本机截图隐藏已被证明无用。

**Files:** `ring/RingWindowController.kt`、`ring/RingWindowGeometry.kt`（暂不涉及）、`src/test/.../SecureFlagTest.kt`

- [x]tep 1: **删掉 `secureFlagFor` 这个 helper 本身**，`params` 里直接构造 flags（`NOT_FOCUSABLE | NOT_TOUCHABLE | LAYOUT_NO_LIMITS | LAYOUT_IN_SCREEN | SPLIT_TOUCH | HARDWARE_ACCELERATED`）。不要把它改名成 `baseFlagsFor` 留着一个「开关参数已无意义」的纯函数——那是死抽象。
- [x]tep 2: **连带删除 `applyScreenshotHide()` 整条死链**（已核对现状：它是 `RingState.onConfigApplied` 的**唯一**用途，删掉后该回调就无人使用）：
  - `ring/RingWindowController.kt`：删 `applyScreenshotHide()`、删 `appliedHideOnScreenshot` 字段与 `attach()` 成功分支里对它的赋值；
  - `hook/SystemUiHooks.kt:93`：删 `RingState.onConfigApplied = { RingWindowController.applyScreenshotHide() }`；**`:94` 的 `onCutoutFrame = { syncScreenshotExclusion() }` 保留**，它才是截图隐藏的主效通路；
  - `ring/RingState.kt`：删 `var onConfigApplied`（:393）与 `:480` 的 `onConfigApplied?.invoke()`，并修正 :389 附近「窗口 flags 类配置（如 FLAG_SECURE）必须在主线程」的注释——该机制已不存在。
  - 截图隐藏行为不受影响：`hide_on_screenshot` 仍由 `syncScreenshotExclusion()`（`setSkipScreenshot`，每帧自愈）与 `begin/endScreenshotCapture()`（采集期 `setVisibility(false)`）实现，二者都不读窗口 flags。
- [x]tep 3: 处理 `src/test/.../SecureFlagTest.kt`（现有 5 处 `secureFlagFor` 断言）：被测对象删除后该文件整体删除；若仍想要一条「环窗口 flags 恒不含 FLAG_SECURE」的守卫，把它写成对 `params` 构造结果的断言（需要把 flags 构造抽成可测的纯值），**不要为了保测试而保留已无行为的函数**。
- [x]tep 4: `cd HolePowerRing && ./gradlew test assembleDebug` 通过。
- [x] Step 5: 交付安装与取证指令（实际由 agent 执行 `adb install -r`，用户自测点按）：安装 → LSPosed 已启用则直接 `adb shell killall com.android.systemui` → 复跑 Task 1 的三个快照 + 用户点按测试。
- [x] Step 6: **验收结果：SECURE 已消失但拦截照旧 ⇒ 本假设证伪，`FLAG_SECURE` 不是成因**（仍保留移除，理由改为 §17.2 截图无用）。转 Task 4。；截图隐藏日志（`setSkipScreenshot=` / `SF 层隐藏环窗口`）仍按预期出现；用户确认点按是否仍被拦。仍被拦 → 进 Task 3（不要跳到 Task 4）。

### Task 3: Step B —— 窗口从全屏宽收到只包挖孔

目标：消除"遮挡面积"这一判据。这是不依赖任何厂商私有标志的主修复。

> **执行前新增取证（2026-10-03，读码确认）——本任务不是小改：**
> 1. **绘制坐标与窗口原点强耦合。** `RingRenderer.draw()` 的圆心来自
>    `CutoutGeometry.resolve(view, outerRadius)`，而 `resolve()` 用
>    `view.getLocationOnScreen()` 把**屏幕坐标的 cutout 矩形**换算到 View 局部坐标
>    （CutoutGeometry.kt:42-49）⇒ `hole.cx/cy` 是**相对窗口原点**的值
>    （RingRenderer.kt:147-148 直接 `hole.cx + offset`）。
>    **窗口一旦从 `x=0` 移开，所有绘制坐标自动平移**，圆心仍会落在物理挖孔上——
>    这条链路本身自洽，但意味着"缩窗口"必须连绘制基准一起验证，不能只改 LayoutParams。
> 2. **`PowerRingView` 里有双份 insets 监听。** 类内 `init{}` 自带一个
>    `setOnApplyWindowInsetsListener`（PowerRingView.kt:29-32），
>    `RingWindowController.attach()` 又对同一实例设了另一个（:131 起）。
>    后设置的生效、前者被静默覆盖。缩窗口要动的正是后者，**顺手把前者删掉**，
>    否则下一个改代码的人会以为监听在类里。
> 3. **备选（成本极低、值得一并测）：`view.setShape(...)` 或 `setCornerRadius`**
>    让窗口的"不透明区域"只剩圆环附近，**不改几何坐标**——它对输入层的遮蔽判定
>    是否生效未验证，作为 Task 3 的 Step 0 先试，成了就不用动坐标系。
>
> **执行顺序调整（已向用户说明）**：Task 2 的 `FLAG_SECURE` 是 §17.2 实证无用、
> §19 认定的唯一新增变量，且改动零未知量——**先单独装这一版验证**。
> Task 3/4 是否要做，取决于 Task 2 的真机结果（决策树本就是这个形状）。

**Files:** 新建 `ring/RingWindowGeometry.kt`、新建 `src/test/java/com/powerring/hole/ring/RingWindowGeometryTest.kt`、修改 `ring/RingWindowController.kt`、`ring/PowerRingView.kt`

- [ ] Step 1: 先读 `ring/CutoutGeometry.kt` 与 `ring/RingConfig.kt`。可直接复用 `CutoutGeometry.resolve(view, outerRadius): Hole?`（返回 `cx/cy/holeRadius/clipRisk`，视图本地坐标）作为几何来源；`clipRisk` 已表达「圆超出左/上边界」的既有判定。新函数只消费这些值，不重复定义常量（`OFFSET_MAX=20f` 在 `RingConfig`）。
- [ ] Step 2: 写纯函数（无 Android import，便于 JVM 单测）：输入 `cutoutLeft/Top/Right/Bottom`、`ringOuterRadius`、`strokeWidth`、`offsetX/offsetY`、`density`，输出窗口 `width/height/x/y`（含 `MARGIN_DP` 同级余量），并对 `width/height` 做 `coerceAtLeast(1)`。
- [ ] Step 3: 单测覆盖：正常挖孔、偏移上下左右四极值、cutout 缺失（返回 0）时退化为安全默认、结果恒 ≥1、结果不越过屏幕边界。先跑红再实现（TDD）。
- [ ] Step 4: `attach()` 里把 `width = MATCH_PARENT` 换成计算值，`gravity` 从 `TOP|CENTER_HORIZONTAL` 换成 `TOP|START` + `x/y` 定位；**`height` 仍复用既有 insets 回调通路**（`setOnApplyWindowInsetsListener` 里已经会 `updateViewLayout` 调高度），把它扩展成同时校正宽度和 x/y，避免两套尺寸来源。
- [ ] Step 5: **不要动 `TYPE_RING_WINDOW=2006`、不要动 `FLAG_LAYOUT_NO_LIMITS`/`LAYOUT_IN_SCREEN`/`cutoutMode=ALWAYS`**——层带与挖孔区域依赖它们（§11）。
- [ ] Step 6: 更新 `RingWindowController` 顶部注释：说明窗口尺寸来源与"为什么不再全屏宽"，并写明 §11 层带结论仍成立。
- [ ] Step 7: `./gradlew test assembleDebug` 通过；交付真机指令：除 Task 2 的快照外，加验「环视觉完整、仍压在灵动岛之上、下拉收起与截图隐藏照常」。
- [ ] Step 8: **验收**：`dumpsys input` 里环的 `frame` 应收成近似方形（例 `[450,0][750,222]` 量级）；用户点按测试。仍被拦 → 进 Task 4。

### Task 4: Step C —— 尝试拿 `TRUSTED_OVERLAY`

目标：直接消掉"不受信"这一判据。**一次只试一条通路**，按代价从低到高。

**Files:** 修改 `ring/RingWindowController.kt`；后备 3 另见 `hook/` 与 `res/values/arrays.xml`

- [x] Step 1（**结论：前提有误，已更正**）: 静态确认可用的设标志入口。用反射读 `WindowManager$LayoutParams` 的公开字段 `systemUiVisibility`（若本机 framework 已改名/移除，读不到就记日志降级，不抛）。取 `SYSTEM_FLAG_TRUSTED_OVERLAY` 常量值：优先反射 `WindowManager.LayoutParams.SYSTEM_FLAG_TRUSTED_OVERLAY`；取不到时**不要猜数字**，改走 Step 3 的类型后备。
- [x] Step 2（**已按真机常量面重写**）: 在 `params` 构造处按位 or 上该 flag，并在 attach 成功日志里打回读到的 `systemUiVisibility`。
- [x] Step 3: **验证点（已通过）**：`dumpsys window windows` 里 `HolePowerRingWindow` 是否出现 `pfl=TRUSTED_OVERLAY`；`dumpsys input` 里 `inputConfig=` 是否出现 `TRUSTED_OVERLAY`。**没有出现就立刻回滚 Step 1-2**（`systemUiVisibility` 对非 Activity 窗口可能不传播），不要保留无效代码。
- [ ] Step 4: 后备 2（换类型）：把 `TYPE_RING_WINDOW` 试为 `2000`（`TYPE_SYSTEM`，落在系统窗口区间，框架按类型自动授信）。**启用前必须**先用只读取证确认本机 2000 的层带 > 灵动岛的 191000：临时装一版按 2000 加窗口，跑 `dumpsys window windows | grep -A3 mBaseLayer`；若 2000 落在岛之下则**放弃本后备**（宁可用 Step B 的窄窗口结论，也不牺牲 §11 的压岛能力）。
- [ ] Step 5: 后备 3（Hook WMS）：仅当 Step 4 也不可行时启用，且**先向用户报备**——它要求把 `system_server` 加进 `module_scope`（改 `res/values/arrays.xml`，需重新在 LSPosed 里激活并重启），并在 `WindowManagerService.addWindow(...)` 的 `beforeHookedMethod` 里，对 `attrs.title == "HolePowerRingWindow"` 的调用把 `systemUiVisibility` 或私有标志置上受信位；整段包 `try/catch (Throwable)` + `ModuleLog`，绝不捕获后重试（AGENTS.md 纪律 1 与「不要做的事」）。
- [ ] Step 6: 每个 Step 后都跑 `./gradlew test assembleDebug`，失败不进真机。

### Task 5: 结论回写与文档同步

- [ ] Step 1: 可行性分析报告**新增 §19「点按劫持防护与覆盖层授信」**：写清 `dumpsys input` 的 `inputConfig` 差异、`ty=2006` 可授信先例（`AntiMistakeTouchView`）、SystemUI 持有 `ADD_TRUSTED_DISPLAY`/`INTERNAL_SYSTEM_WINDOW`、`FLAG_SECURE` 与劫持的关系、最终生效的那条通路，以及**未生效而被删除的尝试**。
- [ ] Step 2: 若确认了新的判读铁律（例如"MIUI 对无 `TRUSTED_OVERLAY` 的覆盖层做全局点按拦截，且会压 alpha"），一并写进 §19 与 AGENTS.md「已知待验证项」/铁律区。
- [ ] Step 3: 更新 §17（截图隐藏）中涉及 `FLAG_SECURE` 的表述——它已被移除，兜底描述要改准，别留与代码不符的文档。
- [ ] Step 4: AGENTS.md 加一条：新增覆盖层窗口时必须核对 `dumpsys input` 的 `TRUSTED_OVERLAY`，避免再次触发劫持拦截。

## 五、决策树（按序执行，逐级验证）

```
Task 2 删 FLAG_SECURE ──解决──▶ 结束（Task 5 回写）
        │仍拦截
        ▼
Task 3 收窄窗口 ────解决──▶ 结束
        │仍拦截
        ▼
Task 4 Step 1-3 反射 systemUiVisibility ──出现 TRUSTED_OVERLAY 且解决──▶ 结束
        │标志没出现 或 仍拦截
        ▼
Task 4 Step 4 换 type=2000（须先验层带 > 191000）──解决──▶ 结束
        │仍拦截 / 层带不满足
        ▼
Task 4 Step 5 Hook WMS（需报备 + 扩作用域）
```

## 六、验收矩阵（真机，由用户执行；每步都要跑）

| # | 项目 | 期望 |
|---|---|---|
| 1 | 任意位置点按（桌面 / 微信 / 浏览器 / 状态栏 / 下拉） | **无「点按劫持防护」提示，点击全部生效** |
| 2 | `dumpsys input` 环窗口 | `inputConfig` 含 `TRUSTED_OVERLAY`（走 Task 4 时）；`frame` 与设置尺寸一致 |
| 3 | `dumpsys window` `mBaseLayer` | 恒为 231000（除后备 2 启用并重测）——环仍压灵动岛 |
| 4 | 环完整性 | 最大上下左右偏移下半环不被窗口裁切；挖孔外沿完整 |
| 5 | 截图隐藏 | 三指/指关节截图内**无环**，屏幕上有环（§17 六项清单复跑） |
| 6 | 下拉收起 | 仅在「下拉收起环」功能已落地时验证（该功能目前仍是未实现计划）：面板拉起时环跟随收缩、收起弹回；未落地则跳过此行 |
| 7 | 灵动岛共存 | 岛播放/充电时环不被盖、岛不被环挡交互 |
| 8 | 日志 | SystemUI 无新增异常栈；`ModuleLog` 无重试风暴 |
