package com.powerring.hole.hook

import android.os.Handler
import android.os.Looper
import android.view.View
import com.powerring.hole.core.ModuleLog
import com.powerring.hole.ring.RingState
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import de.robv.android.xposed.XposedHelpers
import java.util.Collections
import java.util.WeakHashMap

/**
 * 隐藏状态栏原生电池图标。
 *
 * 复用系统自带的隐藏路径：
 * - MiuiStatusBatteryContainer.setIsHideBattery(Boolean)：灵动岛出现时系统自身
 * *   就通过它隐藏电池（见可行性分析报告），这里在环启用期间恒置 true；
 * - MiuiBatteryMeterView 的 onAttachedToWindow / updateVisibility$6：
 *   兜底把状态栏容器内的电池 View 置 GONE。仅处理位于 MiuiStatusBatteryContainer
 *   内的实例，避免误伤控制中心/锁屏等其他电池视图。
 *
 * 安全策略：只有挖孔几何已确认可用（[RingState.cutoutEverResolved]）才隐藏，
 * 且横屏（挖孔换到侧边、环画不出来）时把图标交还系统——环不可见就绝不丢失电量指示。
 * 用户关闭开关、或从横屏回到竖屏时，容器与 View 两层都要重新评估并主动交还。
 */
object BatteryHideHook {

    private const val METER_VIEW =
        "com.android.systemui.statusbar.views.MiuiBatteryMeterView"
    private const val CONTAINER_VIEW =
        "com.android.systemui.statusbar.views.MiuiStatusBatteryContainer"

    /** 被本模块强制隐藏过的电池 View，解除时需要恢复 */
    private val forcedViews: MutableSet<View> =
        Collections.newSetFromMap(WeakHashMap())

    /** 所有已见到的状态栏电池 View，挖孔几何确认后需要重新评估一次 */
    private val knownViews: MutableSet<View> =
        Collections.newSetFromMap(WeakHashMap())

    /**
     * 已见到的 `MiuiStatusBatteryContainer` -> **系统最近一次原始请求**的隐藏值。
     *
     * before-hook 会把 `setIsHideBattery(false)` 改成 `true`，系统自己的
     * `mIsHideBattery` 因此被写成 true；解除强制时必须把这个原始值交还回去，
     * 否则图标不会重新出现（本表就是为这一步服务的）。
     */
    private val containerRequests: MutableMap<View, Boolean> = WeakHashMap()

    @Volatile
    private var containerCheckedLogged = false

    /**
     * 重新评估所有已存在的电池 View / 容器：挖孔几何就绪、或屏幕方向变化后调用。
     *
     * 方向变化时系统不会主动再调 `setIsHideBattery`，容器会一直停在我们改出来的
     * `true` 上，所以解除强制必须显式交还；重入是安全的——回调里
     * [RingState.shouldForceHideBattery] 此刻为 false，before-hook 不再改参。
     */
    fun refreshAll() {
        val views = synchronized(knownViews) { knownViews.toList() }
        val containers = synchronized(containerRequests) { containerRequests.toList() }
        val main = Handler(Looper.getMainLooper())
        containers.forEach { (container, requested) ->
            main.post { releaseContainer(container, requested) }
        }
        views.forEach { v ->
            if (v.isAttachedToWindow) v.post { applyHide(v) }
        }
    }

    /** 把系统最近一次原始请求值交还给容器自身的方法；仍要求隐藏时（如在显示灵动岛）不越权。 */
    private fun releaseContainer(container: View, requested: Boolean) {
        if (RingState.shouldForceHideBattery(container)) return
        if (!container.isAttachedToWindow) return
        try {
            XposedHelpers.callMethod(container, "setIsHideBattery", java.lang.Boolean.valueOf(requested))
            ModuleLog.i("已交还电池容器隐藏状态: requested=$requested")
        } catch (t: Throwable) {
            ModuleLog.e("交还电池容器隐藏状态失败", t)
        }
    }

