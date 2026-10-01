# 环形电量窗口层级分析与抬层修复计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 查清"环形电量被超级岛（灵动岛）遮住"的真实成因，把电量环的窗口层级改成严格高于灵动岛，并给出可回归的真机验证方法。

**Architecture:** 不改代码先取证。层级由 WMS 的 `mBaseLayer`（窗口 type 经本机 policy 表映射出的层带值）决定；**同层带内两个窗口谁叠在上，取决于哪个 surface 后创建**——而 `dumpsys window windows` 的 `Window #N` 索引在同带内与实际合成顺序相反，是判读陷阱。取到权威判据（SurfaceFlinger HWC 输出层数组）后，把环窗口的 type 从 2009 抬到实测层带更高的 2006，让层级结论不再依赖添加顺序。

**Tech Stack:** adb（`dumpsys window windows` / `dumpsys SurfaceFlinger --layers` / `screencap`）、work/ DEX 静态分析脚本、Kotlin / Xposed。

**取证环境（2026-10-01 实测）:** 设备 25102RKBEC（myron），系统界面 17.03.260226.r，插件 18.2.2.2.0，1200x2608 @480dpi。adb 为普通 shell 权限（无 root，`killall com.android.systemui` 返回 `Operation not permitted`）。

**执行约定（用户既定偏好，沿用 2026-10-01-immersive-ring-collapse.md）：**
- 全程**不执行 git commit**。
- 重启 SystemUI 用设置页内置的「重启系统界面」按钮。
- 所有 Hook/回调逻辑必须包 `try/catch (Throwable)` 并走 `ModuleLog`。

---

> ## ⚠️ 更正声明
>
> 本文**第一版**曾得出"环已在岛之上、抬层没必要"的相反结论。该结论建立在两个错误前提上：① 取证时灵动岛窗口处于 `mViewVisibility=INVISIBLE`、窗口高度为 0 的空闲态，**岛 surface 根本不参与合成**；② 把 `dumpsys window windows` 的 `Window #N` 索引当成了实际 z 序。
>
> 在**岛真正显示内容**的条件下复测后，结论被推翻：**遮挡成立，环被完全盖住**。用户的实测反馈是正确的。下文 §3 给出推翻旧结论的完整证据。

---

## 1. 结论摘要（更正后）

| # | 结论 | 依据 |
|---|---|---|
| C1 | **遮挡成立**：超级岛展开时，黑色胶囊把挖孔处的圆环完全盖住 | 同一 SystemUI 会话内的 A/B 截图（§2）：岛收起 → 环正常绘制；岛展开 → 环完全消失 |
| C2 | **成因是"同层带 + 岛 surface 后创建"**，不是我们选的 type 偏低 | 两窗 `type=2009`、`mBaseLayer=191000`、`mSubLayer=0` 完全相同；surface id 环 `#29646` < 岛 `#29696`，岛后创建 |
| C3 | **`Window #N` 索引在同层带内与实际合成顺序相反，是判读陷阱** | WMS 列出岛 `#6`、环 `#7`（看似环在上），但 SF 的 HWC 输出层数组里岛在环**上方** |
| C4 | **权威判据是 SurfaceFlinger 的 HWC 输出层数组**（自底向上打印） | `dumpsys SurfaceFlinger --layers` 中 `Output Layer` 顺序，3 次采样一致（§3.3） |
| C5 | **修复方案：把环窗口 type 从 2009 抬到 2006**（本机实测层带 231000 > 191000），从此与添加顺序彻底解耦 | 层带表 §3.2 |
| C6 | 备选方案：保持 2009，在岛 `addView` 之后把环窗口 `removeView`+`addView` 一次，抢"后创建"位置 | 依赖同带内 SF 规则，需先跑 Task 4 Step 1 的实验验证，不作为默认 |

---

## 2. A/B 取证：遮挡的直接证据

在同一 SystemUI 会话（pid 23162，窗口对象 `Window{1476507}` 未变）内，只改变"岛是否显示内容"这一个变量：

