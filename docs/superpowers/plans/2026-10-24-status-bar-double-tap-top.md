# 状态栏双击回顶实施计划（2026-10-24）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增可选功能——开启后把系统「单击状态栏滚动到页面顶部」改为双击触发，双击间隔可调（默认 0.8s），消除与「点击挖孔显示电量」的误触冲突。

**Architecture:** 拦截系统手势监听器 `StatusBarLongPressGestureDetector$gestureDetector$1` 的 `onSingleTapConfirmed` / `onDoubleTapEvent`（配置开启时置 `result=false`），再旁观 `PhoneStatusBarView.onTouchEvent` 用自己的 `DoubleTapGate` 状态机判双击，命中后反射调 `StatusBarClickTool.invokeInputManager(true)` 走系统原生「双击」通路（守卫全保留）。

**Tech Stack:** Kotlin + Xposed（xposedstub compileOnly）+ JVM JUnit 单测 + miuix Compose 设置页。

---

## 逆向结论（本机 dex 证实，2026-10-24）

| 环节 | 类/方法 |
| --- | --- |
| 状态栏触摸入口 | `com.android.systemui.statusbar.phone.PhoneStatusBarView.onTouchEvent`（喂给 `mStatusBarLongPressGestureDetector` 字段持有的 `GestureDetector`） |
| 手势监听器 | `com.android.systemui.statusbar.gesture.StatusBarLongPressGestureDetector$gestureDetector$1`（`SimpleOnGestureListener`）实现 `onSingleTapConfirmed` / `onDoubleTapEvent` |
| 回顶派发 | `com.miui.systemui.statusbar.StatusBarClickTool.invokeInputManager(Z)`（classes3.dex，public final；`true` = 双击通路），内部 `clickHappen` 防抖、灵动岛显隐守卫、disable 守卫，经 `MiuiInputManager` 反射 scrollTop |
| 取实例路径 | `PhoneStatusBarView.mStatusBarLongPressGestureDetector`（public final）→ `.clickTool`（public final） |
| 证伪记录 | `mIsScrollToTopEnabled` / `ScrollToTopInteractor` / `wm.shell.scrolltotop` 在本机 dex 无实际回顶调用链（仅字符串/未解析类），不选作 Hook 点 |

## Tasks

### Task 1: 配置层

- [x] `RingConfig`：加 `statusBarDoubleTapTop`（默认 false）、`statusBarDoubleTapIntervalMs`（默认 800）、key `status_bar_double_tap_top` / `status_bar_double_tap_interval_ms`、范围常量 300–1500ms
- [x] `ConfigProvider.valueOf` + `ALL_KEYS` 补两个键
- [x] `HookPrefs.load()` 解析两个键
- 纯追加，无迁移：缺 key 回落 DEFAULT

### Task 2: DoubleTapGate（TDD）

- [x] 测试先行 `DoubleTapGateTest`（6 例：首击/成对/边界/超时重计/位移超限/三击不连发），先验证 FAIL 再实现
- [x] `hook/DoubleTapGate.kt`：纯 JVM 状态机，双击成立即消费

### Task 3: Hook 本体

- [x] `hook/StatusBarDoubleTapTopHook.kt`：`blockSystemTapToTop`（拦截两条回调）+ `detectDoubleTap`（旁观 `onTouchEvent`）+ `dispatchScrollToTop`（反射 `clickTool.invokeInputManager(true)`）
- [x] `SystemUiHooks.install` 在 `CutoutTapHook` 后接线，独立 try/catch

### Task 4: 设置页

- [x] `SettingsScreen` tapCard 内「电量节点」后加 `SwitchPreference`（状态栏双击回到顶部）+ `ConfigSlider`（双击间隔 0.3–1.5s，步进 0.1s，默认 0.8s）

### Task 5: 门禁与文档

- [x] `./gradlew.bat test assembleDebug` 全绿（52 tests，0 failures）
- [x] 本计划文档落盘
- [x] 可行性分析报告补 §21（触发链、守卫清单、版本敏感点、证伪记录）

### Task 6: 真机验收（人工）

- [ ] `installDebug` 后 `pidof com.android.systemui` 前后比对；pid 没变需重启设备（先征得同意）
- [ ] `logcat -s HolePowerRing` 确认 `StatusBarDoubleTapTopHook 系统单击回顶已接管` 与 `…双击探测已安装` 出现在正常启动路径
- [ ] 行为验收：开关关=原生行为；开关开=单击挖孔只显电量不回顶、间隔内双击回顶、超间隔不回顶、灵动岛显示时双击不回顶
- [ ] 间隔 0.3s / 1.5s 边界热生效验证；`lastUpdateTime` 与产物时间戳核对

## 边界与失败模式

| 场景 | 行为 |
| --- | --- |
| 找不到 `$gestureDetector$1`（换 ROM） | 拦截安装失败仅记日志，系统保持原生（降级方向安全） |
| 配置未加载 | 默认 false = 原生行为；配置到货热生效 |
| 反射 `clickTool` 失败 | 仅记日志，无异常逃逸 |
| 快速双击 <300ms | 拦截层吞掉系统 `onDoubleTapEvent`，统一由我们的判定在第二击 UP 派发；系统 `clickHappen` 防抖再兜一层 |
| 长按 / 点状态栏展开面板 | 走其他监听器（`createClickListener$gestureDetector$1` 等），未触碰 |
| 三击 | 双击消费后第三击按新首击计 |
