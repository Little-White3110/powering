# 挖孔环形电量显示 LSPosed 模块 — 可行性分析报告

> 分析日期：2026-09-15
> 分析方式：对两个目标 APK 进行静态逆向（DEX 类/方法/字段/字符串常量分析、二进制资源解析）
> 目标功能：① 在摄像头挖孔（punch-hole）外圈绘制环形电量；② 隐藏状态栏原有电池图标；③（可选）与充电灵动岛动画联动
> 结论速览：**技术上可行，推荐以 `com.android.systemui` 为唯一作用域，Hook 挖孔装饰覆盖层绘制环 + Hook 电池容器强制隐藏，电量数据直接复用 SystemUI 内部的 BatteryController 回调。主要不确定性集中在"挖孔覆盖层窗口是否有足够边距绘制外环"和"本机实际走传统 View 还是 Compose 电池管线"，均需在真机上用一轮动态验证闭环。**

---

## 1. 分析对象

| 项目 | APK A：系统界面 | APK B：系统界面组件（插件） |
|---|---|---|
| 包名 | `com.android.systemui` | `miui.systemui.plugin` |
| 版本 | 17.03.260226.r（versionCode 202602260） | 18.2.2.2.0（versionCode 182020200） |
| compileSdk / minSdk | 37 / 37 | 36 / 34 |
| 角色 | SystemUI 宿主：状态栏、ScreenDecorations（挖孔/圆角覆盖层）、电池控制器、充电岛事件源 | MIUI SystemUI 插件：灵动岛全部 View/动画实现，运行时被宿主加载 |
| dex 数量 | 3 个（约 33 MB） | 3 个（约 16.8 MB） |
| 加固/混淆 | 无加固；R8 混淆（内部类名被混淆，关键公开类名保留） | 无加固；R8 混淆，**资源条目名被混淆**（res 下为短随机名） |

**关键架构判断：HyperOS 的 SystemUI 是"宿主 + 插件"结构。** 灵动岛界面（`miui.systemui.dynamicisland.*`）位于插件 APK，但插件**不运行在独立进程**，而是由宿主的插件管理器用独立 ClassLoader 加载进 `com.android.systemui` 进程实例化。这决定了 LSPosed 的作用域策略（见第 5 节）。

插件加载链证据：

- 宿主：`com.android.systemui.shared.plugins.PluginManagerImpl` → `PluginActionManager.loadPluginComponent(ComponentName)` → `PluginInstance.loadPlugin()`（持有 `hostContext`、`pluginData`、`packageName`）。
- 宿主与插件之间通过 `com.android.systemui.plugins.miui.*` 接口通信，如 `NotificationDynamicIslandPlugin`、`DynamicIslandContent`。

---

## 2. 需求拆解与总体思路

```
┌─────────────────────────────── com.android.systemui 进程 ───────────────────────────────┐
│                                                                                          │
│  状态栏窗口                    挖孔装饰覆盖层窗口                灵动岛（插件 ClassLoader） │
│  ┌──────────────────┐         ┌────────────────────┐           ┌──────────────────────┐  │
│  │ MiuiStatusBattery│  隐藏   │ ScreenDecorations  │  绘制环   │ DynamicIsland* 动画   │  │
│  │ Container       │ ──────▶ │  └ OverlayWindow   │ ◀──────── │ 充电岛显隐/坐标事件   │  │
│  │  └ MiuiBattery-  │         │     └ DisplayCutout│  联动     │ （可选，仅做动画配合）│  │
│  │    MeterView     │         │       BaseView     │           └──────────────────────┘  │
│  └──────────────────┘         └────────────────────┘                                      │
│             ▲                          ▲                                                  │
│             └──────────── BatteryController.addCallback ◀── 电量/充电/省电状态           │
└──────────────────────────────────────────────────────────────────────────────────────────┘
```

三件事相互独立，可以分阶段交付：

1. **隐藏电池图标**——Hook 电池容器，强制走系统已有的"隐藏"分支（系统本来就有此能力，灵动岛出现时就在用）。
2. **挖孔环绘制**——Hook 挖孔覆盖层 `DisplayCutoutBaseView`，在其 Canvas 上按真实挖孔几何画弧；或向覆盖层根 View 注入自定义 View。
3. **取电量数据**——直接复用 SystemUI 的 `BatteryController.BatteryStateChangeCallback`，无需自己监听系统广播。