**A. 岛展开（播放音乐，岛窗口 `mAttrs=(0,0)(fillx156)`）——环完全不可见：**

![岛展开时环不可见](images/island-up-no-ring.png)

**B. 岛收起（停止播放后，岛窗口无内容）——环正常绘制（绿色充电弧，约 44%）：**

![岛收起时环正常显示](images/island-down-ring-visible.png)

窗口层级、窗口对象、SystemUI 进程全程未变，唯一变量是岛的可见性 ⇒ **遮挡由岛的 surface 造成，而非环的绘制逻辑、沉浸收起逻辑或配置**。

补充取证：把设置页（`com.powerring.hole/.ui.SettingsActivity`，非全屏应用）拉到前台、岛仍在播放中显示时，环**依然不可见**——排除"全屏沉浸导致环按设计隐藏"这一解释（见 §7 风险 R2）。

---

## 3. 实测数据

### 3.1 两个窗口的参数

```bash
adb shell dumpsys window windows | grep -A8 "Window #.*DynamicIslandWindow\|Window #.*HolePowerRingWindow"
```

```
Window #6 Window{1615fe5 u0 DynamicIslandWindow}:
  mAttrs={(0,0)(fillx156) gr=TOP CENTER_VERTICAL sim={adjust=pan} layoutInDisplayCutoutMode=always
          pkg=miui.systemui.plugin adpkg=null ty=KEYGUARD_DIALOG fmt=TRANSLUCENT
          fl=NOT_FOCUSABLE NOT_TOUCH_MODAL HARDWARE_ACCELERATED DRAWS_SYSTEM_BAR_BACKGROUNDS
          pfl=COLOR_SPACE_AGNOSTIC FIT_INSETS_CONTROLLED TRUSTED_OVERLAY}

Window #7 Window{1476507 u0 HolePowerRingWindow}:
  mAttrs={(0,0)(fillx222) gr=TOP CENTER sim={adjust=pan} layoutInDisplayCutoutMode=always
          pkg=com.android.systemui adpkg=null ty=KEYGUARD_DIALOG fmt=TRANSLUCENT
          fl=NOT_FOCUSABLE NOT_TOUCHABLE LAYOUT_IN_SCREEN LAYOUT_NO_LIMITS SPLIT_TOUCH HARDWARE_ACCELERATED}
```

除窗口大小与 flags 外，**两窗在层级上没有任何差异**：同 `type=2009`、同 `sim`、同 `layoutInDisplayCutoutMode=always`、同 `fmt=TRANSLUCENT`。

> 附带确认：本机 `type=2009` 在 `mAttrs` 中显示为 `ty=KEYGUARD_DIALOG`，`RingWindowController.kt` 现有注释"TYPE_KEYGUARD_DIALOG 槽位，MIUI 复用"**准确无误**。

### 3.2 本机 type → 层带映射表（`mBaseLayer`）

| type | mBaseLayer | 本机实际使用者 |
|---|---|---|
| 2032 | 311000 | `com.omarea.vtools` |
| 2016 | 301000 | `MiuiShellDropTarget` |
| 2027 | 281000 | `GestureStubLeft/Right` |
| 2019 | 241000 | `NavigationBar0` |
| **2006** | **231000** | `AntiMistakeTouchView` ← **抬层目标** |
| **2009** | **191000** | **`DynamicIslandWindow` + `HolePowerRingWindow`**（当前） |
| 2017 | 181000 | `NotificationModalWindowManager` |
| 2040 | 171000 | `NotificationShade` |
| 2000 | 151000 | `StatusBar` |
| 2011 | 131000 | `InputMethod` |
| 2038 | 111000 | `ShellDropTarget` |
| —（应用窗口） | 21000 | 各 Activity |

⚠️ **层带大小与 AOSP type 数值无关**（type=2000 的状态栏只有 151000，低于 type=2009 的 191000）。抬层必须查这张表。**该表是本机实测值而非 ROM 契约，换机型必须重测。**

