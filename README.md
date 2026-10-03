<p align="center">
  <img src="./HolePowerRing/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="128" alt="HolePowerRing 应用图标" />
</p>

<h1 align="center">HolePowerRing（挖孔电量环）</h1>

<p align="center">
  面向 HyperOS（MIUI）的 LSPosed 模块 · MIT 授权
</p>

一个面向 **HyperOS（MIUI）** 的 LSPosed 模块：在摄像头挖孔（punch-hole）外圈绘制环形电量，并隐藏状态栏原有电池图标。

> 当前为开发阶段的原型项目，仅针对目标设备上逆向确认的 SystemUI 版本做了适配，未经广泛机型验证。

## 功能

**环与电量**

- **挖孔环形电量**：在摄像头挖孔外圈绘制电量弧，按真实挖孔几何（`DisplayCutout`）算半径与中心，可缩放/偏移微调
- **隐藏原电池图标**：Hook `MiuiStatusBatteryContainer.setIsHideBattery(Boolean)`，复用系统内置的隐藏路径（灵动岛出现时系统使用的同一机制），布局重算与隐私圆点避让由系统完成
- **电量数据**：直接复用 SystemUI 内部 `BatteryController` 回调，无需自建广播监听
- **点击挖孔显示电量 / 百分比数字节点**：点一下挖孔短暂显示具体数字；可开启常显的节点刻度

**自动显隐联动**

- 状态栏沉浸时同步收缩隐藏；下拉**通知中心**与**控制中心**各自独立开关（收起时交还原生电池图标）
- 截图与录屏时从 SurfaceFlinger 层排除环（反射 `Transaction.setSkipScreenshot`）
- 锁屏隐藏圆环；灵动岛出现时避让；横屏无法贴合挖孔时自动恢复原生电池图标

**动效**

- **充电动画**：6 种形式（进度弧 / 光点 / 双点 / 彗星 / 脉冲 / 回声）× 3 种覆盖范围（智能 / 全环 / 空白段），颜色、速度、幅度可调
- **呼吸灯**：3 种形式，空闲时才呼吸可选
- **消息提醒**：新通知进场闪烁提示，支持常驻 / 限时两种模式，主副色可调
- **音乐律动**：7 种形式（呼吸 / 跑马 / 节拍 / 心跳 / 波浪 / 音量脉冲 / 人声·音乐），后两种需开启麦克风音频驱动
- **低电量特效**：低于阈值时"迪迦计时器"式红蓝交替

**配色与护眼**

- 环颜色四模式互斥：跟随系统电池取色 / 固定单色 / 按电池状态 5 路 / 按电量区间
- 防烧屏：亚像素级漂移 + 亮度上限；发光强度与底槽浓度可单独归零

**配置页**

- 基于 miuix（HyperOS 风格 Compose 组件）的设置界面，分「基础 / 动效 / 外观 / 关于」四个 Tab，60 余项配置
- 外观配置可 JSON 导入导出；内置「重启系统界面」按钮
- 配置经 ContentProvider 跨进程下发，改动多数即时生效（热更新）

## 仓库结构

```
powering/
├── .github/workflows/       # GitHub Actions：推 tag 自动打包 + 发布 Release
├── HolePowerRing/          # LSPosed 模块工程（Gradle / Kotlin / Compose）
│   ├── app/                # 模块主工程（Hook 逻辑 + 配置页）
│   └── xposedstub/         # Xposed API 编译桩（仅 compileOnly，不打入 APK）
├── apks/                   # 逆向分析用的目标 APK（HyperOS 系统界面 / 系统界面组件插件）
├── docs/                   # 专题文档（发布与 CI、方案计划、验证截图）
├── LICENSE                 # MIT 许可证（适用于本项目自有源码与文档）
├── work/                   # DEX 静态逆向工具脚本（Python）与分析产物
│   ├── dexlib.py           # DEX 解析基础库
│   ├── dump_class.py       # 类/方法/字段转储
│   ├── find_classes.py     # 按名称模式检索类
│   ├── find_strings.py     # 字符串常量检索
│   ├── method_strings.py   # 方法级字符串交叉
│   └── xref.py             # 交叉引用分析
└── 挖孔环形电量LSP模块-可行性分析报告.md   # 逆向分析与技术方案文档
```

## 实现原理

HyperOS 的 SystemUI 是**宿主 + 插件**结构：状态栏、挖孔覆盖层（`ScreenDecorations`）位于宿主 `com.android.systemui`，灵动岛位于运行时由宿主 ClassLoader 加载的插件 `miui.systemui.plugin`。模块作用域因此**固定为 `com.android.systemui`**（见 `res/values/arrays.xml` 的 `module_scope`）。

Hook 与渲染分工：

