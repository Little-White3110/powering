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

    fun install(classLoader: ClassLoader) {
        val baseClass = XposedHelpers.findClassIfExists(BASE_VIEW, classLoader)
        if (baseClass == null) {
            ModuleLog.e("未找到 $BASE_VIEW，环形电量无法安装", null)
            return
        }

        // 挖孔 View 绘制完成后追加电量环
        XposedHelpers.findAndHookMethod(
            baseClass, "onDraw", Canvas::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    val canvas = param.args[0] as? Canvas ?: return
                    RingState.onCutoutDraw(view, canvas)
                }
            },
        )

        XposedHelpers.findAndHookMethod(
            baseClass, "onDetachedFromWindow",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    (param.thisObject as? View)?.let { RingState.detachCutoutView(it) }
                }
            },
        )

        // 挖孔填充色（黑/白）随状态栏 tint 变化
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
        }

        ModuleLog.i("挖孔绘制 Hook 已安装")
    }
}
