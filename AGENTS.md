# AGENTS.md

本文件为在本仓库工作的 AI 编码代理提供项目上下文与约定。人工开发者同样可以阅读。

## 项目是什么

HolePowerRing（挖孔电量环）：一个面向 **HyperOS / MIUI** 的 **LSPosed(Xposed) 模块**。它在摄像头挖孔外圈绘制环形电量，并复用系统原生路径隐藏状态栏电池图标。

这是**开发阶段原型**，只针对逆向分析所用的那个 HyperOS 版本做过适配，未经多机型验证。改动时以"不拖垮 SystemUI 进程"为最高优先级。

## 仓库结构

- `HolePowerRing/` — Gradle 工程（Kotlin + Compose）
  - `app/` — 模块本体，源码在 `app/src/main/java/com/powerring/hole/`
    - `ModuleEntry.kt` / `assets/xposed_init` — Hook 入口
    - `hook/` — 各 Hook 点（`SystemUiHooks.kt` 是安装总入口）
    - `ring/` — 环几何、渲染、状态、配色、独立窗口控制
    - `data/BatteryObserver.kt` — 电量采集
    - `core/` — `HookPrefs.kt`（跨进程配置读取）、`ModuleLog.kt`
    - `ui/` — miuix 设置页
  - `xposedstub/` — Xposed API 编译桩，**仅 `compileOnly`，绝不打进 APK**
- `apks/` — 逆向用的目标 APK（SystemUI、系统界面组件插件），**不要修改或删除**
- `work/` — Python DEX 静态分析脚本（`dexlib.py`、`dump_class.py`、`find_classes.py`、`find_strings.py`、`method_strings.py`、`xref.py`、`method_refs.py`）与产物 `sysui/`、`plugin/`
- `挖孔环形电量LSP模块-可行性分析报告.md` — 逆向结论与技术方案，**Hook 点选型前先读它**
- `lsposed-dev-guide.md` — LSPosed 开发参考

## 构建与验证

```bash
cd HolePowerRing
./gradlew assembleDebug        # 产物 app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug         # 需已连接设备
```

工具链：JDK 17+（Gradle 可通过 foojay 自动下载 JDK 21）、Android SDK 37、`minSdk = 34`。Windows 下用 `gradlew.bat`。

### 发布（GitHub Actions）

`.github/workflows/release.yml`：**推送 `vX.Y.Z` 形式的 tag 即自动发版**——跑 `./gradlew test`（纯数据层 JVM 单测）+ `assembleRelease`，创建 GitHub Release 并挂上 APK 与 SHA256SUMS。手动触发发版、PR 只校验的分支行为，以及密钥配置，见 [docs/release-ci.md](docs/release-ci.md)。

因此**发版不需要改 `build.gradle.kts` 里的版本号**——CI 从 tag 解析 `versionName`，按 `major×1000000 + minor×1000 + patch` 算出 `versionCode`，用 `-PversionName=` / `-PversionCode=` 注入。默认值仍写在 `app/build.gradle.kts`，本地构建行为不变。

签名约定：release 包不签名无法安装。密钥以 `RELEASE_*` 环境变量传入（CI 来自 GitHub Secrets），**缺失时回落 debug 签名并打印警告而不是直接失败**——新增签名相关配置时保持这个回落行为。

仓库**没有真机自动化测试**，验证仍需真机：安装 APK → LSPosed 管理器启用模块 → 重启 SystemUI（`adb shell killall com.android.systemui`）→ 观察日志与界面。配置改动必须重启 SystemUI 才生效（配置在 SystemUI 进程内只读加载）。

