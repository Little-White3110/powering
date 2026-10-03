package com.powerring.hole.ring

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.SurfaceControl
import android.view.View
import android.view.WindowManager
import android.view.WindowInsets
import com.powerring.hole.core.HookPrefs
import com.powerring.hole.core.ModuleLog
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * 环形电量的独立窗口宿主。
 *
 * 为什么必须独立窗口：实测灵动岛是 SystemUI 通过 WindowManager 添加的
 * **独立窗口**（dumpsys 标题 DynamicIslandWindow，type=2009，
 * frame 0,0-1200,156，cutoutMode=always），不在 StatusBar 窗口视图树内，
 * 因此向 PhoneStatusBarView 注入 View 永远会被它压住。
 *
 * 本窗口采用 type=2006（TYPE_SYSTEM_ALERT，本机层带 231000）/ gravity=TOP /
 * cutoutMode=always —— 层带严格高于灵动岛的 191000，层级胜负不依赖添加顺序；
 * 高度跟随 cutout 安全区；FLAG_NOT_TOUCHABLE 使整个窗口不参与触摸，
 * 对状态栏操作零影响。
 *
 * 两个必须同时成立的窗口属性：
 * - **受信覆盖层**（见 `applyTrustedOverlayFlag`）。缺它会被 MIUI 判为点按劫持源并
 *   **全局**拦截点击，真机已验证（报告 §19）。
 * - **不带 `FLAG_SECURE`**（见 `RING_WINDOW_FLAGS`）。它挡不住本机特权截图
 *   （报告 §17.2），留着只会在 dumpsys 里多一个干扰位——注意它**不是**劫持拦截的
 *   成因，去掉后拦截照旧，别再往这个方向查。
 */
object RingWindowController {

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

    /**
     * 环窗口的 flags。
     *
     * 语义：不获取焦点、不拦截任何触摸（整个窗口触摸穿透），并允许铺到状态栏/挖孔区域。
     *
     * **`FLAG_NOT_TOUCHABLE` 不等于「不会被判劫持」**（报告 §19，2026-10-03 真机验证）：
     * 带着这个位、窗口照样被 MIUI 计入点按劫持判定。真正决定成败的是
     * `TRUSTED_OVERLAY` 私有标志，由 `applyTrustedOverlayFlag` 设置，这里给不出。
     *
     * **也不要加 `FLAG_SECURE`**：它挡不住本机特权截图（报告 §17.2），截图隐藏
     * 全靠下面的 SF 层机制。它曾被怀疑是劫持拦截的成因，实测证伪——去掉后拦截照旧。
     *
     * 抽成 internal 常量是为了让 JVM 单测能断言「不含 SECURE」——
     * `LayoutParams.FLAG_SECURE` 是编译期内联的 int 常量，不需要 Android 运行时。
     */
    internal const val RING_WINDOW_FLAGS =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_SPLIT_TOUCH or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

    /**
     * 本机 framework 实证（`work/dump_class.py device-fw android.view.WindowManager$LayoutParams`）：
     * 受信覆盖层的私有标志位与设置入口。真机反射确认 `PRIVATE_FLAG_TRUSTED_OVERLAY = 0x20000000`。
     * AOSP 新版的 `SYSTEM_FLAG_TRUSTED_OVERLAY` 在本机**不存在**（实测 NoSuchFieldException），别照搬。
     */
    private const val PRIVATE_FLAG_TRUSTED_OVERLAY = 0x20000000
    private const val PRIVATE_FLAGS_FIELD = "privateFlags"
    private const val SET_TRUSTED_OVERLAY_METHOD = "setTrustedOverlay"

    /**
     * 窗口高度在 cutout 安全区之外的余量（dp）。
     *
     * 需覆盖：最大向下偏移（OFFSET_MAX=20dp）+ 环半径外沿；并留出足够余量，
     * 避免大半径/大偏移态下半环被窗口裁切。62dp 给足安全空间。
     *
     * 向上偏移受屏幕顶物理限制，扩窗无法解决，由设置页文案说明。
     */
    private const val EXTRA_BOTTOM_DP = 62f