### 3.3 权威判据：SurfaceFlinger HWC 输出层数组

```bash
adb shell dumpsys SurfaceFlinger --layers | grep -E "Output Layer|hwc: layer="
```

岛显示内容时的实测输出（**数组自底向上打印**）：

```
- Output Layer (VRI-com.powerring.hole/...SettingsActivity)   ← band 21000，应用窗口必须在最底，可作方向锚点
- Output Layer (VRI-StatusBar)                    hwc=0x082430   ← 151000
- Output Layer (VRI-HolePowerRingWindow)          hwc=0x0823f4   ← 191000  【环】
- Output Layer (VRI-DynamicIslandWindow)          hwc=0x082450   ← 191000  【岛】← 压在环之上
- Output Layer (VRI-NavigationBar0)               hwc=0x08244c   ← 241000
```

连续 3 次采样输出完全一致，**遮挡是稳定可复现的，不是瞬态**。

两点必须讲清楚的读法：

1. **打印方向是自底向上。** 锚点是第一行——应用窗口（band 21000）必然在最底层，它排在第一位即证明数组自底向上。
2. **`hwc: layer=0x...` 不是 z 值**，只是 layer 分配 id，与叠加顺序无关（本次五个 id 甚至不是单调的）。判读只看 `Output Layer` 的先后。

### 3.4 陷阱：`Window #N` 索引在同带内与合成顺序相反

`dumpsys window windows` 中：

```
Window #6  DynamicIslandWindow    mBaseLayer=191000
Window #7  HolePowerRingWindow     mBaseLayer=191000
```

`mBaseLayer` 沿 `#N` 递增**单调下降**（311000→301000→281000→241000→231000→191000→191000→181000→171000→151000→111000），说明索引大致按层带排序；但**两窗同属 191000 带时，WMS 把后加入的岛插到了带底（#6），而 SF 实际把岛合成为在上方**。

> **判读规则（本项目强制）：同层带内，永远不要用 `Window #N` 判断谁盖谁；必须用 `dumpsys SurfaceFlinger --layers` 的 `Output Layer` 数组。**
>
> 这正是第一版报告出错的原因：当时环是 `#7`、岛是 `#6`，据此得出"环在上"的错误结论。

### 3.5 surface id 佐证：岛确实后创建

```
- Output Layer (VRI-HolePowerRingWindow#29646)     ← 环 surface id
- Output Layer (VRI-DynamicIslandWindow#29696)     ← 岛 surface id（更大 = 后创建）
```

上一会话同样是环 `#28677/#28678` < 岛 `#29123/#29137`。两会话一致：**环的 surface 先建，岛的 surface 后建，而后建者在同带内叠在上**。

### 3.6 岛窗口创建路径（插件 18.2.2.2.0，`classes2.dex`）

| 类 / 方法 | 行为 |
|---|---|
| `miui.systemui.dynamicisland.window.DynamicIslandWindowController.<init>` | 构造 `LayoutParams`，设 `gravity`/`layoutInDisplayCutoutMode`/`privateFlags`/`token`，`setTitle("DynamicIslandWindow")` |
| `...DynamicIslandWindowController.start()` | `attach()` + `drawDebugWindowSize()` + `listenForVisibility()` + `listenForWatchOutsideTouch()` + `listenForWindowHeight()` |
| `...DynamicIslandWindowController.attach()` | `scope.launch` 两个协程 |
| `...DynamicIslandWindowController$attach$1.invokeSuspend` | **`WindowManager.addView(windowView, lp)`——岛窗口真正被创建的地方** |
| `...DynamicIslandWindowController.apply()` / `apply$lambda$1` | `lp.copyFrom` → `postOnAnimation` → `updateViewLayout`（改属性，不改层带） |
| `...DynamicIslandWindowController$attach$2.invoke(Throwable)` | 失败回滚：`removeView` + `setDying` |

