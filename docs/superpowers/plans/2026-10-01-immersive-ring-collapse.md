# 沉浸式状态栏收起时环形电量向内收缩 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 当系统进入沉浸模式（看图片/视频时状态栏自动收起）时，环形电量以动画向内收缩并消失；状态栏恢复时弹回原尺寸。

**Architecture:** 复用环形电量独立窗口（type=2009）已有的 `OnApplyWindowInsetsListener`：状态栏收起时系统派发给该窗口的 `WindowInsets.Type.statusBars()` 顶部内边距归零，以此作为检测信号（零新增 Hook、不依赖任何 MIUI 私有类名）。收起状态写入 `RingState` 的全局进度值（0f 完整 / 1f 收起），用 `ValueAnimator` 平滑驱动，`RingRenderer` 按进度同步收缩半径并衰减透明度。若真机验证发现 insets 不随收起变化，启用兜底探针（Hook 状态栏窗口根 View 的 `setTranslationY`）。

**Tech Stack:** Kotlin、Xposed API（仅兜底任务）、android ValueAnimator / WindowInsets、miuix（设置页开关）。

**执行约定（用户既定偏好）：**
- 全程**不执行 git commit**，不做任何提交；验证以真机为准（仓库无自动化测试，见 AGENTS.md）。
- adb 只读命令（logcat）可直接执行；重启 SystemUI 优先用设置页内置的「重启系统界面」按钮，需要 adb 交互类操作时先征得用户同意。
- 所有 Hook/回调逻辑必须包 `try/catch (Throwable)` 并走 `ModuleLog`，绝不拖垮 SystemUI。

**关键现状（执行者必读）：**
- 当前生效载体是 `ring/RingWindowController.kt` 的独立窗口；`PowerRingView` 同时被窗口和（未启用的）状态栏注入方案使用，两者的 `onDraw` 都走 `RingState.onCutoutDraw`，本计划改动对两个载体同时生效，无需区分。
- `RingState` 是 SystemUI 进程单例，`levelAnimator` 已有"取消旧动画→新动画→invalidateAll"模式，本次收起动画照抄该模式。
- 配置管线是固定五处联动：`RingConfig`（数据类+KEY 常量）→ `core/HookPrefs.load()`（Hook 侧读）→ `ui/ConfigProvider`（跨进程游标列）→ `ui/PrefsStore.load()`（设置页读）→ `ui/SettingsScreen`（UI 开关）。加任何新配置项五处都要改，漏一处则该配置永远不生效。
- `RingState.onCutoutDraw` 中 `attachCutoutView(view)` 必须在一切提前 return 之前执行（收起期间若不再注册 View，动画反放时 `invalidateAll` 找不到重绘目标，环会永久卡在收起态）。下面的任务已保证顺序。

---

## 文件结构总览

| 文件 | 操作 | 职责 |
|---|---|---|
| `HolePowerRing/app/src/main/java/com/powerring/hole/ring/RingConfig.kt` | 修改 | 新增 `collapseOnImmersive` 配置项与 KEY |
| `HolePowerRing/app/src/main/java/com/powerring/hole/core/HookPrefs.kt` | 修改 | Hook 侧加载新 KEY |
| `HolePowerRing/app/src/main/java/com/powerring/hole/ui/ConfigProvider.kt` | 修改 | 配置 ContentProvider 新增列 |
| `HolePowerRing/app/src/main/java/com/powerring/hole/ui/PrefsStore.kt` | 修改 | 设置页侧加载新 KEY |
| `HolePowerRing/app/src/main/java/com/powerring/hole/ring/RingState.kt` | 修改 | 收起进度状态 + 动画调度 |
| `HolePowerRing/app/src/main/java/com/powerring/hole/ring/RingRenderer.kt` | 修改 | 按进度收缩半径、衰减透明度 |
| `HolePowerRing/app/src/main/java/com/powerring/hole/ring/RingWindowController.kt` | 修改 | insets 监听中检测状态栏收起 |
| `HolePowerRing/app/src/main/java/com/powerring/hole/ui/SettingsScreen.kt` | 修改 | 「开关」Tab 新增 SwitchPreference |
| `HolePowerRing/app/src/main/java/com/powerring/hole/hook/ImmersiveProbeHook.kt` | 条件创建（仅 Task 8） | 兜底沉浸探针 |
| `HolePowerRing/app/src/main/java/com/powerring/hole/hook/SystemUiHooks.kt` | 条件修改（仅 Task 8） | 注册兜底探针 |
| `挖孔环形电量LSP模块-可行性分析报告.md` | 修改 | 记录检测结论（AGENTS.md 要求） |