    /** 采集全部结束后恢复环窗口的延时：给截图保存与预览动画留出时间 */
    private const val RESTORE_DELAY_MS = 1500L

    private var windowManager: WindowManager? = null
    private var appContext: Context? = null
    private var ringView: PowerRingView? = null

    @Volatile
    private var attached = false
    private var retried = false

    // ---- 截图采集期 SF 层隐藏 ----
    // 环窗口不带 SECURE 位（原因见 RING_WINDOW_FLAGS），截图隐藏由 SurfaceFlinger
    // 层机制承担：ScreenshotCaptureHook 在采集执行前提交临时隐藏事务，事务与采集
    // 请求按序进入 SF 主线程队列，能赶在合成之前生效。

    private val captureCounter = CaptureHideCounter()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 已提交 hide、等待恢复的窗口 SurfaceControl */
    private var hiddenControl: SurfaceControl? = null

    /** 已调度、尚未执行的恢复任务（新采集开始时需要撤销它） */
    private var pendingRestore: Runnable? = null

    /** 反射句柄只解析一次；字段名取自本机 framework.jar 实证（work/dump_class.py） */
    private val getViewRootImpl: Method? by lazy {
        runCatching { View::class.java.getMethod("getViewRootImpl") }
            .onFailure { ModuleLog.e("反射 View.getViewRootImpl 失败", it) }
            .getOrNull()
    }
    private val surfaceControlField: Field? by lazy {
        runCatching {
            Class.forName("android.view.ViewRootImpl")
                .getDeclaredField("mSurfaceControl").apply { isAccessible = true }
        }.onFailure { ModuleLog.e("反射 ViewRootImpl.mSurfaceControl 失败", it) }
            .getOrNull()
    }

    /**
     * 隐藏 API：SF 层「采集排除」标志。与 FLAG_SECURE 不同——本机已实证
     * 系统截图以特权身份连安全层一起捕获，FLAG_SECURE 挡不住；而
     * skipScreenshot 是 layer 级的硬排除（屏幕正常合成、任何采集都拿不到，
     * 且覆盖录屏/投屏）。方法签名取自本机 framework.jar 实证。
     */
    private val setSkipScreenshot: Method? by lazy {
        runCatching {
            Class.forName("android.view.SurfaceControl\$Transaction")
                .getMethod(
                    "setSkipScreenshot",
                    SurfaceControl::class.java,
                    Boolean::class.javaPrimitiveType,
                )
        }.onFailure { ModuleLog.e("反射 Transaction.setSkipScreenshot 失败", it) }
            .getOrNull()
    }

    /** 最近一次 setSkipScreenshot 应用到的 layer 身份与开关值（用于每帧去重） */
    private var appliedSkipSc: SurfaceControl? = null
    private var appliedSkipTarget: Int = -1