> **本机（25102RKBEC）实测的重启困境**：`adb shell killall com.android.systemui`、`am force-stop`、`adb shell kill <pid>` **三种方式都被 SELinux 拒**（`Operation not permitted` / 无效果，SystemUI 以普通应用 uid 运行）。`adb install -r <apk>` **有时会**顺带换出新进程、但**不保证**——2026-10-03 一轮里前两次装包都换了 pid，第三次没换，于是"验证通过"其实验的是上一版代码。
>
> **因此每次改完代码必须自己确认新代码真的在跑**，别信"我装过了"：
> 1. `adb shell pidof com.android.systemui` 与安装前比对，pid 没变 = 没重载；
> 2. `logcat -s HolePowerRing` 里应能看到本轮**新加的那行日志**（看不到就是旧进程）；
> 3. pid 没变时只能重启设备（`adb reboot`，用户主力机，**须先征得同意**）或让用户手动重启。
> 4. 同一轮里 `dumpsys package com.powerring.hole | grep lastUpdateTime` 与产物时间戳也要对上——本项目已因"装了旧包"和"装了没重启"各白跑一轮验证。

## 必须遵守的约定

1. **Hook 全部要兜底捕获异常。** 任何 `beforeHookedMethod` / `afterHookedMethod` 内的逻辑，包括反射、Class.forName、类型转换，都必须包 `try/catch (Throwable)` 并用 `ModuleLog` 记录，绝不能让异常逃逸到 SystemUI。这是既有代码的统一模式，新增 Hook 请照做。
2. **作用域固定为 `com.android.systemui`**（见 `res/values/arrays.xml` 的 `module_scope`）。SystemUI 是"宿主 + 插件"结构：状态栏与挖孔覆盖层在宿主，灵动岛在运行时由宿主 ClassLoader 加载的 `miui.systemui.plugin`。跨这个边界找类要通过对应变量的 ClassLoader，不要用错误的 loader。
3. **优先走系统原生路径，不要暴力拦截。** 隐藏电池图标用的是 `MiuiStatusBatteryContainer.setIsHideBattery(true)`（灵动岛自己用的同一机制），布局重算与隐私圆点避让由系统完成。不要改成 `setVisibility` 之类的硬拦截——副作用大。
4. **Xposed API 只从 `xposedstub` 编译期引入。** 不要为了"方便"把真实 Xposed 依赖改成 `implementation`，那会让 APK 与 LSPosed 运行时冲突。
5. **跨进程配置走 SharedPreferences。** 设置页写、Hook 侧读（`core/HookPrefs.kt`）。不要引入新的进程间通信方式。
6. **Gradle DSL**：沿用现有 `build.gradle.kts` 的 AGP 9.x 新写法（`compileSdk { version = release(37) { ... } }`），不要退回旧式 `compileSdk = 37`。Compose 编译器用 JetBrains 插件（AGP 9 已内置 Kotlin 支持，不要再加 `org.jetbrains.kotlin.android`）。
7. UI 组件优先用 miuix（HyperOS 风格 Compose 库），与现有设置页保持一致，不要混入 Material3 组件风格。
8. **绝不在 `ClassLoader.loadClass` 的回调里做反射枚举或安装 Hook。** 在类加载临界区内调 `cls.declaredMethods`（强制解析签名 → 触发二次类加载）或 `findAndHookMethod`（触发 ART deoptimize / suspend-all）会**死锁 SystemUI，导致启动期 ANR、手机界面卡死**——2026-10-01 已实际发生并复盘（可行性分析报告 §12.2）。
   - 需要灵动岛等**插件侧**信息时，**优先找宿主侧的等价信号**（灵动岛显隐就用宿主侧 `MiuiBatteryMeterView.updateIslandShowing`，见 §12.3）；
   - 宿主侧确实没有、必须走 loadClass 时，回调内只做字符串比较，把重活 `post` 到主线程队列后再执行，绝不内联；
   - 顺带铁律：**同层带内不要用 `dumpsys window windows` 的 `Window #N` 判断叠加顺序**（同带内它与实际合成顺序相反），要用 `dumpsys SurfaceFlinger --layers` 的 `Output Layer` 数组。
