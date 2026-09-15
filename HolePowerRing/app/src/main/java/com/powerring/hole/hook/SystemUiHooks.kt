package com.powerring.hole.hook

import android.app.Application
import com.powerring.hole.core.ModuleLog
import com.powerring.hole.data.BatteryObserver
import com.powerring.hole.ring.RingState
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import de.robv.android.xposed.XposedHelpers

/**
 * SystemUI 进程内的 Hook 总装。
 *
 * 作用域只有 com.android.systemui，直接 Hook 框架类 Application.onCreate
 * 拿到 Context 后再安装各功能 Hook（此时主线程 Looper 已就绪）。
 */
object SystemUiHooks {

    const val TARGET_PACKAGE = "com.android.systemui"

    @Volatile
    private var installed = false

    fun install(classLoader: ClassLoader) {
        if (installed) return
        installed = true

        // 先装功能 Hook（不依赖 Context 的部分），确保不遗漏早期创建的 View
        CutoutRingHook.install(classLoader)
        BatteryHideHook.install(classLoader)

        // Application.onCreate 后启动电量监听与上下文初始化
        XposedHelpers.findAndHookMethod(
            Application::class.java,
            "onCreate",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val app = param.thisObject as Application
                        RingState.attachContext(app)
                        BatteryObserver.start(app)
                        ModuleLog.i("SystemUI Application 初始化完成")
                    } catch (t: Throwable) {
                        ModuleLog.e("Application onCreate Hook 异常", t)
                    }
                }
            },
        )
    }
}
