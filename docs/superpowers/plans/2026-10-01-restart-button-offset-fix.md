# 重启 SystemUI 按钮 + 偏移裁切修复 实施计划

> **For agentic workers:** 按任务逐个实施（subagent-driven 或 inline）。步骤用 `- [ ]` 跟踪。**本仓库约定全程不做 git commit**（用户偏好），验证以编译 + 真机为准（仓库无自动化测试，见 AGENTS.md）。

**Goal:** ① 开关页新增 root 重启 SystemUI 按钮；② 修复外观页垂直偏移被环窗口高度裁切导致"效果不明显"的问题。

**Architecture:** ① 在 `SettingsScreen.kt` 的开关 Tab 卡片下加一行 `ArrowPreference`，点击后子线程 `Runtime.exec("su -c killall com.android.systemui")`，主线程 Toast 反馈；② `RingWindowController` 的窗口底部余量从固定 12px 改为 `26dp × density`（覆盖 ±20dp 最大偏移 + 环半径余量），并同步两处偏移行的 summary 说明屏幕顶限制。

**Tech Stack:** Kotlin + Compose + miuix 0.9.4-rc01；无新依赖。

**诊断依据（已核实）：**
- 偏移生效链路：`RingRenderer.kt:83-84`（`cx/cy = hole中心 ± offsetDp × density`）→ 绘制在环窗口 View 的 Canvas 上，**Canvas 裁切到 View 边界**
- 环窗口高度：`RingWindowController.kt:33` `EXTRA_BOTTOM_PX = 12`、`:52-53` `targetHeight = cutoutTop + 12`；本机挖孔安全区约 144px、孔心 cy≈72px、环外沿半径≈40-48px → 向下偏移 >~10dp 环底越界被裁，向上偏移 >~8dp 出屏幕顶（物理极限，无法通过扩窗解决）
- 水平方向窗口为 MATCH_PARENT 全屏宽，不受此限制

---

### Task 1: 开关页「重启系统界面」按钮

**Files:**
- Modify: `HolePowerRing/app/src/main/java/com/powerring/hole/ui/SettingsScreen.kt`（`SwitchTabContent`，约 175-240 行区域；文件头新增 import）

- [ ] **Step 1: 在文件头新增 import**

```kotlin
import android.os.Handler
import android.os.Looper
import android.widget.Toast
```

- [ ] **Step 2: 在 `SwitchTabContent` 的开关 Card 之后新增一个 item + Card**

在 `item(key = "switchCard") { ... }` 闭合之后、`item(key = "bottomSpacer")` 之前插入（`restarting` 状态与 `ArrowPreference`）：

```kotlin
item(key = "systemTitle") {
    SmallTitle("系统")
}
item(key = "systemCard") {
    var restarting by remember { mutableStateOf(false) }
    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
        ArrowPreference(
            title = "重启系统界面",
            summary = "以 root 权限终止并自动拉起 SystemUI，用于旧版本配置未热加载时",
            enabled = !restarting,
            onClick = {
                restarting = true
                restartSystemui { ok ->
                    restarting = false
                    Toast.makeText(
                        ctx,
                        if (ok) "SystemUI 正在重启" else "重启失败：未获得 root 权限",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
        )
    }
}
```

注意：`SwitchTabContent` 现有签名已有 `ctx: Context` 参数，直接复用；`ArrowPreference`、`SmallTitle`、`Card`、`remember` 等 import 文件里已存在。

- [ ] **Step 3: 在文件底部（`ConfigSlider` 之后）新增重启函数**

命令为固定字符串，不拼接任何用户输入（无注入面）：

```kotlin
/** 以 root 重启 SystemUI；结果回调切回主线程。su 不存在/被拒时回调 false。 */
private fun restartSystemui(onResult: (Boolean) -> Unit) {
    Thread {
        val ok = runCatching {
            Runtime.getRuntime()
                .exec(arrayOf("su", "-c", "killall com.android.systemui"))
                .waitFor() == 0
        }.getOrDefault(false)
        Handler(Looper.getMainLooper()).post { onResult(ok) }
    }.start()
}
```

- [ ] **Step 4: 编译**

Run: `cd "C:/Users/32732/Desktop/TRAE SOLO/powering/HolePowerRing" && cmd //c "gradlew.bat assembleDebug --console=plain"`
Expected: `BUILD SUCCESSFUL`

---

### Task 2: 修复垂直偏移被窗口裁切（扩窗 + 文案说明）

**Files:**
- Modify: `HolePowerRing/app/src/main/java/com/powerring/hole/ring/RingWindowController.kt:33`（余量常量）、`:50-62`（insets 监听里的高度计算）
- Modify: `HolePowerRing/app/src/main/java/com/powerring/hole/ui/SettingsScreen.kt`（两个偏移行 summary）