9. **新增任何常驻覆盖层窗口，必须确认它被标为受信覆盖层（`TRUSTED_OVERLAY`）。** 否则会被 MIUI 的点按劫持防护**全局**拦截点击（不只拦窗口那一片），现象是"任意位置点按都弹提示"，很难归因到具体窗口。
   - 判据用 `adb shell dumpsys input | grep <窗口名>` 看 `inputConfig=`，**不要**先猜 `FLAG_NOT_TOUCHABLE`（穿透窗口照样被计入劫持判定）；`dumpsys window windows` 里整行没有 `pfl=` 就等于不受信。
   - 设置方式：`addView` 前反射调用框架自带的 `WindowManager.LayoutParams.setTrustedOverlay()`。本机常量名是 `PRIVATE_FLAG_TRUSTED_OVERLAY = 0x20000000`，**不是** AOSP 新版的 `SYSTEM_FLAG_TRUSTED_OVERLAY`（后者在本机 NoSuchFieldException）。详见可行性分析报告 §19。
10. **判断"是否有动画在飞"，只要该动画可能带 `startDelay`，就不能只看 `ValueAnimator.isRunning()`。** 它在整个 `startDelay` 期间返回 false（要等延迟结束进 `startAnimation()` 才置真），于是延迟窗口里会被误判成"没在跑"：看门狗一类的安全网会直接把 `collapseProgress` 硬刷到目标值，随后延迟到点的动画第一帧又把进度写回去；而 `if (it.isRunning) it.cancel()` 这类跳过式取消，会把还在延迟里的旧动画漏成**孤儿动画**，让它反过来覆盖新动画的进度。本项目已因此产生"岛回场时环闪一下才正常"的概率性视觉 bug（真机日志：请求回场后 191ms 就被看门狗硬刷，而延迟是 220ms）。正确写法：取消一律无条件 `cancel()` 并清引用，在途判定用 `isRunning || isStarted`。详见可行性分析报告 §20.4。
11. **外部 PR 显示 `MERGEABLE` / 干净快进，不等于合并结果可用。** rebase 式的手工合并能产生语法合法、构建通过但行为错误的代码：本项目 PR #1 的 rebase 把两个 Hook 的 `install` 整段嵌进了上一个 Hook 的 `catch` 体内（正常启动路径根本不安装，且**不报编译错**），还留下重复调用与重复 `when` 分支。合并外部 PR 后必须：① 通读 `SystemUiHooks.install()` 的**括号配对**；② 真机确认每个 Hook 的"已挂载"日志出现在**正常启动路径**上；③ 跑 `./gradlew test`；④ 核对 `isShrinkResources` 等构建开关有没有被 rebase 悄悄丢掉。详见可行性分析报告 §20.2。

## 逆向与分析工作方式

对目标 APK 取证时用 `work/` 下的脚本，而不是重新造轮子：

```bash
cd work
python find_classes.py      # 按名称模式检索类
python dump_class.py        # 类/方法/字段转储
python find_strings.py      # 字符串常量检索
python method_strings.py    # 方法级字符串交叉
python xref.py              # 交叉引用分析
```

产物落在 `work/sysui/` 和 `work/plugin/`。**结论变更要同步回可行性分析报告文档**：类名、方法签名、风险项都记录在那里，而不是只留在代码注释或提交信息里。

## 已知待验证项（别当成已解决）

