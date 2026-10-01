package com.powerring.hole.hook

import android.app.Application
import com.powerring.hole.core.HookPrefs
import com.powerring.hole.core.ModuleLog
import com.powerring.hole.data.BatteryObserver
import com.powerring.hole.ring.RingState
import com.powerring.hole.ring.RingWindowController
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import de.robv.android.xposed.XposedHelpers

/**
 * SystemUI 进程内的 Hook 总装。
 *
 * 当前载体方案：独立 WindowManager 窗口（type=2006，层带 231000，实测严格高于
 * 灵动岛 DynamicIslandWindow 的 2009/191000，层级胜负不再依赖添加顺序）。
 * 状态栏视图注入与挖孔覆盖层 onDraw
 * 两套实验载体保留在代码库中但不启用，避免多载体重影。
 *
 * 收起通路有两个信号源，都收敛到 RingState 的同一条 collapseProgress 通道：
 * [ImmersiveProbeHook]（状态栏窗口 shown/hidden）与 [IslandVisibilityHook]
 * （灵动岛背景 View 的 setVisibility）。
 */
object SystemUiHooks {

    const val TARGET_PACKAGE = "com.android.systemui"

    @Volatile
    private var installed = false

    fun install(classLoader: ClassLoader) {
        if (installed) return
        installed = true

        // 状态栏图标明暗色（机型支持时）
        try {
            SystemTintHook.install(classLoader)
        } catch (t: Throwable) {
            ModuleLog.e("明暗色 Hook 安装异常", t)
        }

        // 电池图标取色（必须在隐藏之前装：隐藏只是置 GONE，View 仍在收系统回调）
        try {
            BatteryColorHook.install(classLoader)
        } catch (t: Throwable) {
            ModuleLog.e("电池图标取色 Hook 安装异常", t)
        }

        // 电池图标隐藏
        try {
            BatteryHideHook.install(classLoader)
            // 挖孔几何确认通常晚于电池 View attach，就绪后补一次隐藏评估
            RingState.onCutoutResolved = { BatteryHideHook.refreshAll() }
        } catch (t: Throwable) {
            ModuleLog.e("电池图标隐藏 Hook 安装异常", t)
        }

        // 沉浸收起兜底探针（环窗口自身不派发 statusBars insets，见可行性分析报告）
        try {
            ImmersiveProbeHook.install(classLoader)
        } catch (t: Throwable) {
            ModuleLog.e("沉浸探针安装异常", t)
        }

        // 灵动岛显隐探针（驱动"有岛时收起圆环"）
        try {
            IslandVisibilityHook.install(classLoader)
        } catch (t: Throwable) {
            ModuleLog.e("灵动岛显隐探针安装异常", t)
        }

        // Application.onCreate 后拿到 Context：注册电量监听 + 添加环窗口
        XposedHelpers.findAndHookMethod(
            Application::class.java,
            "onCreate",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val app = param.thisObject as Application
                        RingState.attachContext(app)
                        BatteryObserver.start(app)
                        HookPrefs.refresh()
                        RingWindowController.attach(app)
                        ModuleLog.i("SystemUI Application 初始化完成")
                    } catch (t: Throwable) {
                        ModuleLog.e("Application onCreate Hook 异常", t)
                    }
                }
            },
        )
    }
}
