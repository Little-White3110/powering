package com.powerring.hole.hook

import android.view.View
import android.view.ViewParent
import com.powerring.hole.core.ModuleLog
import com.powerring.hole.ring.RingState
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import de.robv.android.xposed.XposedHelpers

/**
 * 灵动岛显隐探针：驱动「有岛时收起圆环」。**宿主侧实现，不碰插件 ClassLoader。**
 *
 * ## 为什么必须留在宿主侧（2026-10-01 事故记录，别改回去）
 *
 * 前两版都挂在**插件**（miui.systemui.plugin）的类上，靠 Hook `ClassLoader.loadClass`
 * 等插件类加载出来再下 Hook。第二版在 loadClass 回调里做了两件重活：
 * `cls.declaredMethods`（强制解析方法签名，进而触发插件内被混淆的协程类型 `M0/e`
 * 的类加载）**并当场安装 Hook（触发 ART deoptimize / suspend-all）**。
 * 结果：**SystemUI 启动期 ANR 死锁**（`ANR in com.android.systemui /
 * failed to complete startup`，反复重启），手机界面直接卡死。
 *
 * 教训（AGENTS.md 纪律的延伸）：**永远不要在 `ClassLoader.loadClass` 的回调里
 * 做反射枚举或安装 Hook**。类加载临界区里触发二次类加载 + 让 ART 挂起全部线程，
 * 是稳定的死锁配方。本项目对灵动岛的信息获取一律走宿主侧类。
 *
 * ## 采用的信号源
 *
 * `com.android.systemui.statusbar.views.MiuiBatteryMeterView.updateIslandShowing(ZZZ)V`：
 *
 * - **宿主侧类**，与 BatteryHideHook 已稳定 Hook 的 `onDarkChanged` 同一个类，
 *   用宿主 ClassLoader 直接 `findClassIfExists` 拿到，无需任何 loadClass 拦截；
 * - `work/method_refs.py` 转储确认该方法**写入 `mIsIslandShowing` 字段**，
 *   且是 update 型方法（带新状态被调用，**两个方向都会到达**，不会重演
 *   "只藏不显"）；
 * - 只 Hook 它自己声明的方法，`updateIslandShowing` 经 dump 确认就声明在该类上。
 *
 * 系统自己就是靠这个信息在灵动岛出现时隐藏状态栏电池图标，语义与需求一致。
 *
 * ## 为什么按父链过滤到状态栏容器
 *
 * `MiuiBatteryMeterView` 在状态栏、控制中心等多处都有实例，各实例的
 * `mIsIslandShowing` 可能不同步。只认位于 `MiuiStatusBatteryContainer` 内的实例
 * （与 BatteryHideHook 判定"状态栏电池视图"的同一套办法），避免多实例互相打架导致抖动。
 *
 * ## 降级方向
 *
 * 找不到类 / 字段读不到 / 方法不存在：一律记日志后安静放弃，**环保持常显** ——
 * 「多显示一个环」远比「环该藏却没藏」安全。
 */
object IslandVisibilityHook {

    private const val METER_VIEW =
        "com.android.systemui.statusbar.views.MiuiBatteryMeterView"

    private const val CONTAINER_VIEW =
        "com.android.systemui.statusbar.views.MiuiStatusBatteryContainer"

    /** 该方法内部写入的岛显示状态字段 */
    private const val FIELD_ISLAND_SHOWING = "mIsIslandShowing"

    @Volatile
    private var installed = false

    // ---- 以下状态只在 SystemUI 主线程读写 ----

    /** 上次已上报给 RingState 的取值，避免高频回调反复驱动 */
    private var lastDriven: Boolean? = null

    /** 实例父链只打印一次，避免刷屏 */
    @Volatile
    private var chainLogged = false

    fun install(classLoader: ClassLoader) {
        if (installed) return
        installed = true

        val meterClass = XposedHelpers.findClassIfExists(METER_VIEW, classLoader)
        if (meterClass == null) {
            ModuleLog.e("未找到 $METER_VIEW，灵动岛显隐探针未生效（环保持常显）", null)
            return
        }

        try {
            XposedHelpers.findAndHookMethod(
                meterClass,
                "updateIslandShowing",
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val view = param.thisObject as? View ?: return
                            if (!isInStatusBatteryContainer(view)) return
                            val showing = XposedHelpers.getBooleanField(
                                param.thisObject, FIELD_ISLAND_SHOWING,
                            )
                            drive(showing)
                        } catch (t: Throwable) {
                            ModuleLog.e("灵动岛显隐回调异常", t)
                        }
                    }
                },
            )
            ModuleLog.i("灵动岛显隐 Hook 已挂载 $METER_VIEW.updateIslandShowing（宿主侧信号）")
        } catch (t: Throwable) {
            ModuleLog.e("Hook updateIslandShowing 失败（环保持常显）", t)
        }
    }

    /** 只认状态栏电池容器内的实例，控制中心等其他实例不动。 */
    private fun isInStatusBatteryContainer(start: View): Boolean {
        var node: ViewParent? = start.parent
        var depth = 0
        while (node != null && depth < 30) {
            if (node.javaClass.name == CONTAINER_VIEW) return true
            node = node.parent
            depth++
        }
        if (!chainLogged) {
            chainLogged = true
            ModuleLog.i("灵动岛显隐探针：首个实例不在状态栏容器内，已忽略（链=${parentChain(start)}）")
        }
        return false
    }

    private fun parentChain(start: View): String {
        val sb = StringBuilder()
        var node: ViewParent? = start.parent
        var depth = 0
        while (node != null && depth < 15) {
            if (depth > 0) sb.append(" <- ")
            sb.append(node.javaClass.simpleName)
            node = node.parent
            depth++
        }
        return sb.toString()
    }

    /** 变化过滤 + 驱动 [RingState]，与 ImmersiveProbeHook 的 drive 同构。 */
    private fun drive(showing: Boolean) {
        if (showing == lastDriven) return
        lastDriven = showing
        ModuleLog.i("灵动岛显隐驱动: showing=$showing")
        RingState.setIslandShowing(showing)
    }
}