---

### Task 1: 配置项 collapseOnImmersive 全链路接入

**Files:**
- Modify: `HolePowerRing/app/src/main/java/com/powerring/hole/ring/RingConfig.kt`
- Modify: `HolePowerRing/app/src/main/java/com/powerring/hole/core/HookPrefs.kt:89-100`
- Modify: `HolePowerRing/app/src/main/java/com/powerring/hole/ui/ConfigProvider.kt:47-59,81-92`
- Modify: `HolePowerRing/app/src/main/java/com/powerring/hole/ui/PrefsStore.kt:24-35`

- [ ] **Step 1: RingConfig 增加字段与 KEY**

在 `data class RingConfig` 中 `chargingGlow` 字段之后（`strokeWidthDp` 之前）插入：

```kotlin
    /** 状态栏被系统自动收起（沉浸模式）时，环向内收缩并隐藏 */
    val collapseOnImmersive: Boolean = true,
```

在 companion object 的 KEY 常量区 `KEY_CHARGING_GLOW` 之后插入：

```kotlin
        const val KEY_COLLAPSE_ON_IMMERSIVE = "collapse_on_immersive"
```

- [ ] **Step 2: HookPrefs 加载新 KEY**

`core/HookPrefs.kt` 的 `load()` 中，`RingConfig(...)` 构造里 `chargingGlow = ...` 行之后插入：

```kotlin
            collapseOnImmersive = toBool(
                values[RingConfig.KEY_COLLAPSE_ON_IMMERSIVE], d.collapseOnImmersive,
            ),
```

- [ ] **Step 3: ConfigProvider 新增列**

`ui/ConfigProvider.kt`：

`valueOf()` 的 when 分支中 `KEY_CHARGING_GLOW` 行之后插入：

```kotlin
        RingConfig.KEY_COLLAPSE_ON_IMMERSIVE ->
            prefs.getBoolean(key, RingConfig.DEFAULT.collapseOnImmersive)
```

companion 的 `ALL_KEYS` 数组中 `RingConfig.KEY_CHARGING_GLOW,` 之后插入：

```kotlin
            RingConfig.KEY_COLLAPSE_ON_IMMERSIVE,
```

- [ ] **Step 4: PrefsStore 加载新 KEY**

`ui/PrefsStore.kt` 的 `load()` 中 `RingConfig(...)` 构造里 `chargingGlow = ...` 行之后插入：

```kotlin
            collapseOnImmersive = p.getBoolean(RingConfig.KEY_COLLAPSE_ON_IMMERSIVE, true),
```

- [ ] **Step 5: 编译验证**

```bash
cd HolePowerRing
./gradlew.bat assembleDebug
```

Expected: `BUILD SUCCESSFUL`（此步后 `RingState`/`SettingsScreen` 尚未使用新字段，允许字段暂时无人读取）。

---

### Task 2: RingState 收起进度与动画

**Files:**
- Modify: `HolePowerRing/app/src/main/java/com/powerring/hole/ring/RingState.kt`

- [ ] **Step 1: 新增收起状态与动画字段**

在「电池状态」区块的 `screenOn` 字段之后插入：

```kotlin
    // ---- 沉浸收起 ----

    /** 收起进度：0f 完整显示，1f 完全收缩不可见（渲染端按此收缩半径并衰减透明度） */
    @Volatile
    var collapseProgress: Float = 0f
        private set

    /** 最近一次系统上报的状态栏收起状态（沉浸模式） */
    @Volatile
    private var statusBarCollapsed: Boolean = false

    private var collapseAnimator: ValueAnimator? = null
```

在 companion 区域（object 内底部或紧邻 `levelInterpolator` 声明处均可，与现有风格一致放在 `levelInterpolator` 之后）：

