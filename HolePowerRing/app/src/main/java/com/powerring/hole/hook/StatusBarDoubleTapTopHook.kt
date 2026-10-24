package com.powerring.hole.hook

import android.view.MotionEvent
import android.view.View
import com.powerring.hole.core.ModuleLog
import com.powerring.hole.ring.RingConfig
import com.powerring.hole.ring.RingState
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers

/**
 * 状态栏双击回到顶部（v1.3.x 新增，可选功能）。
 *
 * 系统「单击状态栏 → 列表回顶」的触发链（本机逆向，见可行性分析报告 §21）：
 * PhoneStatusBarView.onTouchEvent → StatusBarLongPressGestureDetector 的
 * GestureDetector → 内部监听器 onSingleTapConfirmed / onDoubleTapEvent →
 * com.miui.systemui.statusbar.StatusBarClickTool.invokeInputManager(Z) →
 * MiuiInputManager 反射 scrollTop（自带防抖、灵动岛显隐、disable 守卫）。
 *
 * 单击回顶与「点击挖孔显示电量」在挖孔区域天然撞车（CutoutTapHook 旁观同一
 * onTouchEvent）。本 Hook 开启后：
 *  1) 吞掉系统监听器的 onSingleTapConfirmed / onDoubleTapEvent 两条回调
 *     （beforeHookedMethod 置 result=false，原生单击/系统 300ms 双击都不再回顶）；
 *  2) 在自己的触摸旁观里按可配置间隔（默认 800ms）判双击，命中后反射调
 *     clickTool.invokeInputManager(true)——走系统原生「双击」通路，守卫全保留。
 *
 * 拦截 Hook 常驻安装、回调内按配置热判开关；找不到内部类时只降级本功能
 * （保持系统原生行为），绝不影响圆环本体。
 */
object StatusBarDoubleTapTopHook {

    private const val STATUS_BAR_VIEW =
        "com.android.systemui.statusbar.phone.PhoneStatusBarView"
    private const val GESTURE_LISTENER =
        "com.android.systemui.statusbar.gesture.StatusBarLongPressGestureDetector\$gestureDetector\$1"
    private const val FIELD_DETECTOR = "mStatusBarLongPressGestureDetector"
    private const val FIELD_CLICK_TOOL = "clickTool"

    // 与 CutoutTapHook 相同的「合格点按」口径
    private const val TAP_TIMEOUT_MS = 350L
    private const val TAP_SLOP_DP = 24f

    fun install(classLoader: ClassLoader) {
        blockSystemTapToTop(classLoader)
        detectDoubleTap(classLoader)
    }

    /** 吞掉系统手势监听器的单击/双击回顶回调（开关开时）。 */
    private fun blockSystemTapToTop(classLoader: ClassLoader) {
        try {
            val listenerCls = XposedHelpers.findClass(GESTURE_LISTENER, classLoader)
            val blocker = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        if (!RingState.config.statusBarDoubleTapTop) return
                        param.result = false
                    } catch (t: Throwable) {
                        ModuleLog.e("双击回顶拦截回调异常", t)
                    }
                }
            }
            XposedHelpers.findAndHookMethod(
                listenerCls, "onSingleTapConfirmed", MotionEvent::class.java, blocker,
            )
            XposedHelpers.findAndHookMethod(
                listenerCls, "onDoubleTapEvent", MotionEvent::class.java, blocker,
            )
            ModuleLog.i("StatusBarDoubleTapTopHook 系统单击回顶已接管")
        } catch (t: Throwable) {
            ModuleLog.e("StatusBarDoubleTapTopHook 拦截安装失败（保持系统原生行为）", t)
        }
    }

    /** 旁观 PhoneStatusBarView.onTouchEvent，按可配置间隔判双击并派发回顶。 */
    private fun detectDoubleTap(classLoader: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                STATUS_BAR_VIEW,
                classLoader,
                "onTouchEvent",
                MotionEvent::class.java,
                object : XC_MethodHook() {
                    private var downAt = 0L
                    private var downX = 0f
                    private var downY = 0f
                    private var downDensity = 3f
                    private var gate: DoubleTapGate? = null

                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
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
                                    if (!RingState.config.statusBarDoubleTapTop) return
                                    val gate = currentGate(downDensity) ?: return
                                    if (!gate.onTap(event.eventTime, event.rawX, event.rawY)) return
                                    dispatchScrollToTop(view)
                                }
                            }
                        } catch (t: Throwable) {
                            ModuleLog.e("双击回顶判定异常", t)
                        }
                    }

                    /** 间隔/密度变化（配置热更新、转屏）时重建状态机。 */
                    private fun currentGate(density: Float): DoubleTapGate? {
                        val interval = RingState.config.statusBarDoubleTapIntervalMs
                            .coerceIn(
                                RingConfig.DOUBLE_TAP_INTERVAL_MIN,
                                RingConfig.DOUBLE_TAP_INTERVAL_MAX,
                            ).toLong()
                        val slop = TAP_SLOP_DP * density
                        val g = gate
                        if (g != null && g.intervalMs == interval && g.slopPx == slop) return g
                        return DoubleTapGate(interval, slop).also { gate = it }
                    }
                },
            )
            ModuleLog.i("StatusBarDoubleTapTopHook 双击探测已安装")
        } catch (t: Throwable) {
            ModuleLog.e("StatusBarDoubleTapTopHook 双击探测安装失败", t)
        }
    }

    /** 反射取 clickTool 并走系统原生「双击」通路派发回顶。 */
    private fun dispatchScrollToTop(statusBarView: View) {
        try {
            val detector = XposedHelpers.getObjectField(statusBarView, FIELD_DETECTOR) ?: return
            val clickTool = XposedHelpers.getObjectField(detector, FIELD_CLICK_TOOL) ?: return
            // 传 true = 系统「双击」通路：复用防抖 / 灵动岛显隐 / disable 守卫
            XposedHelpers.callMethod(clickTool, "invokeInputManager", java.lang.Boolean.TRUE)
            ModuleLog.i("双击状态栏 → 触发回到顶部")
        } catch (t: Throwable) {
            ModuleLog.e("触发回到顶部失败", t)
        }
    }
}
