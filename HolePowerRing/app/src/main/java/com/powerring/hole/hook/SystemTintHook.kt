package com.powerring.hole.hook

import com.powerring.hole.core.ModuleLog
import com.powerring.hole.ring.RingState
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import de.robv.android.xposed.XposedHelpers

/**
 * 感知挖孔/状态栏图标的明暗色。
 *
 * 仅在系统启用挖孔填充（DisplayCutoutView 存在）的机型上生效；
 * 屏下孔机型该类不存在，静默跳过，环使用默认浅色令牌。
 */
object SystemTintHook {

    private const val CUTOUT_VIEW = "com.android.systemui.ScreenDecorations\$DisplayCutoutView"

    fun install(classLoader: ClassLoader) {
        val cutoutClass = XposedHelpers.findClassIfExists(CUTOUT_VIEW, classLoader) ?: return
        try {
            XposedHelpers.findAndHookMethod(
                cutoutClass, "setColor", Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        RingState.setTintColor(param.args[0] as Int)
                    }
                },
            )
            ModuleLog.i("状态栏明暗色 Hook 已安装")
        } catch (t: Throwable) {
            ModuleLog.e("状态栏明暗色 Hook 安装失败（将使用默认色）", t)
        }
    }
}