```kotlin
    private val COLLAPSE_DURATION_MS = 260L
```

- [ ] **Step 2: 新增状态入口与动画调度**

在 `setScreenOn` 方法之后插入：

```kotlin
    /**
     * 状态栏是否被系统自动收起（沉浸模式）。
     * 由环窗口 insets 监听（或兜底探针）调用，二者均在 SystemUI 主线程。
     */
    fun setStatusBarCollapsed(collapsed: Boolean) {
        if (statusBarCollapsed == collapsed) return
        statusBarCollapsed = collapsed
        ModuleLog.i("状态栏沉浸收起状态: collapsed=$collapsed")
        animateCollapseTo(collapseTarget())
    }

    private fun collapseTarget(): Float =
        if (statusBarCollapsed && config.collapseOnImmersive) 1f else 0f

    /** 照抄 startLevelAnimation 的「取消旧动画→立即到位或平滑过渡」模式。 */
    private fun animateCollapseTo(target: Float) {
        val start = collapseProgress
        collapseAnimator?.let { if (it.isRunning) it.cancel() }
        if (!config.collapseOnImmersive || kotlin.math.abs(target - start) < 0.01f) {
            collapseProgress = target
            invalidateAll()
            return
        }
        collapseAnimator = ValueAnimator.ofFloat(start, target).apply {
            duration = COLLAPSE_DURATION_MS
            interpolator = levelInterpolator
            addUpdateListener {
                collapseProgress = it.animatedValue as Float
                invalidateAll()
            }
        }.also { it.start() }
    }
```

- [ ] **Step 3: 配置签名纳入新字段并联动收起**

`signature()` 改为（在 `levelAnim` 前加 `collapseOnImmersive`）：

```kotlin
    private fun RingConfig.signature() =
        "$ringEnabled|$strokeWidthDp|$offsetXDp|$offsetYDp|$scale|" +
            "$useCustomColor|$customColor|$chargingGlow|$levelAnim|$collapseOnImmersive"
```

`onCutoutDraw` 中配置变化补发帧的分支改为（用户切换开关时立即重评估收起目标，例如收起过程中关掉开关要弹回）：

```kotlin
        val sig = c.signature()
        if (sig != lastConfigSig) {
            lastConfigSig = sig
            animateCollapseTo(collapseTarget())
            invalidateAll()
        }
```

注意：`attachCutoutView(view)` 保持在方法第一行，任何提前 return 都不得越过它。

- [ ] **Step 4: 编译验证**

```bash
cd HolePowerRing
./gradlew.bat assembleDebug
```

Expected: `BUILD SUCCESSFUL`。

---

### Task 3: RingRenderer 向内收缩绘制

**Files:**
- Modify: `HolePowerRing/app/src/main/java/com/powerring/hole/ring/RingRenderer.kt:64-95`

- [ ] **Step 1: 计算展开系数并作用于半径与透明度**

将 `draw()` 中从 `val darkIcons = ...`（约 64 行）到 `val fraction = ...`（约 86 行）的片段替换为：

```kotlin
        val darkIcons = MiuixPalette.isDarkIconMode(state.tintColor)

        // 沉浸收缩：expand=1 完整显示，expand→0 半径向内收敛、透明度同步淡出
        val expand = 1f - state.collapseProgress.coerceIn(0f, 1f)
        if (expand <= 0.01f) return

        // 颜色选择：自定义色（覆盖一切）> 低电红 > 充电蓝 > 省电琥珀 > 跟随状态栏图标色
        val progressColor = scaleAlpha(when {
            config.useCustomColor -> config.customColor
            state.level <= LOW_BATTERY_THRESHOLD -> MiuixPalette.errorColor(darkIcons)
            state.charging -> MiuixPalette.primaryColor(darkIcons)
            state.powerSave -> MiuixPalette.POWER_SAVE_AMBER
            else -> MiuixPalette.foregroundColor(darkIcons)
        }, expand)
        // 自定义色时底槽也用该色的低透明度版本（alpha ~15%），视觉更统一
        val trackColor = scaleAlpha(if (config.useCustomColor) {
            (0x26 shl 24) or (progressColor and 0x00FFFFFF)
        } else {
            MiuixPalette.trackColor(darkIcons)
        }, expand)

        // 用户手动微调：缩放（半径）+ 上下左右偏移
        val baseRadius = (hole.holeRadius + gap + stroke / 2f) * config.scale
        val cx = hole.cx + config.offsetXDp * density
        val cy = hole.cy + config.offsetYDp * density
        val radius = baseRadius * expand
        val fraction = (state.animatedLevel / 100f).coerceIn(0f, 1f)
```

