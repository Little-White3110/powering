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
 * （MiuiBatteryMeterView.updateIslandShowing，宿主侧信号）。
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
            RingState.onOrientationChanged = { BatteryHideHook.refreshAll() }
            // v1.3.2：环完全收起 / 完全回场的那一刻，重新评估电池图标显隐。
            // 现在电池要等环彻底收干净才允许回来（避免「环与电池同框」），
            // 所以收起动画跑完的这一刻必须主动踢一脚，否则图标会一直不回来。
            RingState.onCollapseSettled = { BatteryHideHook.refreshAll() }
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
        // 截图采集点探针（FLAG_SECURE 被特权截图绕过，改为采集前 SF 层临时隐藏）
        try {
            ScreenshotCaptureHook.install(classLoader)
        } catch (t: Throwable) {
            ModuleLog.e("截图采集 Hook 安装异常", t)
        }

        // 面板拉起收起：合并布尔信号驱动 + 控制中心/通知侧 fraction 取证
        try {
            ShadeCollapseHook.install(classLoader)
        } catch (t: Throwable) {
            ModuleLog.e("面板收起信号安装异常", t)
        }
        }

        // 点击挖孔显示电量（旁观状态栏触摸，不影响系统行为）
        try {
            CutoutTapHook.install(classLoader)
        } catch (t: Throwable) {
            ModuleLog.e("点击显示电量 Hook 安装异常", t)
        }

        // 消息提醒闪烁数据源（通知栏可清除通知存在性）
        try {
            NotificationBlinkHook.install(classLoader)
        } catch (t: Throwable) {
            ModuleLog.e("消息提醒闪烁 Hook 安装异常", t)
        }

        // 通知中心（左侧下拉）展开探针：面板展开时收起圆环，并把状态栏原生电池
        // 图标交还系统（面板里显示正常电量），收起后自动恢复
        try {
            ShadeProbeHook.install(classLoader)
            RingState.onShadeExpandedChanged = { BatteryHideHook.refreshAll() }
        } catch (t: Throwable) {
            ModuleLog.e("通知中心探针安装异常", t)
        }

        // 控制中心（右侧 QS 面板）展开探针：HyperOS 的通知中心与控制中心是两块
        // 独立面板，信号源也不同，必须各自挂一路（v1.0.0 新增）
        try {
            ControlCenterProbeHook.install(classLoader)
        } catch (t: Throwable) {
            ModuleLog.e("控制中心探针安装异常", t)
        }

        // 屏幕点亮 / 解锁完成时，让三条收起通路各按系统实时值复核一次：
        // 锁屏、解锁过渡、息屏都可能让某条通路留下一个「没有人清回去」的假值，
        // 主动复核是治「解锁进桌面后环不见了」这类残留的最后一道网。
        RingState.onReconcileRequested = {
            runCatching { ShadeProbeHook.reconcile() }
            runCatching { ControlCenterProbeHook.reconcile() }
            runCatching { ImmersiveProbeHook.reconcile() }
            runCatching { IslandVisibilityHook.reconcile() }
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
                        RingWindowController.attach(app)
                        RingState.onCutoutFrame = { RingWindowController.syncScreenshotExclusion() }
                        RingState.onShadeCollapseToggled = { ShadeCollapseHook.syncFromSystem() }
                        ModuleLog.i("SystemUI Application 初始化完成")
                    } catch (t: Throwable) {
                        ModuleLog.e("Application onCreate Hook 异常", t)
                    }
                }
            },
        )
    }
}