全插件范围扫描 `WindowManager$LayoutParams` 的字段写入（`type`/`privateFlags`/`flags`/`subLayer`/`gravity`/`token`/`width`/`height`）只命中 3 处：`apply` 链改 `flags`/`height`，构造时改 `gravity`/`privateFlags`/`token`。

> **岛运行期间从不修改自己的 `type`** ⇒ 抬层后不存在"岛又升回来"的可能，修复是单向稳定的。

---

## 4. 成因模型

### 4.1 两级判定

1. **层带**：WMS 用 `mBaseLayer = policy(type)` 把窗口分到层带，环与岛同在 191000 带。
2. **带内顺序**：同 `mBaseLayer` 时两个 surface 的 z 相同，最终叠加顺序由**创建先后**决定——**后创建者叠在上**（§3.5 surface id 佐证 + §3.3 实测）。

### 4.2 为什么必然是岛赢

| 事件 | 进程内时刻 |
|---|---|
| 环窗口 `addView` | `Application.onCreate`（模块最早的钩子） |
| 岛窗口 `addView` | 插件 `DynamicIslandWindowController.start()` 协程中，**必然更晚** |

SystemUI 是"宿主 + 运行时加载插件"的结构，插件的 Startable 一定在宿主 `Application.onCreate` 之后启动。**岛 surface 永远后建 ⇒ 永远压在环之上。** 这不是偶发竞态，而是结构性的必然。

### 4.3 对修复方案的约束

- 任何"同带内抢先后"的做法（延迟添加、反复 remove/add、监听层级变化）都是在跟必然输的规则对抗，且需要额外的 Hook 与闪烁代价；
- **唯一稳定解是换层带**：找一个 `mBaseLayer` 严格大于 191000 的 type，让层级胜负不再依赖添加顺序。

---

## 5. 方案对比

| 方案 | 做法 | 改动量 | 确定性 | 风险 | 结论 |
|---|---|---|---|---|---|
| **A. 抬到 type=2006（层带 231000）** | 改一个常量 | 1 文件 | **高**，带值严格大于 191000，与顺序解耦 | 低-中：盖过 StatusBar(151000)/Shade(171000)/HeadsUp(181000) 三带（环是透明窗口，只在挖孔周画画素，实际遮挡面积极小）；锁屏/AOD 行为需验证 | **推荐** |
| B. 保持 2009，岛 addView 后 remove+addView 抢"后创建" | Hook 岛 attach 协程，事后重建本窗口 | 2 文件 + 新 Hook | 中：依赖 SF 同带规则（已由实测支撑，但非公开契约） | 中：启动时一次闪烁；插件类名耦合；岛若再次重建会再赢回去 | 备选（Task 4），A 若在锁屏翻车则启用 |
| C. 抬到 2019 / 2016 / 2032 | 更高层带 | 1 行 | 高 | **高**：挪用导航栏/应用信息/投放目标语义，可能被对应系统策略波及 | 不推荐 |
| D. 画进岛窗口 Canvas | Hook `DynamicIslandWindowView.onDraw` 后绘制 | 大 | 中 | 高：插件混淆重、岛窗口消失环就没了、窗口高度可能裁切 | 不推荐（可行性报告 §6 方案 E 已判不适用） |
| E. 岛展开时淡出环 | 检测岛状态，冲突时降低环的存在感 | 中 | 高 | 无层级风险，但环会消失 | 与 A 互补的产品选项，不解决本问题 |
| F. 维持 2009 不动 | —— | 0 | —— | —— | **不可行，已被实测否决** |

---

## 6. 实施计划

### Task 1: 把环窗口抬到 2006 层带

**Files:**
- Modify: `HolePowerRing/app/src/main/java/com/powerring/hole/ring/RingWindowController.kt:29-30,84,112`

- [ ] **Step 1: 替换窗口类型常量**

把 `RingWindowController.kt` 中的常量块：

