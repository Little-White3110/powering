package com.powerring.hole.hook

import android.graphics.Canvas
import android.view.View
import com.powerring.hole.core.ModuleLog
import com.powerring.hole.ring.RingState
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import de.robv.android.xposed.XposedHelpers

/**
 * 方案 A 的核心：Hook 系统挖孔装饰 View。
 *
 * - DisplayCutoutBaseView.onDraw 之后绘制电量环（基类 onDraw 覆盖所有挖孔 View）
 * - onDetachedFromWindow 时注销 View
 * - ScreenDecorations$DisplayCutoutView.setColor 记录当前挖孔填充色（状态栏图标明暗）
 */
object CutoutRingHook {

    private const val BASE_VIEW = "com.android.systemui.DisplayCutoutBaseView"
    private const val CUTOUT_VIEW = "com.android.systemui.ScreenDecorations\$DisplayCutoutView"

    /** onDraw 首次触发日志只打一次 */
    @Volatile
    private var drawLogged = false

    fun install(classLoader: ClassLoader) {
        // 注意：DisplayCutoutBaseView 未重写 onDetachedFromWindow（继承自 View），
        // 不能 Hook；失效 View 的清理依赖 RingState 中的弱引用集合 +
        // 绘制前 isAttachedToWindow 检查，已足够，无需 detach 回调。
        var installed = 0
        val baseClass = XposedHelpers.findClassIfExists(BASE_VIEW, classLoader)
        if (baseClass == null) {
            ModuleLog.e("未找到 $BASE_VIEW，环形电量无法安装", null)
            return
        }

        // 挖孔 View 附加到窗口时注册并主动请求一次重绘，
        // 防止系统挖孔 View 平时不 invalidate 导致环永远没机会首绘
        try {
            XposedHelpers.findAndHookMethod(
                baseClass, "onAttachedToWindow",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val view = param.thisObject as? View ?: return
                        RingState.attachCutoutView(view)
                        view.postInvalidateOnAnimation()
                        ModuleLog.i(
                            "挖孔 View 已附加: ${view.javaClass.name} " +
                                "size=${view.width}x${view.height}",
                        )
                    }
                },
            )
            installed++
        } catch (t: Throwable) {
            ModuleLog.e("Hook DisplayCutoutBaseView.onAttachedToWindow 失败", t)
        }

        // 挖孔 View 绘制完成后追加电量环
        try {
            XposedHelpers.findAndHookMethod(
                baseClass, "onDraw", Canvas::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val view = param.thisObject as? View ?: return
                        val canvas = param.args[0] as? Canvas ?: return
                        if (!drawLogged) {
                            drawLogged = true
                            ModuleLog.i("DisplayCutoutBaseView.onDraw 首次触发: ${view.javaClass.name}")
                        }
                        RingState.onCutoutDraw(view, canvas)
                    }
                },
            )
            installed++
        } catch (t: Throwable) {
            ModuleLog.e("Hook DisplayCutoutBaseView.onDraw 失败", t)
        }

        // 挖孔填充色（黑/白）随状态栏 tint 变化（失败不影响环本体）
        try {
            XposedHelpers.findClassIfExists(CUTOUT_VIEW, classLoader)?.let { cutoutClass ->
                XposedHelpers.findAndHookMethod(
                    cutoutClass, "setColor", Int::class.javaPrimitiveType,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val color = param.args[0] as Int
                            RingState.setTintColor(color)
                        }
                    },
                )
                installed++
            }
        } catch (t: Throwable) {
            ModuleLog.e("Hook DisplayCutoutView.setColor 失败（明暗自适应将使用默认色）", t)
        }

        ModuleLog.i("挖孔绘制 Hook 已安装（$installed/3 个回调成功）")
    }
}
