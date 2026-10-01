package com.powerring.hole.hook

import android.view.View
import com.powerring.hole.core.ModuleLog
import com.powerring.hole.ring.RingState
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import de.robv.android.xposed.XposedHelpers

/**
 * 灵动岛显隐探针：驱动「有岛时收起圆环」。
 *
 * 为什么不直接用现成的 `IslandProbeHook` 里那套写法：它 Hook 了 `onLayout`，
 * 而 `XposedHelpers.findAndHookMethod` 会沿父类链解析方法——目标类没有声明
 * `onLayout` 时，Hook 实际绑到 `android.view.View.onLayout`，于是在 SystemUI 进程内
 * 对**每一个 View** 生效（这正是「挖孔环形电量LSP模块-可行性分析报告.md」§10.5
 * 记录的踩坑：安全门控形同虚设、误判把环藏掉、并给最高频方法加上 Xposed 回调）。
 *
 * 逆向结论（`work/dump_class.py` 对插件 dex 的转储）：
 * `miui.systemui.dynamicisland.DynamicIslandBackgroundView` 的 super = `FrameLayout`，
 * **自身声明了** `setVisibility(I)`（以及编译器合成的桥方法 `superSetVisibility(I)`）。
 * 因此本探针只 Hook 它的 `setVisibility`，绝不碰 `onLayout` 等继承方法。
 * 加 Hook 前若换机型，先用 `work/dump_class.py` 复核这两个事实。
 *
 * 类加载时机：灵动岛属插件（miui.systemui.plugin）侧，由宿主在运行时用自己的
 * ClassLoader 加载，装 Hook 时类多半还不存在 ⇒ 拦 `ClassLoader.loadClass`，
 * 等类真正被加载出来时再取 Class 下 Hook。
 *
 * 降级方向：找不到类 / Hook 失败 / 回调异常，一律记日志后安静放弃，
 * 环保持常显——「多显示一个环」远比「环该藏却没藏」安全。
 */
object IslandVisibilityHook {

    /** 岛背景 View：自身声明 setVisibility，是唯一的驱动点。 */
    private const val ISLAND_BG_VIEW = "miui.systemui.dynamicisland.DynamicIslandBackgroundView"

    @Volatile
    private var installed = false

    // ---- 以下状态只在 SystemUI 主线程读写 ----

    /** 上次已上报给 RingState 的取值，避免 setVisibility 高频调用时反复驱动 */
    private var lastDriven: Boolean? = null

    /** 目标类是否已挂过 Hook，防止 loadClass 多次命中时重复挂钩 */
    @Volatile
    private var viewHooked = false

    fun install(classLoader: ClassLoader) {
        if (installed) return
        installed = true

        try {
            // loadClass(String) 是插件加载类时的必经路径
            XposedHelpers.findAndHookMethod(
                ClassLoader::class.java,
                "loadClass",
                String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val name = param.args[0] as? String ?: return
                            if (name != ISLAND_BG_VIEW) return
                            val cls = param.result as? Class<*> ?: return
                            hookIslandView(cls)
                        } catch (t: Throwable) {
                            ModuleLog.e("灵动岛显隐探针 loadClass 回调异常", t)
                        }
                    }
                },
            )
            ModuleLog.i("灵动岛显隐探针已挂载（等待插件类 $ISLAND_BG_VIEW 加载）")
        } catch (t: Throwable) {
            ModuleLog.e("灵动岛显隐探针安装失败（环保持常显）", t)
        }
    }

    private fun hookIslandView(cls: Class<*>) {
        if (viewHooked) return
        viewHooked = true
        try {
            XposedHelpers.findAndHookMethod(
                cls, "setVisibility",
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val v = param.thisObject as? View ?: return
                            // VISIBLE 但还没挂到窗口上的那一帧不算「正在显示」：
                            // 岛展开/收起动画里存在这种中间态
                            val showing = v.visibility == View.VISIBLE && v.isAttachedToWindow
                            drive(showing)
                        } catch (t: Throwable) {
                            ModuleLog.e("灵动岛显隐探针 setVisibility 回调异常", t)
                        }
                    }
                },
            )
            ModuleLog.i("灵动岛显隐探针已挂载 ${cls.name}.setVisibility（驱动信号）")
        } catch (t: Throwable) {
            ModuleLog.e("Hook ${cls.name}.setVisibility 失败（环保持常显）", t)
        }
    }

    /** 变化过滤 + 驱动 [RingState]，与 ImmersiveProbeHook 的 drive 同构。 */
    private fun drive(showing: Boolean) {
        if (showing == lastDriven) return
        lastDriven = showing
        ModuleLog.i("灵动岛显隐探针驱动: showing=$showing")
        RingState.setIslandShowing(showing)
    }
}