```kotlin
    /** 与 DynamicIslandWindow 相同的窗口类型（TYPE_KEYGUARD_DIALOG 槽位，MIUI 复用） */
    private const val TYPE_ISLAND_COMPAT = 2009
```

替换为：

```kotlin
    /**
     * 环窗口的 type。
     *
     * 为什么不是与灵动岛相同的 2009（TYPE_KEYGUARD_DIALOG 槽位，本机层带 191000）：
     * 同层带内两个 surface 的 z 相同，**后创建者叠在上**。环窗口在
     * Application.onCreate 添加，岛窗口在插件协程里添加，必然更晚 —— 于是岛的
     * 黑色胶囊永远压在环之上，环被完全盖住（2026-10-01 真机 A/B 实测，
     * 见 docs/superpowers/plans/2026-10-01-ring-window-layer-priority.md §2/§3.3）。
     *
     * 2006（TYPE_SYSTEM_ALERT）在本机策略表里映射为层带 231000，严格大于
     * 191000，层级胜负不再依赖添加顺序。岛运行期间从不改自己的 type
     * （work/method_refs.py 全插件扫描确认），所以抬层后不会被打回。
     */
    private const val TYPE_RING_WINDOW = 2006
```

- [ ] **Step 2: 参数构造改用新常量**

把 `WindowManager.LayoutParams().apply {` 块内的：

```kotlin
            type = TYPE_ISLAND_COMPAT
```

改为：

```kotlin
            type = TYPE_RING_WINDOW
```

- [ ] **Step 3: 更新成功日志，保留可诊断信息**

把 `wm.addView(view, params)` 成功后的：

```kotlin
            ModuleLog.i("环形电量独立窗口已添加（type=2009）")
```

改为：

```kotlin
            ModuleLog.i(
                "环形电量独立窗口已添加（type=$TYPE_RING_WINDOW，层带 231000 > 灵动岛的 191000）",
            )
```

- [ ] **Step 4: 确认没有残留引用**

```bash
cd HolePowerRing
grep -rn "TYPE_ISLAND_COMPAT" app/src/main/java
```

期望：**无输出**（常量已改名，`PowerRingView` 的注释里若提到 type 数字无需改动）。

- [ ] **Step 5: 编译**

```bash
cd HolePowerRing
gradlew.bat assembleDebug
```

期望：`BUILD SUCCESSFUL`，产物 `app/build/outputs/apk/debug/app-debug.apk`。

- [ ] **Step 6: 安装并启用**

```bash
cd HolePowerRing
gradlew.bat installDebug
```

在 LSPosed 管理器确认作用域仍勾选 `com.android.systemui`，然后用**设置页的「重启系统界面」按钮**重启 SystemUI（adb 无 root，不能用 `killall`）。

- [ ] **Step 7: 验证层带已切换**

```bash
adb shell dumpsys window windows | grep -A2 "HolePowerRingWindow"
```

期望 `mToken=... type=2006`（不再是 `type=2009`）。

### Task 2: 真机验证

**Files:** 无代码改动。

- [ ] **Step 1: 验证合成顺序已翻转（核心验收）**

先让岛显示内容（播放任意媒体或触发任一岛内容），再执行：

```bash
adb shell dumpsys SurfaceFlinger --layers | grep "Output Layer"
```

期望顺序（自底向上）中 **`VRI-HolePowerRingWindow` 出现在 `VRI-DynamicIslandWindow` 之后**（即环在上）：

```
... VRI-StatusBar → VRI-DynamicIslandWindow → VRI-HolePowerRingWindow → VRI-NavigationBar0
```

- [ ] **Step 2: A/B 视觉复测**

重复 §2 的 A/B：岛展开时截图、岛收起时截图。

```bash
adb shell screencap -p /sdcard/after.png && adb pull /sdcard/after.png
```

期望：**岛展开状态下，环仍然可见**（画在岛的黑色胶囊之上）。

- [ ] **Step 3: 非全屏应用下复测**