说明：`trackColor` 自定义分支引用的是**已淡出后**的 `progressColor`，与原逻辑（引用选择色）一致地保留了低透明度版本语义；收缩时槽与弧一起淡出。

- [ ] **Step 2: 新增 scaleAlpha 私有辅助**

在 `drawGlowArc` 之前插入：

```kotlin
    /** 按比例缩放颜色透明度，用于沉浸收缩的淡出 */
    private fun scaleAlpha(color: Int, factor: Float): Int {
        val a = (android.graphics.Color.alpha(color) * factor).toInt().coerceIn(0, 255)
        return (a shl 24) or (color and 0x00FFFFFF)
    }
```

- [ ] **Step 3: 诊断日志补充收起进度**

现有 `if (!diagLogged)` 块的日志字符串末尾追加 `, collapse=${state.collapseProgress}`（保持一次性诊断风格不变，只改内容）。

- [ ] **Step 4: 编译验证**

```bash
cd HolePowerRing
./gradlew.bat assembleDebug
```

Expected: `BUILD SUCCESSFUL`。

---

### Task 4: RingWindowController insets 检测收起

**Files:**
- Modify: `HolePowerRing/app/src/main/java/com/powerring/hole/ring/RingWindowController.kt:47-111`

**设计要点（务必理解后再改）：** 只有曾经收到过 `statusBars().top > 0` 才信任该信号（armed）。若本机 SystemUI 的 2009 浮窗根本不派发 statusBars 类型（恒为 0 或抛异常），检测永不启用、行为与改动前完全一致——宁可功能不生效，也绝不能把环永久藏掉。这是本任务的核心安全约束。

- [ ] **Step 1: 重写 attach 中的 insets 监听**

将 `attach()` 中 `val view = PowerRingView(...).apply { ... }` 整块替换为：

```kotlin
        // statusBars insets 可信性门控：见过正值后才把"归零"当作收起信号
        var statusBarsArmed = false
        var firstDispatchLogged = false
        val view = PowerRingView(context.applicationContext).apply {
            // 收到 insets 后按挖孔安全区高度调整窗口高度；同时检测状态栏沉浸收起
            setOnApplyWindowInsetsListener { v, insets ->
                val cutoutTop = insets.displayCutout?.safeInsetTop ?: 0
                val extraPx = (EXTRA_BOTTOM_DP * v.resources.displayMetrics.density).toInt()
                val targetHeight = (cutoutTop + extraPx).coerceAtLeast(1)
                val lp = v.layoutParams as? WindowManager.LayoutParams
                if (lp != null && lp.height != targetHeight) {
                    lp.height = targetHeight
                    runCatching { wm.updateViewLayout(v, lp) }
                        .onFailure { ModuleLog.e("更新环窗口高度失败", it) }
                }
                val statusTop = runCatching {
                    insets.getInsets(WindowInsets.Type.statusBars()).top
                }.getOrDefault(-1)
                if (!firstDispatchLogged) {
                    firstDispatchLogged = true
                    ModuleLog.i("环窗口首次 insets: cutoutTop=$cutoutTop statusTop=$statusTop")
                }
                when {
                    statusTop > 0 -> {
                        if (!statusBarsArmed) {
                            statusBarsArmed = true
                            ModuleLog.i("statusBars insets 信号已启用（见过正值），开始跟随收起状态")
                        }
                        RingState.setStatusBarCollapsed(false)
                    }
                    statusTop == 0 && statusBarsArmed -> RingState.setStatusBarCollapsed(true)
                    // statusTop < 0：该类型不存在；== 0 且未 armed：不可信，忽略
                }
                v.postInvalidateOnAnimation()
                insets
            }
        }
```