充电灵动岛不是必需依赖，只作为动画联动与几何校准的增强项（第 4 节详述）。

---

## 3. 电池图标现状（隐藏目标）

### 3.1 状态栏里存在两套电池渲染实现

DEX 中同时存在新旧两套管线，这是本项目**必须真机确认的第一个点**：

| 管线 | 关键类 | 说明 |
|---|---|---|
| 传统 MIUI View（状态栏实际布局使用） | `com.android.systemui.statusbar.views.MiuiStatusBatteryContainer`（ViewGroup）<br>`MiuiBatteryMeterView`（LinearLayout）<br>`MiuiBatteryMeterIconView` / `MiuiHollowBatteryMeterIconView`（自绘电池图形）<br>`com.android.systemui.statusbar.views.BatteryIndicator`（id=`battery_indicator`，`res/layout/status_bar.xml` 第 44 行） | 经典 MIUI 电池视图，自绘图标、百分比、充电视图 |
| AOSP 新 Compose 管线 | `com.android.systemui.statusbar.pipeline.battery.**`（`BatteryRepositoryImpl`、`UnifiedBatteryViewBinder`、`BatteryFrame`、`BatteryGlyph`、`BatteryStatusChip`、`BatteryStatusEventComposeChip`） | Android 新状态栏管线，部分场景（QS 头部 `ShadeHeaderViewModel`、事件 chip）已确认使用；状态栏主图标是否已切换需真机 dump 视图层级确认 |

### 3.2 系统已内置"隐藏电池"能力——这是最稳的 Hook 点

`MiuiStatusBatteryContainer` 存在专门字段与方法：

```
boolean mIsHideBattery
void setIsHideBattery(java.lang.Boolean)   // 由状态驱动
```

其调用方经交叉引用确认：

- `MiuiBatteryMeterView.updateIslandChanged(boolean, boolean)` —— **灵动岛出现/变化时系统自己就会调它隐藏电池**；
- 一个 Compose/Kairos 状态映射（`BuildScopeKt$toState$1$1`）。

`MiuiBatteryMeterView` 中与灵动岛联动的完整状态字段（均为实例字段，可反射观测）：

```
mIsIslandShowing / mStoreIsIslandShowing
mIsAddBatteryIsland / mStoreIsAddBatteryIsland
mByIslandTrigger / mByIsAddBatteryIslandTrigger
mHasIslandOnce / mHasIslandInfoOper
void updateIslandShowing(boolean, boolean, boolean)
void updateIslandChanged(boolean, boolean)
void updateVisibility$6()      // 最终显隐收敛点
static void hideView(IFolme, View, String) / showView(...)
```

**结论：用户观察到的"插电时灵动岛动画期间电池图标消失"在代码中得到证实**：充电岛 add → `updateIslandChanged` → 容器 `setIsHideBattery(true)`。这证明隐藏电池图标是系统支持的常规操作，Hook 它不会引入未知副作用（隐私圆点 `mHomePrivacyContainer` 的布局联动也在同一容器内，需回归测试）。

### 3.3 隐藏方案（按优先级）

1. **首选**：Hook `MiuiStatusBatteryContainer.setIsHideBattery(Boolean)`，把参数恒改为 `true`（after  Hook 反射置字段或 before 改参均可）。语义最贴近系统原生路径，布局重算、隐私点避让都由系统完成。
2. 备选：Hook `MiuiBatteryMeterView.updateVisibility$6()`，after 中对该 View `setVisibility(GONE)`；或直接 Hook `setVisibility` 拦截。更暴力，且动画状态可能反复重设可见性，需要每次回调兜底。
3. 若真机确认主图标走 Compose 管线：传统 Hook View 无效，需要改 Hook `UnifiedBatteryViewBinder.bind(...)` 的可见性数据源，或 Hook `BatteryInteractor`/`BatteryRepositoryImpl` 的暴露状态（复杂度显著上升，列入风险）。
4. 不建议用"改设置项"方式：仅找到 `status_bar_show_battery_percent`（控制百分比文字），没有控制系统 MIUI 电池图标显隐的 Secure 设置。

