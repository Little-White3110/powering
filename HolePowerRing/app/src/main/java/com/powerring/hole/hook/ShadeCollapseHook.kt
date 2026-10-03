package com.powerring.hole.hook

import com.powerring.hole.core.ModuleLog
import com.powerring.hole.ring.RingState
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import de.robv.android.xposed.XposedHelpers

/**
 * 面板拉起探针：把「通知中心展开」与「控制中心展开」两路布尔信号送进 [RingState]，
 * 由 `hideInShade` / `hideInControlCenter` 两个开关在 `RingState.collapseTarget()`
 * 一侧各自门控（本探针始终跟踪状态，与开关无关）。
 *
 * 展开时环收起、并把状态栏原生电池图标交还系统（面板里显示正常电量），收起后自动恢复；
 * 回调侧由 `RingState.onShadeExpandedChanged` 接到 `BatteryHideHook.refreshAll()`。
 *
 * 两路信号源缺一不可（2026-10-03 三轮真机取证定论，详见可行性分析报告 §18）：
 * - 通知面板：`com.android.systemui.shade.NotificationShadeWindowControllerImpl`
 *   （classes2.dex）`onShadeOrQsExpanded(Ljava/lang/Boolean;)V`。装箱 Boolean，
 *   故 `findAndHookMethod` 用 `javaObjectType` 而非 primitive。
 *   ⚠ 名字里的 "Qs" 是误导：实测**下拉控制中心时它一次都不触发**，只覆盖通知面板。
 * - 控制中心：`com.miui.systemui.controlcenter.container.ControlCenterExpandControllerDelegate`
 *   （classes3.dex）`onVisibleChanged(Z)V`。展开→true、收起→false 成对派发（实测三组开合
 *   全部成对），是控制中心唯一可靠、且收起方向天然带回落到隐藏的信号。
 *
 * 曾评估后弃用的信号：
 * - `ShadeExpansionStateManager.onPanelExpansionChanged(FZZ)`：挂载成功但整场 0 触发
 *   （本机通知面板走传统 MIUI View 管线，AOSP scene/flow 点位是死代码）。
 * - `NotificationShadeWrapper.onPanelExpanded(Z)`：全程单实例触发，只覆盖通知面板。
 * - `ControlCenterExpandControllerDelegate.onExpansionChanged(F)`：展开时能给连续 fraction，
 *   但"收起是否回落 0f"两轮都因日志量撞满上限而未证实；拿它驱动有"环卡在不可见"的风险
 *   （比"该藏没藏"更糟，见 AGENTS.md 降级原则），故只用同类的 `onVisibleChanged` 布尔。
 *
 * 观感不是硬闪：布尔跳变走 [RingState] 300ms `DecelerateInterpolator` 动画；
 * 两个 setter 自身按值去重，因此这里不做本地缓存，面板状态由 [RingState] 唯一持有，
 * 自愈复位（`RingState.resetCollapseInputs`）与沉降窗口都作用在同一份状态上。
 *
 * 降级方向：类/方法找不到 → 记日志安静放弃 → 环保持常显。
 * 纪律：两路都在 SystemUI APK 自身的 dex，用宿主 ClassLoader 直接取类，不涉插件 ClassLoader、
 * 不在 loadClass 回调里干活；回调内只做取值与驱动，绝不反射枚举、绝不装 Hook；
 * 每一处包 try/catch (Throwable) + ModuleLog，异常不得逃逸到 SystemUI。
 */
object ShadeCollapseHook {

    private const val SHADE_WINDOW_CTRL =
        "com.android.systemui.shade.NotificationShadeWindowControllerImpl"
    private const val CC_EXPAND_DELEGATE =
        "com.miui.systemui.controlcenter.container.ControlCenterExpandControllerDelegate"

    @Volatile
    private var installed = false

    fun install(classLoader: ClassLoader) {
        if (installed) return
        installed = true
        // 两路独立安装：任一路缺失只让它自己不生效，另一路照常工作。
        runCatching { hookNotification(classLoader) }
            .onFailure { ModuleLog.e("面板收起信号挂载通知路失败", it) }
        runCatching { hookControlCenter(classLoader) }
            .onFailure { ModuleLog.e("面板收起信号挂载控制中心路失败", it) }
    }

    private fun hookNotification(classLoader: ClassLoader) {
        val cls = runCatching {
            XposedHelpers.findClassIfExists(SHADE_WINDOW_CTRL, classLoader)
        }.getOrNull()
        if (cls == null) {
            ModuleLog.e("未找到 $SHADE_WINDOW_CTRL，通知面板收起通路未生效（环保持常显）", null)
            return
        }
        try {
            XposedHelpers.findAndHookMethod(
                cls, "onShadeOrQsExpanded",
                // 该方法签名是装箱 Boolean（Ljava/lang/Boolean;），不是 primitive Z
                Boolean::class.javaObjectType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            RingState.setShadeExpanded((param.args[0] as? Boolean) == true)
                        } catch (t: Throwable) {
                            ModuleLog.e("面板收起通知路回调异常", t)
                        }
                    }
                },
            )
            ModuleLog.i("面板收起信号已挂载通知路 ${cls.name}.onShadeOrQsExpanded")
        } catch (t: Throwable) {
            ModuleLog.e("Hook onShadeOrQsExpanded 失败（环保持常显）", t)
        }
    }

    private fun hookControlCenter(classLoader: ClassLoader) {
        val cls = runCatching {
            XposedHelpers.findClassIfExists(CC_EXPAND_DELEGATE, classLoader)
        }.getOrNull()
        if (cls == null) {
            ModuleLog.e("未找到 $CC_EXPAND_DELEGATE，控制中心收起通路未生效（环保持常显）", null)
            return
        }
        try {
            XposedHelpers.findAndHookMethod(
                cls, "onVisibleChanged",
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            RingState.setControlCenterShowing((param.args[0] as? Boolean) == true)
                        } catch (t: Throwable) {
                            ModuleLog.e("面板收起控制中心路回调异常", t)
                        }
                    }
                },
            )
            ModuleLog.i("面板收起信号已挂载控制中心路 ${cls.name}.onVisibleChanged")
        } catch (t: Throwable) {
            ModuleLog.e("Hook onVisibleChanged 失败（环保持常显）", t)
        }
    }
}