把设置页拉到前台，同时保持岛有内容，确认环仍可见（排除沉浸模式干扰）。

- [ ] **Step 4: 锁屏与 AOD（抬层的唯一未知项）**

锁屏、息屏再点亮，确认：
- 环是否按预期显示/隐藏；
- 锁屏状态下系统是否把 `TYPE_SYSTEM_ALERT` 窗口一并隐藏。

**若锁屏或 AOD 下环异常，直接跳到 Task 4。**

- [ ] **Step 5: 状态栏与通知回归**

- 状态栏图标、灵动岛自身显示正常；
- 下拉通知面板（`NotificationShade` 171000 带）与 heads-up 通知（`NotificationModalWindowManager` 181000 带）视觉正常；
- 沉浸收起时环仍正常收缩淡出（`collapseOnImmersive` 未受影响）。

- [ ] **Step 6: 留档**

把 Step 1 的 `Output Layer` 输出与 Step 2 的两张截图路径记入本报告 §3.3，作为回归基线。

### Task 3: 同步文档（AGENTS.md 要求）

**Files:**
- Modify: `挖孔环形电量LSP模块-可行性分析报告.md`（重写 §11）
- Modify: `AGENTS.md`（替换 §11 相关待验证项条目）

- [ ] **Step 1: 重写可行性报告 §11**

用本文 §1（更正后结论）、§3.3（权威判据）、§3.4（索引陷阱）、§3.5（surface id 佐证）、§4（成因模型）、§3.2（层带表与抬层结论）替换现有 §11 全部内容，保留 §3.2 层带表与 §4.2 的逆向结论，并加一条醒目提示：

> **判读规则：同层带内禁止用 `dumpsys window windows` 的 `Window #N` 判断叠加顺序，必须用 `dumpsys SurfaceFlinger --layers` 的 `Output Layer` 数组（自底向上）。**

- [ ] **Step 2: 更新 AGENTS.md**

把现有条目：

```
- **窗口层级已实测（2026-10-01）**：环窗口与灵动岛窗口同为 `type=2009` / `mBaseLayer=191000`，同层带内**先 `addView` 者在上**……
```

替换为：

```
- **窗口层级已实测（2026-10-01）**：环与灵动岛窗口同为 `type=2009` / `mBaseLayer=191000`，同层带内**后创建的 surface 叠在上**，岛 surface 必然更晚创建 ⇒ **岛会盖住环**。修复：环窗口抬到 `type=2006`（层带 231000）。**判读铁律：同层带内不要用 `dumpsys window windows` 的 `Window #N` 判断叠加顺序（同带内它与实际相反），要用 `dumpsys SurfaceFlinger --layers` 的 `Output Layer` 数组。** 详见可行性分析报告 §11
```

### Task 4（备选）: 保带抢先——岛 addView 后重建环窗口

> **仅在 Task 2 Step 4 发现 `type=2006` 在锁屏/AOD 下有异常时执行。** 若 Task 2 全绿，本 Task 作废。

**Files:**
- Modify: `HolePowerRing/app/src/main/java/com/powerring/hole/ring/RingWindowController.kt`
- Create: `HolePowerRing/app/src/main/java/com/powerring/hole/hook/IslandWindowOrderHook.kt`
- Modify: `HolePowerRing/app/src/main/java/com/powerring/hole/hook/SystemUiHooks.kt`

- [ ] **Step 1: 先做一次实验，确认同带内"后创建者在上"**

在改代码前，用一次真机实验把规则钉死（本方案的正确性完全依赖它）：

1. 按 Task 1 的 Step 1–2 把 `TYPE_RING_WINDOW` 暂时改回 `2009`；
2. 编译安装、重启 SystemUI；
3. 触发岛显示内容，执行：

```bash
adb shell dumpsys SurfaceFlinger --layers | grep "Output Layer"
```

记录此时环与岛的相对位置（预期：岛在上，即当前故障态）；
4. 在模块里临时加一段"启动 3 秒后 `removeView` + `addView` 环窗口"的调试代码，重启后重复 Step 3。

期望：**重建后环出现在岛的上方**。若成立，规则确认，继续 Step 2；若不成立（环仍被盖），说明 SF 在同 z 时按 WMS 索引而非创建顺序叠加，本 Task 作废并需另寻方案。

- [ ] **Step 2: 新建岛窗口创建时机探针**

创建 `HolePowerRing/app/src/main/java/com/powerring/hole/hook/IslandWindowOrderHook.kt`：

```kotlin
package com.powerring.hole.hook