- [ ] **Step 1: `RingWindowController.kt` 把固定 12px 余量改为按 dp 计算**

原（第 32-33 行）：

```kotlin
    /** 窗口高度在 cutout 安全区之外的余量（px） */
    private const val EXTRA_BOTTOM_PX = 12
```

改为：

```kotlin
    /**
     * 窗口高度在 cutout 安全区之外的余量（dp）。
     * 需覆盖最大向下偏移（OFFSET_MAX=20dp）+ 环半径外沿，否则垂直偏移下半环被窗口裁切。
     * 向上偏移受屏幕顶物理限制，扩窗无法解决，由设置页文案说明。
     */
    private const val EXTRA_BOTTOM_DP = 26f
```

原（第 52-53 行）：

```kotlin
                val cutoutTop = insets.displayCutout?.safeInsetTop ?: 0
                val targetHeight = (cutoutTop + EXTRA_BOTTOM_PX).coerceAtLeast(1)
```

改为：

```kotlin
                val cutoutTop = insets.displayCutout?.safeInsetTop ?: 0
                val extraPx = (EXTRA_BOTTOM_DP * v.resources.displayMetrics.density).toInt()
                val targetHeight = (cutoutTop + extraPx).coerceAtLeast(1)
```

窗口本身 `FLAG_NOT_TOUCHABLE` 全穿透（`:75-82` 不变），加高仅扩大可绘制区，对状态栏交互零影响。

- [ ] **Step 2: `SettingsScreen.kt` 更新两个偏移行的 summary**

水平偏移行 `summary = "正值向右，负值向左"` 改为：

```kotlin
                        summary = "正值向右，负值向左（约 1dp≈3px）",
```

垂直偏移行 `summary = "正值向下，负值向上"` 改为：

```kotlin
                        summary = "正值向下，负值向上；向上幅度受屏幕顶限制",
```

- [ ] **Step 3: 编译**

Run: 同 Task 1 Step 4
Expected: `BUILD SUCCESSFUL`

---

### Task 3: 真机验证（需用户确认杀 SystemUI）

- [ ] **Step 1: 安装**

Run: `adb -s <serial> install -r HolePowerRing/app/build/outputs/apk/debug/app-debug.apk`
（注意：设备同时有 USB+无线两条连接，同一台机器 serial `6f1adaee`，所有命令带 `-s 6f1adaee`）

- [ ] **Step 2: 重启 SystemUI 加载新模块代码**

优先走本次新增的 UI 按钮（同时验证 Task 1）：打开设置页 → 开关 Tab → 点「重启系统界面」→ 应 Toast "SystemUI 正在重启"。
（命令行兜底：`adb -s 6f1adaee shell "su -c 'killall com.android.systemui'"` —— 该操作会重启系统界面，执行前需用户确认）

- [ ] **Step 3: 验证偏移不再被裁切**

1. 设置页 → 外观 Tab → 垂直偏移拉到 +20dp，截图：环整体下移且**下半环完整可见**（此前被裁底）
2. 垂直偏移 -20dp：环上移，上半环出屏幕顶属预期物理限制，确认无异常闪烁/崩溃
3. 水平偏移 ±20dp：环左右移动完整
4. 全部归零后截图对比基线

Run: `adb -s 6f1adaee shell "screencap -p /sdcard/x.png" && adb -s 6f1adaee pull //sdcard/x.png .`（Git Bash 下 pull 路径需双斜杠）

- [ ] **Step 4: 验证日志无裁切告警**

Run: `adb -s 6f1adaee logcat -d -s HolePowerRing | grep -E "环绘制参数|越出|窗口已添加"`
Expected: 有「环形电量独立窗口已添加」「环绘制参数」且调节后不再出现「环有越出窗口边界的部分」（±20dp 范围内）

- [ ] **Step 5: 验证重启按钮失败路径**

若设备 su 弹窗拒绝授权，应 Toast "重启失败：未获得 root 权限" 且应用不崩溃。

---

## 风险与边界

- `su -c` 在不同 root 方案（KernelSU/Magisk）语法兼容，`killall` 由 toybox 提供，HyperOS 均具备；su 不可用时降级为 Toast 提示，不崩溃
- 扩窗后窗口覆盖更多状态栏区域，但 `FLAG_NOT_TOUCHABLE + FLAG_NOT_FOCUSABLE` 保证纯绘制层，无输入副作用
- 向上偏移的屏幕顶裁切是物理极限，本次不解决，仅文案说明
