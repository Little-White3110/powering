package com.powerring.hole.hook

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
 * 用户关闭开关后主动交还系统重新计算可见性，保证电量指示永不丢失。
 */
object BatteryHideHook {

    private const val METER_VIEW =
        "com.android.systemui.statusbar.views.MiuiBatteryMeterView"
    private const val CONTAINER_VIEW =
        "com.android.systemui.statusbar.views.MiuiStatusBatteryContainer"

    /** 被本模块强制隐藏过的电池 View，解除时需要恢复 */
    private val forcedViews: MutableSet<View> =
        Collections.newSetFromMap(WeakHashMap())

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
            XposedHelpers.findAndHookMethod(
                containerClass, "setIsHideBattery", java.lang.Boolean::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (RingState.shouldForceHideBattery()) {
                            param.args[0] = java.lang.Boolean.TRUE
                        }
                    }
                },
            )
        }

        // 2) 视图层兜底
        if (meterClass != null) {
            val applyHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    (param.thisObject as? View)?.let { applyHide(it) }
                }
            }
            XposedHelpers.findAndHookMethod(meterClass, "onAttachedToWindow", applyHook)
            XposedHelpers.findAndHookMethod(meterClass, "updateVisibility\$6", applyHook)
        }

        ModuleLog.i("电池图标隐藏 Hook 已安装")
    }

    private fun applyHide(view: View) {
        if (!isInStatusBatteryContainer(view)) return
        if (RingState.shouldForceHideBattery()) {
            forcedViews.add(view)
            if (view.visibility != View.GONE) {
                view.visibility = View.GONE
            }
        } else if (forcedViews.remove(view)) {
            // 开关已关闭：交还系统按其自身逻辑决定显隐，避免强行 VISIBLE 与灵动岛冲突
            restoreSystemVisibility(view)
        }
    }

    /** 判断电池 View 是否位于状态栏电池容器内（控制中心等其他实例不动）。 */
    private fun isInStatusBatteryContainer(start: View): Boolean {
        var node: View? = start
        var depth = 0
        while (node != null && depth < 8) {
            if (node.javaClass.name == CONTAINER_VIEW) return true
            val parent = node.parent
            node = parent as? View
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