    /**
     * 把环窗口标记为「受信覆盖层」，消除厂商的点按劫持全局拦截（报告 §19）。
     *
     * 为什么要它：本机 InputDispatcher 窗口列表里，`StatusBar`/`NavigationBar0`/
     * `NotificationShade`/灵动岛/同为 2006 的 `AntiMistakeTouchView` **全部带
     * `TRUSTED_OVERLAY`，只有环窗口没有**；MIUI 据此把点按判为劫持并**全局**拦截
     * （实测触摸能送达，随后以「前台被遮」名义弹 toast）。`FLAG_SECURE` 已证伪——
     * 去掉后拦截照旧。
     *
     * 本机 framework 实证（2026-10-03，`work/dump_class.py device-fw` + 真机日志）：
     * 常量名是 **`PRIVATE_FLAG_TRUSTED_OVERLAY = 0x20000000`**，AOSP 新版的
     * `SYSTEM_FLAG_TRUSTED_OVERLAY` 在本机**不存在**（实测 NoSuchFieldException）；
     * `LayoutParams` 自带公开方法 `setTrustedOverlay()`，优先走它——真机确认调用后
     * `dumpsys window` 出现 `pfl=TRUSTED_OVERLAY`、`dumpsys input` 的 `inputConfig`
     * 同步带上该位。反射常量与字段仅作旧 ROM 无此方法时的回落，全程包 try/catch，
     * 取不到就维持现状，绝不抛异常到 SystemUI。
     */
    private fun applyTrustedOverlayFlag(params: WindowManager.LayoutParams) {
        try {
            val cls = WindowManager.LayoutParams::class.java
            val field = cls.getField(PRIVATE_FLAGS_FIELD)
            val setter = runCatching { cls.getMethod(SET_TRUSTED_OVERLAY_METHOD) }.getOrNull()
            val route = if (setter != null) {
                setter.invoke(params)
                "setTrustedOverlay()"
            } else {
                field.setInt(params, field.getInt(params) or PRIVATE_FLAG_TRUSTED_OVERLAY)
                "privateFlags"
            }
            val applied = field.getInt(params) and PRIVATE_FLAG_TRUSTED_OVERLAY != 0
            ModuleLog.i(
                "环窗口受信覆盖层标志已${if (applied) "设置" else "未生效"}（经 $route）",
            )
        } catch (t: Throwable) {
            ModuleLog.e("设置 TRUSTED_OVERLAY 失败，环窗口维持不受信（点按劫持拦截可能照旧）", t)
        }
    }

    fun attach(context: Context) {
        if (attached) return
        appContext = context.applicationContext
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        var firstDispatchLogged = false
        val view = PowerRingView(context.applicationContext).apply {
            // 收到 insets 后按挖孔安全区高度调整窗口高度
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
                // 本机实测：环窗口(2009)覆盖状态栏区域，系统不派发 statusBars insets，
                // statusTop 恒为 0，不能作为收起信号。收起检测改由 ImmersiveProbeHook
                // （Hook StatusBarWindowStateController 的 setWindowState 回调）驱动，
                // 此处保持禁用以防双写抖动。statusTop 计算与首次日志保留，方便换机型复查。
                v.postInvalidateOnAnimation()
                insets
            }
        }
        ringView = view

        val params = WindowManager.LayoutParams().apply {
            type = TYPE_RING_WINDOW
            format = PixelFormat.TRANSLUCENT
            width = WindowManager.LayoutParams.MATCH_PARENT
            // 初始给与灵动岛相同的高度（156px），insets 到达后按安全区精确修正；
            // WRAP_CONTENT 会让纯 onDraw 的 View 测量为 0
            height = 156
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            flags = RING_WINDOW_FLAGS
            title = "HolePowerRingWindow"
            // 与岛一致：窗口内容延伸到挖孔区域
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            applyTrustedOverlayFlag(this)
        }

