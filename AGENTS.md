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

仓库**没有自动化测试**。验证只能靠真机：安装 APK → LSPosed 管理器启用模块 → 重启 SystemUI（`adb shell killall com.android.systemui`）→ 观察日志与界面。配置改动必须重启 SystemUI 才生效（配置在 SystemUI 进程内只读加载）。

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

## 不要做的事

- 不改 `apks/` 内容，不提交新的 APK
- 不动 `local.properties`、`app/build/`、`work/__pycache__/`（已在 `.gitignore` 中）
- 不为了让 Hook "更稳"而加宽的异常捕获语义——捕获后降级即可，不要静默重试或吞掉关键错误信息
- 本项目仅供学习与研究，不用于绕过设备安全机制或生产环境部署