- 挖孔覆盖层窗口是否有足够边距绘制外环，需真机动态验证
- **状态栏电池管线：已确认（2026-10-01）走传统 MIUI View 管线**（原为待验证项）。真机日志中 `MiuiStatusBatteryContainer` 实际实例化，视图链为 `MiuiStatusBatteryContainer <- MiuiNotificationStatusContainer <- ... <- ComposeView <- StatusBarWindowView`，`setIsHideBattery` 原生路径生效
- 仅适配系统界面 17.03.260226.r / 插件 18.2.2.2.0；其他版本类名与方法签名可能不同，反射处要做好找不到类时的降级
- 沉浸收起检测已在 25102RKBEC 验证通过，但**信号源与最初设计不同**：环窗口（type=2009）不派发 `statusBars` insets（`statusTop` 恒为 0），实际改用 `StatusBarWindowStateController$commandQueueCallback$1.setWindowState(III)`。该项版本敏感，换机型须先用 `work/dump_class.py` 核对类名与方法签名（详见可行性分析报告 §10）
- **窗口层级已实测（2026-10-01）**：环与灵动岛窗口同为 `type=2009` / `mBaseLayer=191000`，同层带内**后创建的 surface 叠在上**。环在 `Application.onCreate` 加窗口、岛在插件协程里加，必然更晚 ⇒ **岛会盖住环**（A/B 截图已证）。修复：环窗口抬到 `type=2006`（本机层带 231000 > 191000）。
  - **判读铁律：同层带内禁止用 `dumpsys window windows` 的 `Window #N` 判断叠加顺序——同带内它与实际合成顺序相反**；必须用 `dumpsys SurfaceFlinger --layers` 的 `Output Layer` 数组（自底向上打印）。本项目已在这上面栽过一次（早期误判"环在上"）。
  - 详见可行性分析报告 §11 与 `docs/superpowers/plans/2026-10-01-ring-window-layer-priority.md`。**层带表是本机实测值不是 ROM 契约**，换机型必须先用 `dumpsys window windows | grep mBaseLayer` 重测
- **电池图标取色链路：已确认（2026-10-01）**。`MiuiBatteryMeterIconView.onDarkChangeInternal()` 是系统给电池图标上色的唯一位置，`mLightColor`/`mDarkColor`/`mDarkIntensity` + 四个 `mBattery*Color` 字段可直接反射读取；反色动画时钟在 `LightBarTransitionsController.animateIconTint`。**未覆盖**镂空样式 `MiuiHollowBatteryMeterIconView`。
  - ⚠️ **该链路在可行性分析报告中的章节尚未补写**（原引用写作"§12"，但 §12 现已被「灵动岛显隐信号与 ANR 事故」占用，见下条）。补写时请用 **§13**，结论目前只暂存在本条。
