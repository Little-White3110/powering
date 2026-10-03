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

- **挖孔环形电量**：Hook SystemUI 挖孔装饰覆盖层（`DisplayCutoutBaseView`），在真实挖孔几何外圈绘制电量弧
- **隐藏原电池图标**：Hook `MiuiStatusBatteryContainer.setIsHideBattery(Boolean)`，复用系统内置的隐藏路径（灵动岛出现时系统使用的同一机制），布局重算与隐私圆点避让由系统完成
- **电量数据**：直接复用 SystemUI 内部 `BatteryController` 回调，无需自建广播监听
- **配置页**：基于 miuix（HyperOS 风格 Compose 组件）的设置界面，跨进程 SharedPreferences 下发配置

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

Hook 分工：

| 文件 | 职责 |
|---|---|
| `hook/SystemUiHooks.kt` | Hook 安装总入口 |
| `hook/CutoutRingHook.kt` | 挖孔覆盖层绘制电量环 |
| `hook/BatteryHideHook.kt` | 强制隐藏状态栏电池图标 |
| `data/BatteryObserver.kt` | 电量状态采集 |
| `ring/*` | 环几何计算（`CutoutGeometry`）、渲染（`RingRenderer`）、状态与配色 |

关键设计决策（详见可行性分析报告）：

- 隐藏电池图标走系统原生 `setIsHideBattery(true)` 路径，而非暴力 `setVisibility` 拦截，副作用最小
- 环绘制在挖孔覆盖层的 Canvas 上按真实挖孔几何画弧
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

密钥生成、Secrets 配置、本地复现与排错见 **[docs/release-ci.md](./docs/release-ci.md)**。

## 安装与使用

1. 设备需已 root 并安装 **LSPosed**（libxposed / 传统 Xposed API 均兼容，minVersion 82）
2. 安装 APK，在 LSPosed 管理器中启用模块（作用域已固定为系统界面，无需勾选）
3. 重启系统界面（LSPosed 管理器内操作或 `adb shell killall com.android.systemui`）
4. 打开「挖孔电量环」App 调整配置：总开关、隐藏原电池图标、电量动画、充电光效、环描边宽度

配置改动后需重启系统界面生效（配置在 SystemUI 进程内只读加载）。

## 已知限制与待验证项

- 「挖孔覆盖层窗口是否有足够边距绘制外环」需真机动态验证
- 本机状态栏电池实际走传统 MIUI View 管线还是新 Compose 管线，需真机 dump 视图层级确认（两条管线在逆向中均存在）
- 仅适配分析所用 HyperOS 版本（系统界面 17.03.260226.r / 插件 18.2.2.2.0），其他版本类名/方法签名可能不同

## 相关文档

- [挖孔环形电量LSP模块-可行性分析报告.md](./挖孔环形电量LSP模块-可行性分析报告.md) — 目标 APK 逆向分析、Hook 点选型、风险与验证计划
- [docs/release-ci.md](./docs/release-ci.md) — CI 发版流水线、发布密钥配置与本地复现
- [lsposed-dev-guide.md](./lsposed-dev-guide.md) — LSPosed 开发参考
- [LICENSE](./LICENSE) — MIT 许可证全文

## 免责声明

本项目仅供学习与研究。修改 SystemUI 行为存在一定风险（如界面异常），请自行承担使用风险，并保留可通过 LSPosed 禁用模块恢复原状的能力。

## 许可证

[MIT](./LICENSE)。

**适用范围**：本仓库中本项目自有的内容——`HolePowerRing/` 源码、`work/` 分析脚本、`docs/` 与各类 Markdown 文档。

**不适用**：`apks/` 目录下的小米 HyperOS 系统界面与系统界面组件插件 APK 为第三方专有软件，版权归小米所有，仅用于本地逆向分析，**不受 MIT 授权**，请勿再分发。`lsposed-dev-guide.md` 等外部资料同样遵循其原始来源的许可。