import com.powerring.hole.core.ModuleLog
import com.powerring.hole.ring.RingWindowController
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import de.robv.android.xposed.XposedHelpers

/**
 * 灵动岛窗口创建时机探针（保带抢先方案的触发器）。
 *
 * 插件由独立 ClassLoader 加载，必须用插件类的 classLoader 解析类名。
 * 岛窗口的真正创建点是 DynamicIslandWindowController$attach$1.invokeSuspend
 * 里的 WindowManager.addView；该协程只在 start() 时跑一次，是冷路径，
 * 不会给 SystemUI 带来额外开销。
 */
object IslandWindowOrderHook {

    /** 插件包前缀，只对灵动岛窗口的类做处理 */
    private const val ISLAND_ATTACH_CLASS =
        "miui.systemui.dynamicisland.window.DynamicIslandWindowController\$attach\$1"

    @Volatile
    private var installed = false

    fun install(hostClassLoader: ClassLoader) {
        if (installed) return
        installed = true
        try {
            // 插件类由宿主 ClassLoader 在运行时加载，先挂钩 loadClass 拿到插件 loader
            XposedHelpers.findAndHookMethod(
                ClassLoader::class.java,
                "loadClass",
                String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val name = param.args[0] as? String ?: return
                        if (name != ISLAND_ATTACH_CLASS) return
                        val cls = param.result as? Class<*> ?: return
                        hookAttach(cls)
                    }
                },
            )
            ModuleLog.i("灵动岛窗口探针已挂载（等待插件类加载）")
        } catch (t: Throwable) {
            ModuleLog.e("灵动岛窗口探针安装异常", t)
        }
    }

    private var hooked = false

    private fun hookAttach(cls: Class<*>) {
        if (hooked) return
        hooked = true
        try {
            XposedHelpers.findAndHookMethod(
                cls,
                "invokeSuspend",
                Any::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            // 岛窗口此刻已 addView，其 surface 已创建。
                            // 同层带内后创建的 surface 叠在上，重新添加环窗口即可翻盘。
                            RingWindowController.recreateAboveIsland()
                        } catch (t: Throwable) {
                            ModuleLog.e("重建环窗口以抢占层级失败", t)
                        }
                    }
                },
            )
            ModuleLog.i("已捕获 ${cls.name}，等待 invokeSuspend")
        } catch (t: Throwable) {
            ModuleLog.e("Hook ${cls.name}.invokeSuspend 失败", t)
        }
    }
}
```

- [ ] **Step 3: RingWindowController 增加重建方法**

在该文件的 `attach` 函数之后新增：

```kotlin
    /**
     * 在灵动岛窗口创建之后重建本窗口，抢"同层带内后创建者在上"的位置。
     *
     * 仅在窗口尚未添加成功时是空操作；重建复用同一个 View 实例，
     * 窗口高度会随 insets 监听重新校准，不影响绘制状态。
     */
    fun recreateAboveIsland() {
        val wm = windowManager ?: return
        val view = ringView ?: return
        if (!attached) return
        try {
            wm.removeView(view)
        } catch (t: Throwable) {
            ModuleLog.e("重建前移除环窗口失败，放弃本次重建", t)
            return
        }
        try {
            wm.addView(view, params)
            ModuleLog.i("环窗口已在灵动岛窗口之后重建（type=$TYPE_RING_WINDOW）")
        } catch (t: Throwable) {
            ModuleLog.e("重建环窗口失败，环将不可见", t)
        }
    }