- **自定义配色四模式（2026-10-02）**：`color_mode` 四值互斥（0 跟随系统 / 1 固定单色 / 2 按电池状态 5 路 / 3 按电量区间），代码与 14 条 JVM 单测已通过，**真机验收尚未执行**（六项清单见可行性分析报告 §15 末尾）。契约、存储格式与 `use_custom_color` 遗留推导都在 §15。计划：`docs/superpowers/plans/2026-10-02-ring-custom-color-modes.md`
- **截图时隐藏电量环：已确认（2026-10-03，真机验证通过）**。设置键 `hide_on_screenshot`（默认开，热生效）。本机真实截图引擎是独立进程 `com.miui.screenshot`（SystemUI 内 AOSP 管线休眠），FLAG_SECURE 会被特权截图绕过（`fl=` 含 SECURE 仍被采到）；主效机制为反射 `Transaction.setSkipScreenshot`（SF 层硬排除，录屏/投屏同样覆盖），灭屏/旋转后由 `RingState.onCutoutFrame` 每帧按 layer 身份自愈。`captureDisplay` Hook 保留给走 AOSP 管线的 ROM。三层机制与迭代证据见可行性分析报告 §17。**注**：环窗口现已**不带** `FLAG_SECURE`（2026-10-03 移除，它对本机无用；原 `applyScreenshotHide`/`onConfigApplied` 通路一并删除，开关只剩 SF 层调用），别把它当截图隐藏的一环再加回去，原因见 §19。换机型须先确认真实截图引擎（报告 §17.1 方法）
- **下拉通知栏/控制中心收起电量环：已确认（2026-10-03 原实现，2026-10-04 合并 PR #1 后复验通过）**。设置键已改为 `hide_in_shade`（通知中心）与 `hide_in_control_center`（控制中心）**两个独立开关，默认开**，热生效；原 `collapse_on_shade`（默认关）随合并被删除，旧勾选不迁移。本机通知面板与控制中心是**两套独立管线**，信号源不变：通知走 `NotificationShadeWindowControllerImpl.onShadeOrQsExpanded(java.lang.Boolean)`（名字带 "Qs" 却**不覆盖控制中心**），控制中心走 `ControlCenterExpandControllerDelegate.onVisibleChanged(boolean)`（成对派发）。两路各驱各的 `RingState.setShadeExpanded(Boolean)` / `setControlCenterShowing(Boolean)`，**不再有中间 fraction 合并与开关门控**（setter 自身按值去重），收起与否由 `collapseTarget()` 里「状态 && 对应开关」决定；动画 300ms（原写 260ms）。面板展开时经 `onShadeExpandedChanged` 把原生电池图标交还系统。行为仍是布尔（展开即收、收起即弹回），非跟随手指——`onExpansionChanged(float)` 能给 fraction 但收起回落两轮未证实、有卡死风险，弃用；PR 侧曾用 `ShadeControllerImpl` + 反射 `NotificationPanelViewController.mExpandedFraction` 驱动，与弃用的 fraction 路线同源且未经真机验证，合并时已删。`ShadeExpansionStateManager.onPanelExpansionChanged(FZZ)` 与 `NotificationShadeWrapper.onPanelExpanded(Z)` 真机证伪后删除。换机型须先分别确认通知与控制中心的真实展开信号是否同路。见可行性分析报告 §18.3、§20.1
- **点按劫持防护全局拦截：已确认（2026-10-03，真机验证通过）**。开环后任意位置点按被拦，根因是环窗口是全列表里唯一「`frame` 在屏幕内却没有 `TRUSTED_OVERLAY`」的窗口。修复：`RingWindowController.applyTrustedOverlayFlag` 在 `addView` 前反射调用框架自带的 `LayoutParams.setTrustedOverlay()`（旧 ROM 回落 `privateFlags |= 0x20000000`），**无需改窗口类型**，§11 层带结论原样保住。两条已证伪的假设不要再查：`FLAG_NOT_TOUCHABLE` 挡不住劫持判定；`FLAG_SECURE` 不是成因（去掉后拦截照旧，它只因 §17.2 的截图原因被移除）。注意本机常量名是 `PRIVATE_FLAG_TRUSTED_OVERLAY`，AOSP 新版的 `SYSTEM_FLAG_TRUSTED_OVERLAY` 在本机不存在（实测 NoSuchFieldException）。换机型须先用 `dumpsys input` 核对 `inputConfig`。见可行性分析报告 §19
- **PR #1（v1.3.3）已合并（2026-10-04，真机验证通过）**。带入音乐律动、消息提醒呼吸/闪烁、点击挖孔显电量、锁屏隐藏圆环、百分比数字节点，约 40 个新配置键；人脸解锁（`FaceUnlockHook`）由作者自行删净。**遗留三项**：① PR 新增配置键**零单测**，CI 的 `./gradlew test` 只跑既有测试；② `RECORD_AUDIO` + `microphone` 前台服务是模块首次申请敏感权限，README 与隐私说明尚未同步；③ 音乐律动与提醒闪烁的**主通道需用户授权**（通知使用权 / 麦克风运行时权限），未授权会退回反射兜底通道，而反射通道"posted 加、removed 减"，漏一次 removed 就永久卡在"有通知"状态。合并细节与两处硬伤见可行性分析报告 §20

## 不要做的事

- 不改 `apks/` 内容，不提交新的 APK
- 不动 `local.properties`、`app/build/`、`work/__pycache__/`（已在 `.gitignore` 中）
- 不为了让 Hook "更稳"而加宽的异常捕获语义——捕获后降级即可，不要静默重试或吞掉关键错误信息
- 本项目仅供学习与研究，不用于绕过设备安全机制或生产环境部署