- [ ] **Step 2: 编译验证**

```bash
cd HolePowerRing
./gradlew.bat assembleDebug
```

Expected: `BUILD SUCCESSFUL`。`RingState` 与 `PowerRingView` 同包，无需新增 import；`WindowInsets` 已 import。

---

### Task 5: 设置页新增开关

**Files:**
- Modify: `HolePowerRing/app/src/main/java/com/powerring/hole/ui/SettingsScreen.kt:222-232`

- [ ] **Step 1: SwitchPreference 增加一行**

在「开关」Tab（`SwitchTabContent`）的「充电高亮辉光」`SwitchPreference` 之后、卡片闭合 `}` 之前插入（分隔线风格与既有项一致）：

```kotlin
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    SwitchPreference(
                        checked = config.collapseOnImmersive,
                        onCheckedChange = { enabled ->
                            PrefsStore.setBoolean(
                                ctx, RingConfig.KEY_COLLAPSE_ON_IMMERSIVE, enabled,
                            )
                            update(config.copy(collapseOnImmersive = enabled))
                        },
                        title = "状态栏收起时隐藏圆环",
                        summary = "观看视频/图片等状态栏自动收起的场景，圆环向内收缩并淡出；状态栏恢复时弹回",
                        enabled = config.ringEnabled,
                    )
```

- [ ] **Step 2: 更新「关于」页提示文案**

`AboutTabContent` 提示卡片的 Text 内容改为（在原文「息屏/AOD 期间自动隐藏圆环以防烧屏」后补充一句）：

```kotlin
                        text = "提示：所有调节即时生效；息屏/AOD 期间自动隐藏圆环以防烧屏，" +
                            "状态栏自动收起（沉浸模式）时圆环同步收缩隐藏。" +
                            "若调节后环与挖孔有偏差，优先用「缩放」对齐半径，再用偏移微调中心。",
```

- [ ] **Step 3: 编译验证**

```bash
cd HolePowerRing
./gradlew.bat assembleDebug
```

Expected: `BUILD SUCCESSFUL`。产物 `app/build/outputs/apk/debug/app-debug.apk`。

---

### Task 6: 真机安装与首轮验证

**Files:** 无代码改动。

- [ ] **Step 1: 安装 APK（需设备已连接；若 adb 有其他连接先与用户确认用哪台）**

```bash
cd HolePowerRing
./gradlew.bat installDebug
```

Expected: `Installed`。

- [ ] **Step 2: 重启 SystemUI**

优先用设置页「系统 → 重启系统界面」按钮（内置 root 通道）。若设置页不可用，需执行 adb 命令时**先征得用户同意**再运行：

```bash
adb shell "su -c 'killall com.android.systemui'" 
```

（本机 su 行为见项目记忆：SystemUI 为普通应用 uid，killall 需 root。）

- [ ] **Step 3: 确认日志基线**

```bash
adb logcat -s HolePowerRing
```

Expected: 出现 `环形电量独立窗口已添加（type=2009）`、`环窗口首次 insets: cutoutTop=... statusTop=...`。

**关键决策点，按 statusTop 数值分流：**
- `statusTop > 0` 且随后出现 `statusBars insets 信号已启用` → 检测可用，继续 Step 4。
- `statusTop = 0`（恒零、信号始终未启用）→ 本机 2009 浮窗不派发 statusBars，**跳到 Task 8 兜底探针**，完成后再回来做 Step 4（此时验证目标改为探针日志 `沉浸探针已安装于 ...` 与收起行为）。
- 其他异常值 → 把日志原样报告给用户，不要自行猜测放宽条件。

- [ ] **Step 4: 功能验证（真机手测，全部场景都要过）**

依次验证并记录结果：