    fun install(classLoader: ClassLoader) {
        val containerClass = XposedHelpers.findClassIfExists(CONTAINER_VIEW, classLoader)
        val meterClass = XposedHelpers.findClassIfExists(METER_VIEW, classLoader)

        if (containerClass == null && meterClass == null) {
            // 可能是纯 Compose 电池管线（报告 R1），记录后放弃——绝不盲目隐藏
            ModuleLog.e(
                "未找到 MIUI 传统电池视图类，可能为 Compose 电池管线，" +
                    "原生电池图标隐藏未生效（环形电量仍可独立工作）", null,
            )
            return
        }

        // 1) 容器层：系统自身的隐藏开关
        if (containerClass != null) {
            try {
                XposedHelpers.findAndHookMethod(
                    containerClass, "setIsHideBattery", java.lang.Boolean::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            try {
                                val container = param.thisObject as? View
                                val requested = param.args[0] as? Boolean ?: false
                                if (container != null) {
                                    synchronized(containerRequests) {
                                        containerRequests[container] = requested
                                    }
                                }
                                if (RingState.shouldForceHideBattery(container)) {
                                    param.args[0] = java.lang.Boolean.TRUE
                                }
                            } catch (t: Throwable) {
                                // 异常时放行系统原值：宁可短暂露出原生图标，也不能卡住 SystemUI
                                ModuleLog.e("setIsHideBattery 前置处理异常，放行系统值", t)
                            }
                        }
                    },
                )
                ModuleLog.i("电池容器隐藏 Hook 已安装")
            } catch (t: Throwable) {
                ModuleLog.e("Hook setIsHideBattery 失败", t)
            }
        }

        // 2) 视图层兜底（各注册点独立保护，方法名随 R8 版本可能变化）
        if (meterClass != null) {
            val attachHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    synchronized(knownViews) { knownViews.add(view) }
                    // post：等整棵视图树 attach 完成后父链才完整
                    view.post { applyHide(view) }
                    // 立即也试一次（覆盖 post 期间的窗口）
                    applyHide(view)
                }
            }
            val visibilityHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    (param.thisObject as? View)?.let { applyHide(it) }
                }
            }
            for ((methodName, callback) in arrayOf(
                "onAttachedToWindow" to attachHook,
                "updateVisibility\$6" to visibilityHook,
            )) {
                try {
                    XposedHelpers.findAndHookMethod(meterClass, methodName, callback)
                } catch (t: Throwable) {
                    ModuleLog.e("Hook MiuiBatteryMeterView.$methodName 失败", t)
                }
            }
        }
    }

    private fun applyHide(view: View) {
        synchronized(knownViews) { knownViews.add(view) }
        val force = RingState.shouldForceHideBattery(view)
        val inContainer = isInStatusBatteryContainer(view)
        if (!containerCheckedLogged) {
            containerCheckedLogged = true
            ModuleLog.i(
                "电池 View 可见性检查: inContainer=$inContainer force=$force " +
                    "vis=${view.visibility} chain=${parentChain(view)}",
            )
        }
        if (!inContainer) return
        if (force) {
            forcedViews.add(view)
            if (view.visibility != View.GONE) {
                view.visibility = View.GONE
                ModuleLog.i("已隐藏状态栏电池图标")
            }
        } else if (forcedViews.remove(view)) {
            // 开关已关闭：交还系统按其自身逻辑决定显隐，避免强行 VISIBLE 与灵动岛冲突
            restoreSystemVisibility(view)
        }
    }

    /** 输出父链，仅诊断使用。 */
    private fun parentChain(start: View): String {
        val sb = StringBuilder()
        var node: android.view.ViewParent? = start.parent
        var depth = 0
        while (node != null && depth < 15) {
            if (depth > 0) sb.append(" <- ")
            sb.append(node.javaClass.simpleName)
            node = node.parent
            depth++
        }
        return sb.toString()
    }

    /** 判断电池 View 是否位于状态栏电池容器内（控制中心等其他实例不动）。 */
    private fun isInStatusBatteryContainer(start: View): Boolean {
        var node: android.view.ViewParent? = start.parent
        var depth = 0
        while (node != null && depth < 30) {
            if (node.javaClass.name == CONTAINER_VIEW) return true
            node = node.parent
            depth++
        }
        return false
    }

    private fun restoreSystemVisibility(view: View) {
        try {
            // 触发系统自身的可见性收敛方法（R8 后名称为 updateVisibility$6）
            XposedHelpers.callMethod(view, "updateVisibility\$6")
        } catch (t: Throwable) {
            ModuleLog.e("交还系统可见性失败，直接置 VISIBLE", t)
            if (view.visibility == View.GONE) view.visibility = View.VISIBLE
        }
    }
}