        try {
            wm.addView(view, params)
            attached = true
            ringView = view
            ModuleLog.i(
                "环形电量独立窗口已添加（type=$TYPE_RING_WINDOW，层带 231000 > 灵动岛的 191000，" +
                    "w=${params.width} h=${params.height} flags=0x${Integer.toHexString(params.flags)}）",
            )
        } catch (t: Throwable) {
            ModuleLog.e("添加环窗口失败（首次），5 秒后重试一次", t)
            if (!retried) {
                retried = true
                // view 尚未 attach，不能用 view.post，走主线程 Handler
                Handler(Looper.getMainLooper()).postDelayed({
                    appContext?.let { runCatching { attach(it) } }
                }, 5000L)
            }
        }
    }

    /**
     * 截图采集开始（由 [com.powerring.hole.hook.ScreenshotCaptureHook] 的
     * before 回调驱动，任意线程）。计数首位时提交隐藏事务；开关关闭或
     * SurfaceControl 取不到时降级跳过。并发容忍：两次采集的 begin/end 在
     * 不同线程交错时，最坏结果是某一次少藏或多显一帧，不产生崩溃——
     * 采集由用户操作触发，频率低。
     */
    fun beginScreenshotCapture() {
        if (!captureCounter.onBegin()) return
        pendingRestore?.let { mainHandler.removeCallbacks(it) }
        pendingRestore = null
        if (!HookPrefs.get().hideOnScreenshot) return
        val sc = currentWindowSurfaceControl()
        if (sc == null || !sc.isValid) {
            ModuleLog.e("截图隐藏：窗口 SurfaceControl 不可用，本次不隐藏", null)
            return
        }
        hiddenControl = sc
        try {
            // setVisibility(false) 置 layer 隐藏标志：该 layer 完全不参与合成，
            // 任何权限的采集（含本机特权截图）都拿不到它
            SurfaceControl.Transaction().setVisibility(sc, false).apply()
            ModuleLog.i("截图采集开始：SF 层隐藏环窗口")
        } catch (t: Throwable) {
            ModuleLog.e("截图隐藏事务提交失败", t)
        }
    }

    /**
     * 截图采集结束（after 回调，采集抛异常时同样会走到）。计数归零后延时恢复，
     * 给截图保存与预览动画留出时间，避免环在截图动画期间闪回。
     */
    fun endScreenshotCapture() {
        if (!captureCounter.onEnd()) return
        val restore = Runnable {
            pendingRestore = null
            val sc = hiddenControl
            hiddenControl = null
            if (sc != null) {
                try {
                    SurfaceControl.Transaction().setVisibility(sc, true).apply()
                    ModuleLog.i("截图采集结束：环窗口已恢复")
                } catch (t: Throwable) {
                    ModuleLog.e("环窗口恢复事务提交失败", t)
                }
            }
        }
        pendingRestore = restore
        mainHandler.postDelayed(restore, RESTORE_DELAY_MS)
    }

    /** 环窗口根 SurfaceControl：View.getViewRootImpl() → ViewRootImpl.mSurfaceControl。 */
    private fun currentWindowSurfaceControl(): SurfaceControl? {
        val view = ringView ?: return null
        return try {
            val root = getViewRootImpl?.invoke(view) ?: return null
            surfaceControlField?.get(root) as? SurfaceControl
        } catch (t: Throwable) {
            ModuleLog.e("读取环窗口 SurfaceControl 失败", t)
            null
        }
    }

    /**
     * 自愈式同步 SF 层「采集排除」标志（主线程，每帧经 RingState.onCutoutFrame 调用）。
     *
     * 灭屏/旋转会重建窗口 surface、layer 身份随之更换，旧 handle 上的标志作废；
     * 每帧按「layer 身份 + 开关值」去重，身份变了就补打一次。未变化的帧只花
     * 两次反射字段读取，绝不在这里做重活。
     */
    fun syncScreenshotExclusion() {
        if (!attached) return
        val target = HookPrefs.get().hideOnScreenshot
        val sc = currentWindowSurfaceControl()
        if (appliedSkipTarget == (if (target) 1 else 0) && appliedSkipSc === sc) return
        if (sc == null || !sc.isValid) return
        val method = setSkipScreenshot
        try {
            if (method != null) {
                SurfaceControl.Transaction().also { method.invoke(it, sc, target) }.apply()
                ModuleLog.i("环窗口 setSkipScreenshot=$target（采集排除已刷新）")
            }
            // 反射不可用时也记状态，避免每帧重试打日志
            appliedSkipTarget = if (target) 1 else 0
            appliedSkipSc = sc
        } catch (t: Throwable) {
            ModuleLog.e("setSkipScreenshot 调用失败", t)
        }
    }
}
