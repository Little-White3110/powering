package com.powerring.hole.hook

import android.view.View
import android.view.ViewGroup
import com.powerring.hole.core.ModuleLog
import com.powerring.hole.ring.PowerRingView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import de.robv.android.xposed.XposedHelpers

/**
 * 方案 C：向状态栏根 View 注入 [PowerRingView]。
 *
 * 触发条件：当系统未启用挖孔填充覆盖层（方案 A 的 DisplayCutoutView 不创建）时，
 * 这是本模块的主载体。状态栏窗口 layoutInDisplayCutoutMode=always，覆盖挖孔。
 *
 * 同时 Hook 基类与 MIUI 子类的 onAttachedToWindow：
 * MIUI 子类重写了该方法，super 调用时机不受我们控制，两处都挂、注入幂等。
 */
object StatusBarInjectHook {

    private const val BASE = "com.android.systemui.statusbar.phone.PhoneStatusBarView"
    private const val MIUI = "com.android.systemui.statusbar.phone.MiuiPhoneStatusBarView"

    fun install(classLoader: ClassLoader) {
        val injectCallback = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val root = param.thisObject as? ViewGroup ?: return
                    // post：等根 View 完成首次布局，rootWindowInsets 就绪
                    root.post {
                        try {
                            PowerRingView.inject(root)
                        } catch (t: Throwable) {
                            ModuleLog.e("注入 PowerRingView 失败", t)
                        }
                    }
                } catch (t: Throwable) {
                    ModuleLog.e("状态栏 attach 回调异常", t)
                }
            }
        }

        var ok = 0
        XposedHelpers.findClassIfExists(BASE, classLoader)?.let { cls ->
            try {
                XposedHelpers.findAndHookMethod(cls, "onAttachedToWindow", injectCallback)
                ok++
            } catch (t: Throwable) {
                ModuleLog.e("Hook PhoneStatusBarView.onAttachedToWindow 失败", t)
            }
        }
        XposedHelpers.findClassIfExists(MIUI, classLoader)?.let { cls ->
            try {
                XposedHelpers.findAndHookMethod(cls, "onAttachedToWindow", injectCallback)
                ok++
            } catch (t: Throwable) {
                ModuleLog.e("Hook MiuiPhoneStatusBarView.onAttachedToWindow 失败", t)
            }
        }
        ModuleLog.i("状态栏注入 Hook 已安装（$ok 个挂载点）")
    }
}
