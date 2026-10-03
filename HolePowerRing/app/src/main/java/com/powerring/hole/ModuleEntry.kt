package com.powerring.hole

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.powerring.hole.core.ModuleLog
import com.powerring.hole.hook.SystemUiHooks

/**
 * LSPosed 模块入口（传统 Xposed API）。
 *
 * 作用域只有 com.android.systemui（见 res/values/arrays.xml）。
 * v1.2.0 起移除了息屏显示（com.miui.aod）作用域——息屏提醒在本机始终无效，
 * 按用户要求整块下线。
 */
class ModuleEntry : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != SystemUiHooks.TARGET_PACKAGE) return
        try {
            ModuleLog.i("模块已加载到 ${lpparam.packageName} (${lpparam.processName})")
            SystemUiHooks.install(lpparam.classLoader)
        } catch (t: Throwable) {
            // 任何异常都不能拖垮宿主进程
            ModuleLog.e("模块初始化失败", t)
            XposedBridge.log(t)
        }
    }
}
