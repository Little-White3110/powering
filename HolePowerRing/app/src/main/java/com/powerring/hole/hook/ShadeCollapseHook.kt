package com.powerring.hole.hook

import com.powerring.hole.core.ModuleLog
import com.powerring.hole.ring.RingState
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import de.robv.android.xposed.XposedHelpers

/**
 * 面板拉起收起：下拉通知栏或控制中心时让圆环向内收缩并淡出（设置键 `collapse_on_shade`，
 * 默认关；关闭时 [com.powerring.hole.ring.RingCollapseLogic.target] 把该通路当 0f 处理，
 * 对行为零影响）。
 *
 * 两路布尔"求或"驱动，缺一不可（2026-10-03 三轮真机取证定论，
 * 详见可行性分析报告 §18）：
 * - 通知面板：`com.android.systemui.shade.NotificationShadeWindowControllerImpl`
 *   （classes2.dex）`onShadeOrQsExpanded(Ljava/lang/Boolean;)V`。装箱 Boolean，
 *   故 `findAndHookMethod` 用 `javaObjectType` 而非 primitive。
 *   ⚠ 名字里的 "Qs" 是误导：实测**下拉控制中心时它一次都不触发**，只覆盖通知面板。
 * - 控制中心：`com.miui.systemui.controlcenter.container.ControlCenterExpandControllerDelegate`
 *   （classes3.dex）`onVisibleChanged(Z)V`。展开→true、收起→false 成对派发（实测三组开合
 *   全部成对），是控制中心唯一可靠、且收起方向天然带回落到隐藏的信号。
 *
 * 任一为真即收到 1f、两者皆假才弹回 0f。观感不是硬闪：0f↔1f 跳变走 [RingState]
 * 260ms `DecelerateInterpolator` 动画。fraction 形参保留以便将来接入连续进度信号。
 *
 * 曾评估后弃用的信号：
 * - `ShadeExpansionStateManager.onPanelExpansionChanged(FZZ)`：挂载成功但整场 0 触发
 *   （本机通知面板走传统 MIUI View 管线，AOSP scene/flow 点位是死代码）。
 * - `NotificationShadeWrapper.onPanelExpanded(Z)`：全程单实例触发，只覆盖通知面板。
 * - `ControlCenterExpandControllerDelegate.onExpansionChanged(F)`：展开时能给连续 fraction，
 *   但"收起是否回落 0f"两轮都因日志量撞满上限而未证实；拿它驱动有"环卡在不可见"的风险
 *   （比"该藏没藏"更糟，见 AGENTS.md 降级原则），故改用同类的 `onVisibleChanged` 布尔。
 *
 * 降级方向：类/方法找不到 → 记日志安静放弃 → 环保持常显。
 * 纪律：两路都在 SystemUI APK 自身的 dex，用宿主 ClassLoader 直接取类，不涉插件 ClassLoader、
 * 不在 loadClass 回调里干活；回调内只做取值、门控、求或与驱动，绝不反射枚举、绝不装 Hook；
 * 每一处包 try/catch (Throwable) + ModuleLog，异常不得逃逸到 SystemUI。
 */
object ShadeCollapseHook {

    private const val SHADE_WINDOW_CTRL =
        "com.android.systemui.shade.NotificationShadeWindowControllerImpl"
    private const val CC_EXPAND_DELEGATE =
        "com.miui.systemui.controlcenter.container.ControlCenterExpandControllerDelegate"

    @Volatile
    private var installed = false

    // ---- 以下状态只在 SystemUI 主线程读写 ----

    /** 通知面板展开态（来自 onShadeOrQsExpanded），与开关无关，始终跟踪 */
    private var notifExpanded = false

    /** 控制中心展开态（来自 onVisibleChanged），与开关无关，始终跟踪 */
    private var ccExpanded = false

    /** 上一次驱动出去的进度，用于变化去重 */
    private var lastFraction = Float.MIN_VALUE

    fun install(classLoader: ClassLoader) {
        if (installed) return
        installed = true
        // 两路独立安装：任一路缺失只让它自己不生效，另一路照常工作。
        runCatching { hookNotification(classLoader) }
            .onFailure { ModuleLog.e("面板收起信号挂载通知路失败", it) }
        runCatching { hookControlCenter(classLoader) }
            .onFailure { ModuleLog.e("面板收起信号挂载控制中心路失败", it) }
    }

    /**
     * 开关打开时按已观测的两路状态补一次驱动。
     *
     * 由「下拉面板收起」开关热翻转时（RingState.onShadeCollapseToggled）调用：开关关闭期间
     * 回调只更新状态、不写 `lastFraction`，它会停在旧值；不清掉就会把开关后第一次真实变化
     * 当成重复值跳过（环不跟手），且此刻面板可能本就开着、不补就会"面板开着、环还亮着"。
     */
    fun syncFromSystem() {
        lastFraction = Float.MIN_VALUE
        val fraction = combinedFraction()
        ModuleLog.i("下拉收起开关翻转，同步已观测状态 notif=$notifExpanded cc=$ccExpanded → $fraction")
        RingState.setShadeCollapse(fraction)
    }

    /** 两路求或：任一面板展开即 1f，都收起才 0f。 */
    private fun combinedFraction(): Float = if (notifExpanded || ccExpanded) 1f else 0f

    /**
     * 求或后驱动。门控必须在驱动之前：开关关闭时驱动一次就伴随一条日志 + 一次
     * invalidateAll，而收起目标恒为 0f，等于整场拖拽白重绘（首轮就踩了）。
     * 调用前 [notifExpanded] / [ccExpanded] 已被更新，故开关关闭时状态仍在跟踪，
     * 供 [syncFromSystem] 在开关重新打开时读取。
     */
    private fun evaluateAndDrive() {
        if (!RingState.config.collapseOnShade) return
        val fraction = combinedFraction()
        if (fraction == lastFraction) return
        lastFraction = fraction
        RingState.setShadeCollapse(fraction)
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
                            notifExpanded = (param.args[0] as? Boolean) == true
                            evaluateAndDrive()
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
                            ccExpanded = (param.args[0] as? Boolean) == true
                            evaluateAndDrive()
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
