# 环形电量窗口层级分析与抬层修复计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 查清"环形电量被超级岛（灵动岛）遮住"的真实成因，把电量环的窗口层级改成严格高于灵动岛，并给出可回归的真机验证方法。

**Architecture:** 不改代码先取证。层级由 WMS 的 `mBaseLayer`（窗口 type 经本机 policy 表映射出的层带值）决定；**同层带内两个窗口谁叠在上，取决于哪个 surface 后创建**——而 `dumpsys window windows` 的 `Window #N` 索引在同带内与实际合成顺序相反，是判读陷阱。取到权威判据（SurfaceFlinger HWC 输出层数组）后，把环窗口的 type 从 2009 抬到实测层带更高的 2006，让层级结论不再依赖添加顺序。

**Tech Stack:** adb（`dumpsys window windows` / `dumpsys SurfaceFlinger --layers` / `screencap`）、work/ DEX 静态分析脚本、Kotlin / Xposed。

**取证环境（2026-10-01 实测）:** 设备 25102RKBEC（myron），系统界面 17.03.260226.r，插件 18.2.2.2.0，1200x2608 @480dpi。adb 为普通 shell 权限（无 root，`killall com.android.systemui` 返回 `Operation not permitted`）。

> **实施状态（2026-10-01）：Task 1–4 全部完成并真机验证。**
> - Task 1 抬层（`type=2006`/层带 231000）：已验证 —— 环已在灵动岛之上，**锁屏与息屏（AOD）显示正常**，§5 方案 A 的未知项 R3 已排除。
> - Task 2 验证：已由 `dumpsys SurfaceFlinger --layers` 的 `Output Layer` 数组 + A/B 截图确认。
> - Task 4 灵动岛收起：已实现并双方向验证（信号源换成宿主侧 `updateIslandShowing`，见 Task 4 正文）。
> - **过程中发生一次 SystemUI 启动期 ANR 死锁事故**（插件侧 Hook 方案导致，手机界面卡死），根因与纪律见可行性分析报告 §12.2 与 Task 4 的「已否决」小节。

**执行约定（用户既定偏好，沿用 2026-10-01-immersive-ring-collapse.md）：**
- 全程**不执行 git commit**（注：另有并行会话在本仓库提交过 `7b4f57d` / `76a25c9`，其中 `76a25c9` 含**会冻死 SystemUI 的插件侧 Hook 版本**；本计划的宿主侧修复若未提交，重置工作区会退回事故版本）。
- 重启 SystemUI 用设置页内置的「重启系统界面」按钮；adb 无 root，必要时用 `adb reboot`。
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

### Task 4: 灵动岛显示时收起圆环（已实现并真机验证）

**状态：完成（2026-10-01）。** 实现为 `hook/IslandVisibilityHook.kt` + 配置项 `collapseOnIsland`
（设置页「有岛时隐藏圆环」，默认关），与沉浸收起共用 `RingState` 的同一条 `collapseProgress` 动画通道。

**采用的信号源：宿主侧 `MiuiBatteryMeterView.updateIslandShowing(ZZZ)V`** —— `afterHookedMethod`
内读 `mIsIslandShowing` 字段后驱动 `RingState.setIslandShowing(showing)`。用宿主 ClassLoader 直接取类，
**不涉及任何类加载劫持**；只认位于 `MiuiStatusBatteryContainer` 内的实例，避免状态栏/控制中心多实例打架。

真机实测（媒体岛，播放 → 停止），**两个方向都到达**：

```
灵动岛显隐驱动: showing=true   → 灵动岛显示状态: showing=true  沉浸=false 收起目标=1.0   （环收起）
灵动岛显隐驱动: showing=false  → 灵动岛显示状态: showing=false 沉浸=false 收起目标=0.0   （环恢复）
```

> ## ⛔ 已否决：任何"在 `ClassLoader.loadClass` 回调里装 Hook"的做法
>
> 本计划初稿的 Task 4（"保带抢先"：Hook 插件 `DynamicIslandWindowController$attach$1` 后在岛之后
> 重建环窗口）**已被否决，不要再实现**。同思路的写法——在 `loadClass` 回调里做
> `cls.declaredMethods`（强制解析签名 → 触发插件内混淆协程类型的二次类加载）**并当场安装 Hook**
> （触发 ART deoptimize / suspend-all）——在真机上**直接死锁 SystemUI**：
>
> ```
> ANR in com.android.systemui
> Reason: Process ... failed to complete startup     （反复重启仍 ANR，手机界面卡死）
> ```
>
> 恢复方式只能是重装安全版 APK + `adb reboot`（adb 无 root 不能单独重启 SystemUI）。
> 完整复盘见可行性分析报告 §12.2。
>
> **由此确立的选型原则：灵动岛等插件侧信息，一律优先取宿主侧等价信号；实在没有时，
> 回调内只做字符串比较，把重活 `post` 到主线程后再执行。**

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