| 文件 | 职责 |
|---|---|
| `hook/SystemUiHooks.kt` | Hook 安装总入口（每个 Hook 独立 `try/catch` 降级） |
| `ring/RingWindowController.kt` | 环的载体：独立 `WindowManager` 窗口（type=2006，受信覆盖层） |
| `ring/CutoutGeometry.kt` / `RingRenderer.kt` / `PowerRingView.kt` | 环几何计算与 Canvas 渲染（含各类动效） |
| `ring/RingState.kt` | 显隐/收起状态机，所有信号收敛到同一条 `collapseProgress` 通道 |
| `hook/BatteryHideHook.kt` | 走原生路径隐藏状态栏电池图标 |
| `hook/BatteryColorHook.kt` | 反射读取系统给电池图标的实际取色（供「跟随系统」模式） |
| `hook/ImmersiveProbeHook.kt` / `IslandVisibilityHook.kt` / `ShadeCollapseHook.kt` | 沉浸、灵动岛、通知中心 / 控制中心的显隐信号源 |
| `hook/CutoutTapHook.kt` / `NotificationBlinkHook.kt` / `ScreenshotCaptureHook.kt` | 点按显电量、通知提醒兜底通道、截图管线捕获 |
| `core/HookPrefs.kt` + `ui/ConfigProvider.kt` | 跨进程配置读取（ContentProvider + 变更广播热更新） |
| `data/AudioReactiveService.kt` / `RingNotificationListener.kt` | 麦克风音频驱动、通知使用权（提醒主通道） |
| `ui/SettingsScreen.kt` | miuix 设置页 |

> `hook/CutoutRingHook.kt`（挖孔覆盖层 `onDraw`）与 `hook/StatusBarInjectHook.kt`（状态栏视图注入）是早期实验载体，**保留在代码库中但不启用**，避免多载体重影。

关键设计决策（详见可行性分析报告）：

- 隐藏电池图标走系统原生 `setIsHideBattery(true)` 路径，而非暴力 `setVisibility` 拦截，副作用最小
- 环用独立窗口承载而非宿主 View，层级实测严格高于灵动岛窗口，且不依赖窗口添加顺序
- 常驻覆盖层必须标为受信覆盖层（`TRUSTED_OVERLAY`），否则会被 MIUI 点按劫持防护**全局**拦截点击
- 所有 Hook 异常兜底捕获，保证不拖垮 SystemUI 进程

## 构建

要求：JDK 17+（Gradle 工具链支持自动下载 JDK 21），Android SDK 37。

