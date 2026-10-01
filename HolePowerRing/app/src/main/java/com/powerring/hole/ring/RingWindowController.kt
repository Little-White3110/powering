package com.powerring.hole.ring

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.WindowInsets
import com.powerring.hole.core.ModuleLog

/**
 * 环形电量的独立窗口宿主。
 *
 * 为什么必须独立窗口：实测灵动岛是 SystemUI 通过 WindowManager 添加的
 * **独立窗口**（dumpsys 标题 DynamicIslandWindow，type=2009，
 * frame 0,0-1200,156，cutoutMode=always），不在 StatusBar 窗口视图树内，
 * 因此向 PhoneStatusBarView 注入 View 永远会被它压住。
 *
 * 本窗口采用与岛相同的 type=2009 / gravity=TOP / cutoutMode=always，
 * 在岛窗口之后创建，同 baseLayer 下后创建者层级更高；
 * 高度跟随 cutout 安全区；FLAG_NOT_TOUCHABLE 使整个窗口不参与触摸，
 * 对状态栏操作零影响。
 */
object RingWindowController {

    /** 与 DynamicIslandWindow 相同的窗口类型（TYPE_KEYGUARD_DIALOG 槽位，MIUI 复用） */
    private const val TYPE_ISLAND_COMPAT = 2009

    /**
     * 窗口高度在 cutout 安全区之外的余量（dp）。
     * 需覆盖最大向下偏移（OFFSET_MAX=20dp）+ 环半径外沿，否则垂直偏移下半环被窗口裁切。
     * 向上偏移受屏幕顶物理限制，扩窗无法解决，由设置页文案说明。
     */
    private const val EXTRA_BOTTOM_DP = 26f

    private var windowManager: WindowManager? = null
    private var appContext: Context? = null
    private var ringView: PowerRingView? = null

    @Volatile
    private var attached = false
    private var retried = false

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
            type = TYPE_ISLAND_COMPAT
            format = PixelFormat.TRANSLUCENT
            width = WindowManager.LayoutParams.MATCH_PARENT
            // 初始给与灵动岛相同的高度（156px），insets 到达后按安全区精确修正；
            // WRAP_CONTENT 会让纯 onDraw 的 View 测量为 0
            height = 156
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            // 不获取焦点、不拦截任何触摸（整个窗口触摸穿透）
            flags = (
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_SPLIT_TOUCH or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                )
            title = "HolePowerRingWindow"
            // 与岛一致：窗口内容延伸到挖孔区域
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }

        try {
            wm.addView(view, params)
            attached = true
            ringView = view
            ModuleLog.i("环形电量独立窗口已添加（type=2009）")
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
}
