package com.powerring.hole.hook

import android.view.View
import com.powerring.hole.core.ModuleLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import de.robv.android.xposed.XposedHelpers

/**
 * 灵动岛坐标探针：
 *
 * 插件类（miui.systemui.plugin）由独立 ClassLoader 加载，因此先 Hook
 * ClassLoader.loadClass，在 DynamicIslandBackgroundView 被加载时拿到其
 * Class 再 Hook onLayout / setVisibility，打印小岛胶囊在屏幕上的真实
 * 位置，用于校准环形电量的圆心。
 */
object IslandProbeHook {

    private const val ISLAND_BG_VIEW = "miui.systemui.dynamicisland.DynamicIslandBackgroundView"
    private const val PREFIX = "miui.systemui.dynamicisland"

    @Volatile
    private var installed = false

    fun install(classLoader: ClassLoader) {
        if (installed) return
        installed = true

        // loadClass(String) 是插件加载类时的必经路径
        XposedHelpers.findAndHookMethod(
            ClassLoader::class.java,
            "loadClass",
            String::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val name = param.args[0] as? String ?: return
                    if (!name.startsWith(PREFIX)) return
                    if (name != ISLAND_BG_VIEW) return
                    val cls = param.result as? Class<*> ?: return
                    hookIslandView(cls)
                }
            },
        )
        ModuleLog.i("灵动岛坐标探针已挂载（等待插件类加载）")
    }

    private var viewHooked = false

    private fun hookIslandView(cls: Class<*>) {
        if (viewHooked) return
        viewHooked = true
        ModuleLog.i("捕获到插件类 $cls，开始 Hook onLayout")

        try {
            XposedHelpers.findAndHookMethod(
                cls, "onLayout",
                Boolean::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val v = param.thisObject as? View ?: return
                        if (v.visibility != View.VISIBLE) return
                        val loc = IntArray(2)
                        v.getLocationOnScreen(loc)
                        ModuleLog.i(
                            "灵动岛布局: visible=${v.visibility} screenPos=(${loc[0]},${loc[1]}) " +
                                "size=${v.width}x${v.height} center=(${loc[0] + v.width / 2}," +
                                "${loc[1] + v.height / 2}) cls=${v.javaClass.name}",
                        )
                    }
                },
            )
        } catch (t: Throwable) {
            ModuleLog.e("Hook DynamicIslandBackgroundView.onLayout 失败", t)
        }

        try {
            XposedHelpers.findAndHookMethod(
                cls, "setVisibility", Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val v = param.thisObject as? View ?: return
                        val vis = param.args[0] as Int
                        if (vis == View.VISIBLE) {
                            v.post {
                                val loc = IntArray(2)
                                v.getLocationOnScreen(loc)
                                ModuleLog.i(
                                    "灵动岛显示: screenPos=(${loc[0]},${loc[1]}) " +
                                        "size=${v.width}x${v.height} center=(${loc[0] + v.width / 2}," +
                                        "${loc[1] + v.height / 2})",
                                )
                            }
                        }
                    }
                },
            )
        } catch (t: Throwable) {
            ModuleLog.e("Hook DynamicIslandBackgroundView.setVisibility 失败", t)
        }
    }
}