```bash
cd HolePowerRing
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

## 发布（推 tag 自动发版）

[`.github/workflows/release.yml`](./.github/workflows/release.yml) 会在推送 `vX.Y.Z` 形式的 tag 时自动跑单测、打 `release` 包并创建 GitHub Release（APK + SHA256SUMS）。版本号直接从 tag 解析，无需改代码：

```bash
git tag v1.2.3
git push origin v1.2.3      # → 自动出包 + Release
```

`versionCode` 按 `major×1000000 + minor×1000 + patch` 计算（`v1.2.3` → 1002003）；也可在 Actions 页面手动填版本号发版。**发布前需先配置 4 个仓库 Secrets**（`RELEASE_KEYSTORE_BASE64` / `_PASSWORD` / `RELEASE_KEY_ALIAS` / `RELEASE_KEY_PASSWORD`），否则产物回落到 debug 签名。

`app/build.gradle.kts` 里的 `DEFAULT_VERSION_NAME` / `DEFAULT_VERSION_CODE` 只影响本地不带 `-P` 的构建（含设置页「关于」显示的版本号），惯例是跟最近一次发版保持一致。当前为 **1.1.0 / 1001000**。

密钥生成、Secrets 配置、本地复现与排错见 **[docs/release-ci.md](./docs/release-ci.md)**。

## 安装与使用

1. 设备需已 root 并安装 **LSPosed**（libxposed / 传统 Xposed API 均兼容，minVersion 82）
2. 安装 APK，在 LSPosed 管理器中启用模块（作用域已固定为系统界面，无需勾选）
3. 重启系统界面：设置页「关于」里的**重启系统界面**按钮，或在 LSPosed 管理器内操作
4. 打开「挖孔电量环」App 调整配置：总开关、隐藏原电池图标、显隐联动开关、各类动效与配色模式

多数配置改动**即时生效**（配置经 ContentProvider 下发并广播通知 SystemUI 刷新）；少数在 SystemUI 进程内只读加载的项仍需重启系统界面。

## 权限与隐私

模块**不申请任何网络权限**，所有数据只在设备内流转。以下敏感能力全部默认关闭，需用户在设置页主动开启并授权：

| 权限 | 用途 | 未开启 / 未授权时 |
|---|---|---|
| `RECORD_AUDIO` + 麦克风前台服务 | 「音乐律动 → 音频驱动」在模块自身进程采集音量/人声特征，广播给 SystemUI；前台服务带常驻通知明示 | 音频驱动的两种形式退化为呼吸，完全不采集音频 |
| 通知使用权（`NotificationListenerService`） | 消息提醒闪烁的**主通道**，感知通知进入与移除 | 退回 Hook 兜底通道（见「已知限制」） |
| `POST_NOTIFICATIONS` | 上述前台服务的常驻通知 | — |

## 已知限制与待验证项

- **仅适配分析所用 HyperOS 版本**（系统界面 17.03.260226.r / 插件 18.2.2.2.0）。窗口层带表、类名与方法签名都是本机实测值而非 ROM 契约，其他版本类名/签名可能不同，换机型须重测
- 消息提醒的**兜底通道**（Hook 反射）依赖配对的 removed 事件，漏一次会长期停在「有通知」状态；建议授予通知使用权走主通道
- 横屏时环无法贴合挖孔，默认自动恢复原生电池图标
- 仓库**没有真机自动化测试**：CI 只跑纯数据层 JVM 单测，动效与显隐行为须装机验收；v1.1.0 由 PR #1 带入的新配置键目前**尚无单测覆盖**

## 更新日志

### v1.1.0（2026-10-04）

相对 v0.1.0 的全部增量（主要由 PR #1 带入，约 40 个新配置键）：

- **显隐联动**：截图/录屏时从 SurfaceFlinger 层隐藏环；下拉**通知中心**与**控制中心**拆成两个独立开关；锁屏隐藏圆环
- **动效体系**：充电动画 6 形式 × 3 覆盖范围、呼吸灯 3 形式、消息提醒进场闪烁（常驻/限时）、音乐律动 7 形式（含麦克风音频驱动的音量脉冲与人声·音乐识别）、低电量"迪迦计时器"、百分比数字节点、点击挖孔显示电量
- **调节维度**：发光强度、底槽浓度、各动效的速度与幅度、防烧屏漂移与亮度上限
- **设置页**：扩为「基础 / 动效 / 外观 / 关于」四 Tab，新增外观配置 JSON 导入导出与「重启系统界面」按钮
- **修复**：受信覆盖层（`TRUSTED_OVERLAY`）修复开环后 MIUI 点按劫持防护全局拦截点击；回场动画与看门狗/孤儿动画冲突导致的"闪一下才正常"；合并 PR #1 时 rebase 造成的两处硬伤（Hook 安装段被嵌进上一个 `catch`、重复调用与重复分支）
- **工程**：R8 资源收缩、推 tag 自动发版的 Actions 流水线、MIT 许可证与文档体系

> **版本号口径**：PR #1 由贡献者侧以 v1.3.x 编号，代码注释中残留的 `v1.3.x` 指的是该 PR 内的功能批次，**不是本仓库的发布 tag**。本仓库发布线为 v0.1.0 → v1.1.0。

### v0.1.0（2026-10-02）

首个可安装原型：挖孔环形电量、原生路径隐藏电池图标、沉浸收起、灵动岛避让、横屏回退、自定义配色四模式、miuix 设置页、Actions 自动发版流水线。

## 相关文档

- [挖孔环形电量LSP模块-可行性分析报告.md](./挖孔环形电量LSP模块-可行性分析报告.md) — 目标 APK 逆向分析、Hook 点选型、风险与验证计划
- [docs/release-ci.md](./docs/release-ci.md) — CI 发版流水线、发布密钥配置与本地复现
- [lsposed-dev-guide.md](./lsposed-dev-guide.md) — LSPosed 开发参考
- [LICENSE](./LICENSE) — MIT 许可证全文

## 免责声明

本项目仅供学习与研究。修改 SystemUI 行为存在一定风险（如界面异常），请自行承担使用风险，并保留可通过 LSPosed 禁用模块恢复原状的能力。

## 👥 贡献者

感谢所有为 HolePowerRing 做出贡献的朋友：

<p align="center">
  <a href="https://github.com/acxmy"><img src="https://avatars.githubusercontent.com/u/318193740?v=4&s=80" width="80" height="80" alt="acxmy" title="acxmy" /></a>
  <a href="https://github.com/Little-White3110"><img src="https://avatars.githubusercontent.com/u/53994162?v=4&s=80" width="80" height="80" alt="Little-White3110" title="Little-White3110" /></a>
</p>

## 许可证

[MIT](./LICENSE)。

**适用范围**：本仓库中本项目自有的内容——`HolePowerRing/` 源码、`work/` 分析脚本、`docs/` 与各类 Markdown 文档。

**不适用**：`apks/` 目录下的小米 HyperOS 系统界面与系统界面组件插件 APK 为第三方专有软件，版权归小米所有，仅用于本地逆向分析，**不受 MIT 授权**，请勿再分发。`lsposed-dev-guide.md` 等外部资料同样遵循其原始来源的许可。