1. 打开任意全屏幕视频（B 站/YouTube 全屏播放）→ 状态栏自动收起 → 环 260ms 向内收缩淡出，日志 `状态栏沉浸收起状态: collapsed=true`。
2. 点一下屏幕唤出状态栏 → 环弹回原尺寸，日志 `collapsed=false`。
3. 收起期间下拉通知栏 → 状态栏瞬时可见 → 环应重新展开（与系统行为一致，属预期）。
4. 设置页关闭「状态栏收起时隐藏圆环」→ 立即弹回且不再生效（视频全屏时环保持显示）；重新开启 → 再次全屏时恢复收缩。
5. 息屏 → 亮屏（非沉浸场景）→ 环正常显示，无卡死。
6. 沉浸收起期间充电/拔线 → 无异常日志（`电池状态:` 行正常但不再绘制）。
7. 回归：普通桌面、控制中心下拉、锁屏，环行为与改动前一致；设置页各 Tab 正常。

若任一场景 SystemUI 崩溃（`adb logcat -b crash` 有 systemui 堆栈），立即停止并把堆栈报告给用户。

---

### Task 7: 结论回写文档

**Files:**
- Modify: `挖孔环形电量LSP模块-可行性分析报告.md`

- [ ] **Step 1: 在报告末尾「风险与待验证项」相关章节追加结论**

记录（按实际验证结果措辞调整）：

```markdown
## 沉浸模式收起检测（2026-10-01 验证）

- 检测方式：环形电量独立窗口（type=2009）的 `OnApplyWindowInsetsListener` 读取
  `WindowInsets.Type.statusBars().top`，归零即视为状态栏自动收起；需先见过正值才启用
  （armed 门控，防止个机型浮窗不派发该类型导致环永久隐藏）。
- 真机结论：<本机是否派发 statusBars insets；若走了兜底探针，注明实际 hook 的类名与方法>
- 动画：半径按 `1-collapse` 收敛、透明度同步淡出，260ms，DecelerateInterpolator(1.5f)。
```

- [ ] **Step 2: 同步 AGENTS.md「已知待验证项」**

若结论确认 insets 方案可用，在 `AGENTS.md` 的「已知待验证项」列表追加一行说明沉浸收起检测已在本机验证通过（或注明依赖兜底探针）。

---

### Task 8（条件执行，仅当 Task 6 Step 3 判定 statusTop 恒零）: 兜底沉浸探针

> **⚠️ 执行结果更正（2026-10-01 已落地并真机验证通过）——照本文原样实现会失效，先读这段：**
>
> 1. **本任务确已触发**（`statusTop` 恒为 0），但下方候选类名**全部错误**，本机均不存在：
>    - `com.android.systemui.statusbar.phone.StatusBarWindowView` ❌
>    - `com.android.systemui.statusbar.phone.MiuiStatusBarWindowView` ❌（`MiuiStatusBarWindowView` 只是 `StatusBarWindowView` 内的一个字符串常量，不是类名）
>    - ✅ 实际类名为 `com.android.systemui.statusbar.window.StatusBarWindowView`
> 2. **`setTranslationY` 方案不可用**：该类并未声明 `setTranslationY`/`setVisibility`，`findAndHookMethod` 会沿父类链解析到 `android.view.View` 的同名方法，从而在 SystemUI 内对**每个 View** 生效，既有误判风险又会拖慢进程。
> 3. **最终采用的信号**：`StatusBarWindowStateController$commandQueueCallback$1.setWindowState(int displayId, int window, int state)`，仅跟随 `window=1`（`WINDOW_STATUS_BAR`），`state` 取 `0=SHOWING/1=HIDING/2=HIDDEN`。环窗口自身的 insets 亦不可用（恒 `statusTop=0`），状态栏窗口自身的 insets 同样不可用。
> 4. 本任务还需修改第 3 个文件：`ring/RingWindowController.kt`（停用已失效的 insets `when` 分支，防双写）。下文 Files 列表遗漏了它。
>
> 完整实测过程、类名索引与陷阱记录见 `挖孔环形电量LSP模块-可行性分析报告.md` §10。

**Files:**
- Create: `HolePowerRing/app/src/main/java/com/powerring/hole/hook/ImmersiveProbeHook.kt`
- Modify: `HolePowerRing/app/src/main/java/com/powerring/hole/hook/SystemUiHooks.kt:38-45`

**原理：** 沉浸模式下系统把状态栏窗口根 View 用 `translationY` 平移出屏幕顶。Hook 其 `setTranslationY`，`translationY < -4px` 即视为收起。类名用候选列表 + `findClassIfExists` 降级，找不到只记日志不影响既有功能。

