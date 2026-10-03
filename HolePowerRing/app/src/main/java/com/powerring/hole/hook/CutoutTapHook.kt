package com.powerring.hole.hook

import android.view.MotionEvent
import android.view.View
import com.powerring.hole.core.ModuleLog
import com.powerring.hole.ring.CutoutGeometry
import com.powerring.hole.ring.RingState
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers

/**
 * 「点击挖孔显示电量」的探针（v0.2 新增）。
 *
 * 为什么不直接在环窗口上收触摸：环窗口是 FLAG_NOT_TOUCHABLE 的硬穿透设计
 * （见 RingWindowController 文件头），改成可触摸会挡住整条状态栏的点击；
 * 而挖孔区域内本来就没有任何可交互元素，所以改为**旁观**状态栏
 * PhoneStatusBarView 的触摸事件——只观察、不改变系统行为，风险最低。
 *
 * 命中判定：按下与抬起间隔 ≤ 350ms、位移 ≤ 24dp（tap），且抬起点距
 * 挖孔圆心 ≤ 环外半径 + 10dp 余量。命中即调用 [RingState.flashPercent]，
 * 由 RingRenderer 在挖孔正下方画 2.6 秒的百分比数字（末段 400ms 淡出）。
 *
 * 兼容性：PhoneStatusBarView 类名/方法在个别 HyperOS 版本可能不同；
 * 安装失败只影响本功能，圆环本体不受影响（调用方已 try/catch）。
 */
object CutoutTapHook {

    private const val TARGET_VIEW =
        "com.android.systemui.statusbar.phone.PhoneStatusBarView"

    private const val TAP_TIMEOUT_MS = 350L
    private const val TAP_SLOP_DP = 24f
    private const val HIT_MARGIN_DP = 10f

    fun install(classLoader: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                TARGET_VIEW,
                classLoader,
                "onTouchEvent",
                MotionEvent::class.java,
                object : XC_MethodHook() {
                    private var downAt = 0L
                    private var downX = 0f
                    private var downY = 0f
                    private var downDensity = 3f

                    override fun afterHookedMethod(param: MethodHookParam) {
                        val event = param.args[0] as? MotionEvent ?: return
                        val view = param.thisObject as? View ?: return
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                downAt = event.eventTime
                                downX = event.rawX
                                downY = event.rawY
                                downDensity = view.resources.displayMetrics.density
                            }

                            MotionEvent.ACTION_UP -> {
                                if (downAt == 0L) return
                                val dt = event.eventTime - downAt
                                val dx = event.rawX - downX
                                val dy = event.rawY - downY
                                downAt = 0L
                                if (dt > TAP_TIMEOUT_MS) return
                                if (kotlin.math.hypot(dx, dy) > TAP_SLOP_DP * downDensity) return
                                if (!hitCutout(event.rawX, event.rawY)) return
                                if (RingState.config.tapShowPercent) {
                                    RingState.flashPercent()
                                }
                            }
                        }
                    }
                },
            )
            ModuleLog.i("CutoutTapHook 安装完成（点击挖孔显示电量）")
        } catch (t: Throwable) {
            ModuleLog.e("CutoutTapHook 安装失败（点击显示电量不可用）", t)
        }
    }

    /** 屏幕坐标是否落在挖孔圆心附近（以环 View 的几何换算到屏幕坐标）。 */
    private fun hitCutout(screenX: Float, screenY: Float): Boolean {
        val view = RingState.firstAttachedRingView() ?: return false
        val hole = runCatching { CutoutGeometry.resolve(view, 0f) }.getOrNull() ?: return false
        val loc = IntArray(2)
        runCatching { view.getLocationOnScreen(loc) }
        val cx = loc[0] + hole.cx
        val cy = loc[1] + hole.cy
        val maxR = hole.holeRadius + HIT_MARGIN_DP * view.resources.displayMetrics.density
        val dist = kotlin.math.hypot(screenX - cx, screenY - cy)
        return dist <= maxR
    }
}