---

## 4. 充电灵动岛链路（"突破口"评估）

### 4.1 事件链实证

充电岛不是动画装饰，而是走 MIUI"设备通知（DeviceNotification）"通道：

```
充电状态变化
 └─ DeviceNotificationListenerImpl（com.android.systemui.devicenotification.listener）
     ├─ startAnimationForChargeNumber(float, float, BatteryStatus, boolean)
     ├─ structModelForCharge(...) / structBundleForCharge(...)
     │     Bundle 中 package_name = "miui.systemui.plugin"，含 charger_mode /
     │     quick_charger_mode（"%dW"、"%dW MAX"）、duration 等
     ├─ chargeIslandShowing / normalChargeStarted / quickChargeStarted（状态位）
     ├─ onIslandStateChanged(boolean add, boolean isChargeKey)
     │     日志字符串："onIslandStateChanged: add="、"chargeIslandShowing changed to:"
     └─ removeChargeIslandRunnable（延时自动移除 → 电池图标恢复显示）
```

插件侧：`miui.systemui.dynamicisland.anim.DynamicIslandAnimationController` 维护 HIDDEN / SMALL / BIG / EXPANDED / TEMP_HIDDEN / MINI_WINDOW 等状态及全套状态间动画（约 40+ 个 `xxxToYyyAnimation`）。背景视图 `DynamicIslandBackgroundView`（FrameLayout）持有 `actualLeft/actualTop/actualWidth/actualHeight`，坐标由宿主经 action 回传：

- `ACTION_BACK_REQUEST_CUTOUT_Y` / `ACTION_BACK_REQUEST_CUTOUT_HEIGHT`（插件 `DynamicIslandConstants`），
- 宿主侧对应字符串 `onIslandViewChanged: setCutoutY=` / `setCutoutHeight`、`action_back_request_cutout_y/height`。

### 4.2 对本模块的价值判断

| 设想 | 评估 |
|---|---|
| 靠充电岛事件来隐藏电池 | ❌ 不适合常驻。它是事件触发 + `removeChargeIslandRunnable` 自动恢复，拔插/持续充电时机会漂；隐藏应走第 3.3 节的容器 Hook |
| 充电岛证明"系统允许电池隐藏" | ✅ 价值大。`updateIslandChanged → setIsHideBattery` 是官方维护的隐藏路径，直接复用最稳 |
| 复用岛的挖孔坐标 | ⚠️ 可选增强。岛的 `actualLeft/Top/Width/Height` 紧贴挖孔，可作为挖孔几何的第二来源/交叉校验，但这些类在插件 ClassLoader 中且经过混淆，维护成本高；首选仍用系统 `DisplayCutout` API |
| 与岛做动画联动 | ✅ 体验增强。Hook/监听岛显隐状态，岛展开（BIG/EXPANDED）时让环淡出，SMALL/HIDDEN 时淡入，避免环与岛胶囊重叠。宿主侧 `IslandMonitor.OnIslandStatusChangedListener` / `StatusBarIslandControllerImpl` 提供了状态监听接口 |

**结论：充电岛是"隐藏可行性的证据"和"动画联动的抓手"，但不是环形电量功能的依赖项。功能主干不依赖它，可独立成立。**

---

## 5. LSPosed 落地方案

### 5.1 作用域（scope.list）

```
com.android.systemui
```

- 电池视图、BatteryController、ScreenDecorations 挖孔覆盖层、充电岛事件源、插件管理器**全部在该进程**，一个作用域覆盖全部需求。
- 不需要 `system` 虚拟作用域（不 Hook system_server；`BatteryService` 数据经 SystemUI 内的 controller 已可拿到）。
- **不能只勾 `miui.systemui.plugin`**：插件包没有独立进程，模块不会被加载进去。
- 若确实需要 Hook 插件中的类（仅动画联动增强才需要），在模块内 Hook 插件 ClassLoader 的获取时机：
  - 首选 Hook `com.android.systemui.shared.plugins.PluginInstance.loadPlugin()`（after 时从 `pluginData`/`hostContext` 创建出的插件 Context 拿 `getClassLoader()`）；
  - 兜底 Hook `dalvik.system.BaseDexClassLoader` 构造，按 apk 路径包含 `miui.systemui.plugin` 过滤，拿到 loader 后再加载 `miui.systemui.dynamicisland.*` 的类并 Hook；
  - 插件热重载（MIUI 更新插件后）需重新注册，建议在 Hook 点内做"一次性 + 重载重挂"保护。