```

同时把当前的 `params` 提升为对象级字段（原为 `attach` 内的局部变量），使其可被重建方法复用：

- 在字段区（`private var ringView: PowerRingView? = null` 之后）新增 `private var params: WindowManager.LayoutParams? = null`
- 在 `attach` 中把 `val params = WindowManager.LayoutParams().apply {` 改为 `params = WindowManager.LayoutParams().apply {`
- 重建方法中改用局部快照：`val p = params ?: return`，并把 `wm.addView(view, params)` 改为 `wm.addView(view, p)`

> 注意：`setOnApplyWindowInsetsListener` 里对 `lp.height` 的更新走的是 `v.layoutParams`，remove/add 之后由系统重新下发 insets，会再次校正高度，逻辑无需改动。

- [ ] **Step 4: 注册探针**

在 `SystemUiHooks.install()` 中 `ImmersiveProbeHook.install(classLoader)` 之后插入：

```kotlin
        // 灵动岛窗口创建时机探针（保带抢先方案）
        try {
            IslandWindowOrderHook.install(classLoader)
        } catch (t: Throwable) {
            ModuleLog.e("灵动岛窗口探针安装异常", t)
        }
```

- [ ] **Step 5: 编译、安装、重启、复验**

重复 Task 1 Step 5–7 与 Task 2 Step 1–3，额外确认日志中出现：

```
环窗口已在灵动岛窗口之后重建（type=2009）
```

且 `Output Layer` 数组中环位于岛之后。

---

## 7. 风险与边界

| 风险 | 说明 | 处置 |
|---|---|---|
| R1 抬层后盖过状态栏/通知面板/heads-up | 231000 高于 151000/171000/181000 三带 | 环是 `TRANSLUCENT` 窗口，只在挖孔周画画素，实际遮挡面积极小；Task 2 Step 5 做回归 |
| R2 误判为"沉浸模式把环藏了" | 全屏视频里环本就不显示（`collapseOnImmersive`），会与遮挡混淆 | §2 已用非全屏应用（设置页）复测排除 |
| R3 `TYPE_SYSTEM_ALERT` 在锁屏/AOD 被系统隐藏 | 本机未验证，是本方案唯一未知项 | Task 2 Step 4 专项验证；异常则走 Task 4 |
| R4 层带表是本机实测值不是 ROM 契约 | 换机型后 2006 的层带可能不再高于 2009 | 换机型后先用 `dumpsys window windows` 取层带表复核，再决定 type |
| R5 Task 4 依赖 SF 同带叠加规则 | 非公开契约 | Task 4 Step 1 先做实验验证，验证不过就不做 |
| R6 adb 无 root，不能重启 SystemUI | 本次无法自动验证重启后行为 | 一律用设置页「重启系统界面」按钮 |
| 不做 git 提交 | 用户既定偏好 | 各任务以"记录变更点"代替 commit |

---

## 8. 附：复现本次取证的命令

```bash
# 1. 两窗参数与层带
adb shell dumpsys window windows | grep -A8 "Window #.*DynamicIslandWindow\|Window #.*HolePowerRingWindow"

# 2. 全部窗口的序号与层带（用于核对层带表）
adb shell dumpsys window windows | grep -E "Window #|mBaseLayer"

# 3. 权威判据：合成顺序（自底向上）
adb shell dumpsys SurfaceFlinger --layers | grep -E "Output Layer|hwc: layer="

# 4. 触发/收起岛 + 截图
adb shell input keyevent KEYCODE_WAKEUP
adb shell input keyevent 85    # MEDIA_PLAY
adb shell screencap -p /sdcard/up.png && adb pull /sdcard/up.png
adb shell input keyevent 86    # MEDIA_STOP
adb shell screencap -p /sdcard/down.png && adb pull /sdcard/down.png

# 5. 模块日志
adb logcat -d | grep HolePowerRing
```