- [ ] **Step 1: 新建 ImmersiveProbeHook**

```kotlin
package com.powerring.hole.hook

import android.view.View
import com.powerring.hole.core.ModuleLog
import com.powerring.hole.ring.RingState
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import de.robv.android.xposed.XposedHelpers

/**
 * 兜底沉浸探针：仅当本机环窗口不派发 statusBars insets 时启用。
 *
 * 状态栏自动收起时系统通过 translationY 把状态栏窗口根 View 平移出屏幕顶，
 * Hook setTranslationY 即可拿到收起/恢复时机（SystemUI 主线程调用）。
 */
object ImmersiveProbeHook {

    private val CANDIDATES = listOf(
        "com.android.systemui.statusbar.phone.StatusBarWindowView",
        "com.android.systemui.statusbar.phone.MiuiStatusBarWindowView",
    )

    /** 平移超过该像素数即视为已收起；过滤浮点抖动与瞬时小位移 */
    private const val COLLAPSE_THRESHOLD_PX = 4f

    @Volatile
    private var installed = false

    fun install(classLoader: ClassLoader) {
        if (installed) return
        installed = true
        val cls = CANDIDATES.firstNotNullOfOrNull {
            XposedHelpers.findClassIfExists(it, classLoader)
        }
        if (cls == null) {
            ModuleLog.e("未找到状态栏窗口根 View 类，沉浸兜底探针未生效", null)
            return
        }
        try {
            XposedHelpers.findAndHookMethod(
                cls, "setTranslationY", Float::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val ty = param.args[0] as Float
                            RingState.setStatusBarCollapsed(-ty > COLLAPSE_THRESHOLD_PX)
                        } catch (t: Throwable) {
                            ModuleLog.e("沉浸探针 setTranslationY 异常", t)
                        }
                    }
                },
            )
            ModuleLog.i("沉浸兜底探针已安装于 ${cls.name}")
        } catch (t: Throwable) {
            ModuleLog.e("Hook ${cls.name}.setTranslationY 失败", t)
        }
    }
}
```

- [ ] **Step 2: 在 SystemUiHooks 注册**

`install(classLoader)` 中「电池图标隐藏」try 块之后插入：

```kotlin
        // 沉浸收起兜底探针
        try {
            ImmersiveProbeHook.install(classLoader)
        } catch (t: Throwable) {
            ModuleLog.e("沉浸探针安装异常", t)
        }
```

- [ ] **Step 3: 环窗口侧禁用 insets 检测避免双写**

`RingWindowController` 中 `when` 块整体注释掉并在其上注明原因：

```kotlin
                // 本机验证：2009 浮窗 statusBars insets 恒为 0，不可用；
                // 收起检测改由 ImmersiveProbeHook（translationY）驱动，此处保持禁用防止双写抖动。
```

（`statusTop` 计算与首次日志保留，方便后续换机型复查。）

- [ ] **Step 4: 编译 + 重复 Task 6 Step 2–4 验证**

Expected: 日志出现 `沉浸兜底探针已安装于 com.android.systemui.statusbar.phone.<实际类名>`，全屏视频环收缩、恢复弹回。

---

## Self-Review 结论

- 覆盖检查：需求「状态栏收起→环向内收缩隐藏」由 Task 2/3/4 实现；「恢复时弹回」由同一 animateCollapseTo 反放覆盖；开关可回退（Task 1/5）；本机不可测风险有 armed 门控 + Task 8 兜底。
- 类型一致性：`collapseProgress`/`setStatusBarCollapsed`/`collapseOnImmersive`/`KEY_COLLAPSE_ON_IMMERSIVE` 在全部任务中签名一致；`scaleAlpha` 仅在 Task 3 定义并使用；`COLLAPSE_DURATION_MS` 仅 Task 2 使用（普通 val，object 内私有即可，不用 const 因 ValueAnimator 时长为 Long 常量——用 `private val` 保持与现有 `levelInterpolator` 同风格）。
- 无占位符；每个代码步骤均给出完整代码。