API 选择：Modern Xposed API 102（参考工程内 [lsposed-dev-guide.md](file:///c:/Users/32732/Desktop/TRAE%20SOLO/powering/lsposed-dev-guide.md)）。异常模式用 `PROTECTIVE`——SystemUI 崩溃后果是系统界面反复重启，所有 Hook 回调内部必须 try/catch 全包并判空。

### 5.2 Hook 点清单（主干功能）

| # | 目标（均在 com.android.systemui） | 时机 | 用途 |
|---|---|---|---|
| H1 | `ScreenDecorations$DisplayCutoutView`（继承 `DisplayCutoutBaseView`）的 `onDraw(Canvas)` | after | 用其现成的 `cutoutPath` / `displayInfo` / `location[]` 在挖孔外圆画电量弧；`shouldDrawCutout` 为 false 时也要能画（不依赖 super 是否绘制） |
| H2 | `ScreenDecorations$DisplayCutoutView.onMeasure(int,int)` 或 `ScreenDecorations.getWindowLayoutParams(int)` | around | **条件触发**：仅当实测覆盖层窗口边距不足以容纳外环时，扩大其测量尺寸/窗口尺寸（见风险 R2） |
| H3 | `OverlayWindow.getView(int)` 或 `ScreenDecorations.setupDecorations()` | after | 备选注入方式：拿到 `OverlayWindow.rootView`（`RegionInterceptingFrameLayout`）addView 自定义环形 View，不直接改 onDraw |
| H4 | `MiuiStatusBatteryContainer.setIsHideBattery(Boolean)` | before 改参=true | 常驻隐藏原电池图标 |
| H5 | `MiuiBatteryMeterView.onBatteryLevelChanged(int, boolean, boolean)` / `onChargeStateChanged(boolean,boolean)` / `onPowerSaveChanged(boolean)` | after | 最轻量的电量数据源：参数即 `(level, plugged, charging)`；环 View 据此刷新 |
| H6（备选） | `BatteryControllerImpl.addCallback(BatteryController$BatteryStateChangeCallback)` | — | 若 H5 因视图未创建/被隐藏后不回调，改为自行向 controller 注册回调（`MiuiBatteryControllerImpl extends BatteryControllerImpl`，Dagger 单例，可从电池 View 的 `mBatteryController` 字段反射获取） |

### 5.3 环形 View 实现要点

- 绘制：`Canvas.drawArc(rectF, -90f, level * 3.6f, false, paint)`，`Paint` 开抗锯齿、`Style.STROKE`，线宽建议 2–3 dp（可配置）。
- 几何：以 `DisplayCutout` 挖孔 bounds 中心为圆心，半径 = 挖孔外接圆半径 + 线宽/2 + 1px 间隙；多挖孔/旋转屏遍历所有 bounds（`DisplayCutoutBaseView` 已处理旋转与物理像素比，`getDisplayRotation()`、`getPhysicalPixelDisplaySizeRatio()` 可复用）。
- 颜色（可直接沿用系统语义）：常规白色/深色模式反色（参考电池的 `mDarkColor/mLightColor` 与 `onDarkChanged`）；充电中绿/品牌色；低电红色；省电模式橙色（对应 `batteryPowerSaveColor` 等字段）。
- 动画：level 变化用 `ValueAnimator` 平滑；充电可加流动/呼吸光效（首版不做，先保功能）。
- 避让（首版就要有）：锁屏 AOD/常显、全屏视频/游戏（immersive）、灵动岛 BIG/EXPANDED 期间隐藏环，防烧屏与重叠。可利用的状态：`MiuiBatteryMeterView.mToAod/mAnimToAod`、`DynamicIslandContent` 岛状态、Keyguard 回调。
- 触控：覆盖层根布局本就是 `RegionInterceptingFrameLayout`（按 Region 拦截），自定义环 View 设 `setOnTouchListener` 吞掉或不处理即可，**不得扩大触控拦截区域**。
- 性能：无动画时电量不变不重绘（脏刷新），避免在 SystemUI 常驻动画耗电/烧屏。

### 5.4 隐藏与环的状态一致性

- 两者由同一份电量状态驱动（H5/H6），环在则图标隐藏；
- 环不可用的兜底（未获取到挖孔、非挖孔屏、解析失败）必须**自动放弃隐藏**，恢复原电池图标——Hook 回调中任何异常都走"放行原始行为"，这是防止"电量指示完全消失"的安全阀。
- 建议加模块配置开关（Remote Preferences），允许单独开关"隐藏图标"和"环形显示"。

---

## 6. 方案对比

| 方案 | 做法 | 优点 | 缺点 | 结论 |
|---|---|---|---|---|
| **A. Hook 挖孔覆盖层 onDraw** | H1 直接画 | 几何最准（系统 cutoutPath）、零新增窗口、层级天然在最底层装饰窗口、不影响状态栏布局 | 覆盖层窗口可能紧贴挖孔导致环被裁切（R2）；系统若不绘制则需强制画 | **首选** |
| B. 向 OverlayWindow.rootView 注入自定义 View | H3 addView | 绘制逻辑自主、好做动画 | 需自己算坐标/旋转，MIUI 混淆升级后 rootView 获取点易变 | 备选/与 A 互补 |
| C. 状态栏窗口内注入自定义 View | Hook 状态栏布局 inflate 完成（status_bar 根），按 `rootWindowInsets.displayCutout` 定位 | 窗口空间充足、无裁切问题，做百分比文字方便 | 要处理状态栏在各场景（锁屏/下拉/全屏）的可见性与位移，联动复杂 | 若 R2 验证失败则启用 |
| D. 独立悬浮窗（TYPE 层） | 模块自己加 WindowManager 视图 | 实现最简单、不依赖混淆类 | 层级/触摸/全屏避让全要自己管，最像"外挂"，易被状态栏/岛遮挡，功耗与稳定性差 | 不推荐，仅做原型验证 |
| E. 复用灵动岛 View | 在插件里挂环 | 与岛一体、坐标现成 | 跨 ClassLoader、插件混淆最重、岛隐藏时环也没了 | 仅适合做联动，不适合承载主功能 |

---

## 7. 风险与不确定性

| 编号 | 风险 | 影响 | 缓解 / 验证方式 |
|---|---|---|---|
| R1 | 状态栏主电池图标实际走 Compose 新管线（`pipeline.battery`），`MiuiBatteryMeterView` 在本机未被使用 | 隐藏 Hook 失效，环做出来但图标还在 | **真机第一步**：`adb shell dumpsys activity top`/布局检查或用 H5 打日志确认 `MiuiBatteryMeterView` 是否实例化；若否，转 Compose binder 侧 Hook |
| R2 | `DisplayCutoutBaseView` 所在覆盖层窗口仅按 cutout 紧边布局，外环被裁 | 方案 A 画不全 | 真机 dump 窗口：`adb shell dumpsys window | grep -i cutout`；必要时 H2 扩窗口/改用方案 C |
| R3 | 部分 ROM 配置不填充挖孔（`shouldDrawCutout=false`、覆盖层 View 不参与绘制） | onDraw 不触发 | Hook 中不依赖 super 绘制；或改 H3 主动 addView；`config_mainBuiltInDisplayCutout` 资源可辅助判断 |
| R4 | 机型适配：挖孔形状/数量/位置（中置/左置、药丸、双孔）、旋转 | 环位置错位 | 以 `DisplayCutout.getBoundingRectsAll()` 为准，不写死坐标；左置孔时环仍贴孔，需产品决策 |
| R5 | 系统 OTA 后类/方法变化（R8 混淆、版本 17.03↔18.2） | 模块失效/SystemUI 崩溃 | Hook 全部反射+名称探测，失败静默降级；关键短方法防内联（必要时 `deoptimize`，参考开发指南 6.5） |
| R6 | Hook 异常导致 SystemUI 反复重启 | 严重可用性事故 | PROTECTIVE 模式 + 回调全包裹 + 启动失败计数器（连续失败自动停用 Hook） |
| R7 | 烧屏（OLED 常亮环） | 硬件风险 | AOD/锁屏/长时间静止时隐藏或周期微位移；参考系统 `ACTION_AVOID_SCREEN_BURN_IN` 机制 |
| R8 | 环与充电岛/隐私圆点/状态栏图标视觉重叠 | 观感 | 接岛状态做淡入淡出；隐私点场景随电池容器一起避让 |
| R9 | LSPosed/Zygisk 对 Android 17（API 37）的兼容性 | 模块无法加载 | 开发前确认设备上 LSPosed 版本支持该系统版本；本分析基于 API 102 指南 |
| R10 | 插件热重载导致联动 Hook 丢失（若做增强功能） | 联动失效 | Hook `PluginInstance.loadPlugin` 重挂；主干不 Hook 插件则不受影响 |

---

## 8. 实施路线图建议

**阶段 0：真机动态侦察（0.5–1 天，决定后续分支）**
1. 设备装好 LSPosed，安装模块空壳，作用域勾 `com.android.systemui`。
2. Hook `MiuiBatteryMeterView` 构造函数与 `onBatteryLevelChanged`、Hook `DisplayCutoutBaseView.onDraw`，各打一行日志，重启 SystemUI（`adb shell pkill com.android.systemui` 或 `killall`）。
3. 确认四件事：电池 View 是否实例化（R1）、onDraw 是否触发及触发时机（R3）、覆盖层窗口 bounds 与挖孔 bounds 对比（R2）、`DisplayCutout` 真实几何（R4）。
4. 插拔充电器，抓 `DeviceNotificationListenerImpl.onIslandStateChanged` 与电池容器显隐日志，复现用户描述的现象。

**阶段 1：最小可用（1–2 天）**
- H4 隐藏电池（带异常回退开关）；
- 方案 A 画静态环：H1 after 中按挖孔 bounds 画灰色底环 + level 弧；
- H5 接电量/充电态。
- 验收：桌面、锁屏、下拉、横竖屏、充电/省电/低电五类状态。

**阶段 2：健壮性（1–2 天）**
- 颜色深浅色模式、AOD/全屏避让、动画平滑、防烧屏策略；
- Compose 管线兜底分支（若 R1 命中）；
- 模块配置页（开关、线宽、颜色、是否隐藏原图标）。

**阶段 3：灵动岛联动（可选，1–2 天）**
- 跨 ClassLoader Hook 插件岛状态或监听宿主 `IslandMonitor`，岛展开淡出环、回落淡入；
- 校准挖孔坐标。

---

## 9. 最终结论

1. **可行。** 三个核心子问题都有明确落点：隐藏电池复用系统自带的 `setIsHideBattery` 路径；环绘制有系统挖孔覆盖层（`DisplayCutoutBaseView`/`OverlayWindow`）这一精确载体；电量数据可直接挂在 `BatteryController` 回调上，无需自己造监听。
2. **用户提出的"充电灵动岛"突破口判断部分正确**：它实证了系统允许电池图标隐藏，并提供了动画联动通道，但不应作为常驻隐藏/环形显示的依赖。
3. **单一作用域 `com.android.systemui` 即可覆盖**；只有做岛联动时才需要处理插件 ClassLoader。
4. 工程量预估：主干功能 2–4 天可出可用版本，前提是阶段 0 的四个动态验证点通过；最大不确定性是 R1（Compose 管线）和 R2（窗口裁切），它们只影响"Hook 在哪一层"，不影响整体可行性。

---

## 附录 A：关键类索引（逆向实证）

**宿主 APK（com.android.systemui，17.03.260226.r）**

| 类 | 作用 |
|---|---|
| `com.android.systemui.statusbar.views.MiuiStatusBatteryContainer` | 电池+隐私点容器；`setIsHideBattery(Boolean)`、`mIsHideBattery` |
| `com.android.systemui.statusbar.views.MiuiBatteryMeterView` | MIUI 电池主 View；`onBatteryLevelChanged(IZZ)`、`updateIslandChanged(ZZ)`、`updateIslandShowing(ZZZ)`、`updateVisibility$6()`、岛状态字段组 |
| `com.android.systemui.statusbar.views.MiuiBatteryMeterIconView` / `MiuiHollowBatteryMeterIconView` | 自绘电池图形（Canvas/Bitmap 合成，含 level、充电、省电、性能模式配色） |
| `com.android.systemui.statusbar.views.BatteryIndicator` | status_bar.xml 中 id=`@id/battery_indicator`(0x7f0b0182) 的指示器 |
| `com.android.systemui.statusbar.policy.MiuiBatteryControllerImpl` → `BatteryControllerImpl` | 电量数据源；`addCallback(BatteryController$BatteryStateChangeCallback)` |
| `com.android.systemui.statusbar.pipeline.battery.**` | 新 Compose 电池管线（`BatteryRepositoryImpl`/`BatteryInteractor`/`UnifiedBatteryViewBinder`/`BatteryGlyph` 等） |
| `com.android.systemui.DisplayCutoutBaseView` | 挖孔装饰 View 基类：`cutoutPath`、`displayInfo`、`location[]`、`onDraw`、`updateCutout()`、`getDisplayRotation()`、`getPhysicalPixelDisplaySizeRatio()` |
| `com.android.systemui.ScreenDecorations$DisplayCutoutView` | 挖孔 View 实现：`setColor`、`boundsFromDirection`、`onMeasure`、`updateCutout` |
| `com.android.systemui.ScreenDecorations` | 覆盖层管理：`OverlayWindow[] mOverlays`、`getWindowLayoutParams(int)`、`setupDecorations()`、`mDisplayCutout` |
| `com.android.systemui.decor.OverlayWindow` | 覆盖层窗口：`rootView`(RegionInterceptingFrameLayout)、`getView(int)` |
| `com.miui.systemui.util.CutoutUtils` | `mCutoutPosition`、`updateCutoutPosition()` |
| `com.android.systemui.devicenotification.listener.DeviceNotificationListenerImpl` | 充电岛事件源：`startAnimationForChargeNumber`、`onIslandStateChanged(ZZ)`、`chargeIslandShowing`、`removeChargeIslandRunnable` |
| `com.android.systemui.statusbar.IslandMonitor`（含 Real/Fake/Notification/ControlCenter 四种监听器）、`OnIslandStatusChangedListener` | 灵动岛显隐状态监听 |
| `com.android.systemui.statusbar.notification.DynamicIslandController` / `DynamicIslandPluginController` / `DynamicIslandPluginHolder` | 灵动岛宿主侧控制 |
| `com.android.systemui.shared.plugins.PluginManagerImpl` / `PluginActionManager.loadPluginComponent` / `PluginInstance.loadPlugin` | 插件加载（拿插件 ClassLoader 的 Hook 点） |

**插件 APK（miui.systemui.plugin，18.2.2.2.0）**

| 类 | 作用 |
|---|---|
| `miui.systemui.dynamicisland.DynamicIslandBackgroundView` | 岛背景 View：`actualLeft/Top/Width/Height`、`onDraw`、`stokeWidth` |
| `miui.systemui.dynamicisland.anim.DynamicIslandAnimationController` / `...AnimationDelegate` | 岛状态机与全套转场动画 |
| `miui.systemui.dynamicisland.DynamicIslandConstants` | action/extra 常量（`ACTION_BACK_REQUEST_CUTOUT_Y/HEIGHT`、烧屏避让 action 等） |

## 附录 B：环境备注

- 逆向工具：aapt2（build-tools 36.0.0）+ 自研轻量 DEX 解析脚本（位于 `work/`：dexlib.py、find_classes.py、dump_class.py、xref.py、method_strings.py、find_strings.py），无加固，证据可复现。
- 当前无连接设备（`adb devices` 为空），阶段 0 动态验证待设备接入后执行。
- 模块开发规范参考：[lsposed-dev-guide.md](file:///c:/Users/32732/Desktop/TRAE%20SOLO/powering/lsposed-dev-guide.md)（Modern Xposed API 102、scope.list、热重载、deoptimize 等）。
