package com.powerring.hole.hook

import android.os.Handler
import android.os.Looper
import com.powerring.hole.core.ModuleLog
import com.powerring.hole.ring.RingState
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference

/**
 * 控制中心 / 通知面板展开探针（驱动「面板展开时隐藏圆环 + 交还原生电量图标」）。
 *
 * 为什么需要它：环是独立窗口（type=2006，层带 231000），比下拉面板还高，
 * 面板展开时环会压在控制中心上；而状态栏原生电池图标又已被本模块隐藏，
 * 于是控制中心顶部既没有正常电量提示、又顶着一圈环。这里检测面板展开，
 * 让 [RingState.setShadeExpanded] 收起环并把图标交还系统。
 *
 * ## 2026-10-03 事故复盘（v0.8.1 重写，别改回去）
 *
 * v0.8.0 引入了两条「新」驱动通道：
 * 1. `StatusBarStateControllerImpl.setState(int, boolean)` 后读 `isExpanded()`；
 * 2. `KeyguardUpdateMonitorCallback.onShadeExpandedChanged(boolean)`。
 *
 * dex 转储确认这两条通道的语义都是 `StatusBarStateControllerImpl.mIsExpanded`，
 * 而 `mIsExpanded` 在 `start()` 里由 **ShadeInteractor 的 `anyExpansion`**
 * 这一条 Kotlin Flow 驱动（不是「面板真的展开了」）——它是「任意形式的展开」
 * （轻微下拉、QS、过渡中间态都算）。在锁屏 / 解锁过渡 / 开机阶段它很容易为 true
 * 且不会自动回落到 false。由于 `hideInShade` 默认开启，这个假 true 一旦落进
 * [RingState] 就把环永久收缩（`expand<=0.01` 直接 return）——症状就是
 * 「重启后环整个不见了」。v0.7.0 读的是另一个更严格的字段，所以没有这个问题。
 *
 * ## 现在的信号选取（v0.8.1）
 *
 * **唯一权威 = `ShadeControllerImpl.mExpandedVisible`**（读法见 [readExpandedVisible]）。
 * dex 转储确认：该字段**只**由 `makeExpandedVisible(Z)` / `makeExpandedInvisible()`
 * 两条一等事件写入，语义精确等于「面板真的展开并可见」，锁屏未下拉时为 false。
 * 同类的 `mLockscreenOrShadeVisible` 是另一个字段（还含锁屏可见），本探针刻意不用。
 *
 * - **立即响应**：按方法名挂 `ShadeControllerImpl` 的展开/收起事件，事件触发后
 *   **回读 mExpandedVisible** 再驱动（不猜方向）。
 * - **自愈轮询**：SystemUI 主线程每 1 秒回读一次 mExpandedVisible 并对齐。
 *   任何来源把状态带偏（含旧版遗留的假 true），最多 1 秒后自动纠正——
 *   这是本次事故的根治手段。
 * - `setState` / `onShadeExpandedChanged` / MIUI 的 appear 回调**只写日志、不驱动**
 *   （它们的信号是 `anyExpansion`，语义不匹配）。
 *
 * 降级策略：所有通道都挂不上时本探针从不调用 RingState，环保持常显——安全降级。
 */
object ShadeProbeHook {

    /** 面板控制器（AOSP）：展开/收起的一等事件与权威字段都在这里。 */
    private const val SHADE_CONTROLLER = "com.android.systemui.shade.ShadeControllerImpl"

    /** 权威字段：面板是否「展开且可见」。只由 makeExpandedVisible/Invisible 写入。 */
    private const val FIELD_EXPANDED_VISIBLE = "mExpandedVisible"

    /** AOSP 状态栏状态控制器：setState(int, boolean)（仅诊断，不驱动）。 */
    private const val STATE_CONTROLLER =
        "com.android.systemui.statusbar.StatusBarStateControllerImpl"

    /** 下拉面板控制器注入器（MIUI 侧）：仅诊断用。 */
    private const val PANEL_INJECTOR =
        "com.android.systemui.shade.NotificationPanelViewControllerInjector"

    /** KeyguardUpdateMonitor 回调基类：onShadeExpandedChanged(boolean)（仅诊断）。 */
    private const val KUM_CALLBACK = "com.android.keyguard.KeyguardUpdateMonitorCallback"

    /** 诊断日志上限，任何异常情况下都不允许刷屏。 */
    private const val MAX_DIAG_LINES = 80

    /** 自愈轮询间隔（ms）：权威字段回读周期，环的显隐最多滞后这么久被纠正。 */
    private const val POLL_MS = 600L

    /** 面板展开比例小于它即视为「面板完全收着」（浮点比较容差）。 */
    private const val PANEL_FRACTION_EPS = 0.01f

    /**
     * 「面板真的拉开了」的展开比例阈值（2026-10-03 用户反馈「通知来的时候环会消失」）。
     *
     * 为什么不能见到 `mExpandedVisible == true` 就收环：**悬浮通知（heads-up）**
     * 到达时，系统会把通知面板微微顶出一点点（面板位置 fraction 只有零点几），
     * 某些过渡动画里 `mExpandedVisible` 也会瞬时为 true。照单全收的话，每次来通知
     * 环都会“消失”几秒再回来 —— 用户看到的正是这个。
     *
     * 真正的下拉，面板位置会迅速越过这个阈值（用户手指至少拉了小半屏），
     * 因此用它把「悬浮通知 / 过渡态」和「真的在下拉」区分开。
     */
    private const val SHADE_HIDE_MIN_FRACTION = 0.28f

    /** 面板自身展开比例字段（NotificationPanelViewController）。 */
    private const val FIELD_EXPANDED_FRACTION = "mExpandedFraction"

    @Volatile
    private var installed = false

    // ---- 仅在 SystemUI 主线程读写 ----

    /** 面板控制器实例（自愈轮询靠它回读权威字段）。弱引用，不拖住控制器。 */
    private var controller: WeakReference<Any>? = null

    private var diagLines = 0

    private val handler = Handler(Looper.getMainLooper())

    private val pollRunnable = object : Runnable {
        override fun run() {
            try {
                // 息屏时不轮询：没有下拉面板场景，省掉一份无意义唤醒
                if (RingState.screenOn) {
                    val v = pollShadeExpanded(controller?.get())
                    if (v != null) driveByValue(v, "poll")
                }
            } catch (t: Throwable) {
                // 轮询异常绝不能拖垮宿主，打一次日志后继续
                ModuleLog.e("控制中心探针轮询异常", t)
            }
            handler.postDelayed(this, POLL_MS)
        }
    }

    fun install(classLoader: ClassLoader) {
        if (installed) return
        installed = true
        hookShadeController(classLoader)
        hookStateControllerDiag(classLoader)
        hookKumCallbackDiag(classLoader)
        hookPanelInjectorDiag(classLoader)
        handler.removeCallbacks(pollRunnable)
        handler.postDelayed(pollRunnable, POLL_MS)
        ModuleLog.i("控制中心探针：自愈轮询已启动（${POLL_MS}ms，权威字段 $FIELD_EXPANDED_VISIBLE）")
    }

    /**
     * 立刻按系统实时值对齐一次（屏幕点亮 / 解锁完成时由 RingState 请求）。
     *
     * 与轮询同一判据，只是不等下一个 1s 周期。非主线程调用时转投主线程。
     */
    fun reconcile() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { reconcile() }
            return
        }
        val v = pollShadeExpanded(controller?.get()) ?: return
        driveByValue(v, "reconcile")
    }

    /**
     * 主通道：面板控制器上的展开/收起事件 + 权威字段回读。
     *
     * 每个方法单独 try：某个方法在该版本不存在或签名不同时只跳过它，
     * 不影响其它事件挂载。
     */
    private fun hookShadeController(cl: ClassLoader) {
        val cls = runCatching {
            XposedHelpers.findClassIfExists(SHADE_CONTROLLER, cl)
        }.getOrNull()
        if (cls == null) {
            ModuleLog.e("未找到 $SHADE_CONTROLLER，控制中心展开检测（主通道）不可用", null)
            return
        }

        // 记下实例：start() 一定在主线程被调用一次，之后轮询就能回读权威字段
        runCatching {
            XposedHelpers.findAndHookMethod(
                cls, "start",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        controller = WeakReference(param.thisObject)
                        ModuleLog.i("控制中心探针已捕获面板控制器实例（自愈轮询可回读权威字段）")
                    }
                },
            )
        }.onFailure { ModuleLog.e("Hook $SHADE_CONTROLLER.start 失败（轮询退化为事件驱动）", it) }

        // 展开 / 收起事件：不猜方向，事件后统一回读 mExpandedVisible
        for (name in arrayOf("makeExpandedVisible", "instantExpandShade", "expandToNotifications", "expandToQs")) {
            hookByName(cls, name, "展开")
        }
        for (name in arrayOf(
            "makeExpandedInvisible", "collapseShade", "instantCollapseShade",
            "animateCollapseShade", "collapseOnMainThread", "collapseShadeInternal",
        )) {
            hookByName(cls, name, "收起")
        }
    }

    /** 挂某个名字的全部重载；事件后回读权威字段并驱动。 */
    private fun hookByName(cls: Class<*>, name: String, kind: String) {
        val hooked = runCatching {
            var n = 0
            for (m in cls.declaredMethods) {
                if (m.name != name) continue
                runCatching {
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            controller = WeakReference(param.thisObject)
                            diag("面板$kind: $name → 回读 ${FIELD_EXPANDED_VISIBLE}")
                            if (kind == "展开") {
                                driveExpandEvent(param.thisObject, name)
                            } else {
                                // 回读权威字段再驱动，避免把「过渡态」当成「已展开」
                                val v = readExpandedVisible(param.thisObject) ?: false
                                driveByValue(v, name)
                            }
                        }
                    })
                }.onSuccess { n++ }
            }
            n
        }.getOrDefault(0)
        if (hooked > 0) {
            ModuleLog.i("控制中心探针已挂载 $SHADE_CONTROLLER.$name → $kind（$hooked 个重载）")
        }
    }

    /** 展开事件延迟复核的延迟（ms）：给面板位置一点时间动起来。 */
    private const val EXPAND_CONFIRM_DELAY_MS = 260L

    /** 是否已排过一次展开复核（避免同一波过渡事件排出一串复核）。 */
    private var expandConfirmPosted = false

    /**
     * 展开事件的处理（2026-10-03 用户反馈「解锁后要过一会儿才有环」之后加固）。
     *
     * 事件通道原先只看 `mExpandedVisible`：一旦标志为 true 就立刻把环收起来。
     * 但解锁动画 / 窗口切换 / QS 手势的**过渡态**里这个标志会瞬间为 true，
     * 而面板其实根本没出现 —— 环就被白白收掉，要等轮询慢慢纠回来。
     *
     * 现在的判据：标志说展开时，同时看一眼**面板实际位置**
     * （`NotificationPanelViewController.mExpandedFraction`）。
     * - 位置也动了（> ε）：确实是真下拉，立刻收起（不引入延迟）；
     * - 位置仍是 0：多半是过渡误报，先不收，排一次 260ms 后的复核再定夺。
     */
    private fun driveExpandEvent(target: Any, source: String) {
        val flag = readExpandedVisible(target)
        if (flag == false) {
            driveByValue(false, source)
            return
        }
        val frac = readPanelExpandedFraction(target)
        if (flag == true && frac != null && frac < SHADE_HIDE_MIN_FRACTION) {
            diag(
                "面板展开事件($source) 但面板位置只有 $frac，疑似悬浮通知/过渡态 → " +
                    "延后 ${EXPAND_CONFIRM_DELAY_MS}ms 复核",
            )
            scheduleExpandConfirm()
            return
        }
        driveByValue(flag ?: true, source)
    }

    /** 展开事件的延迟复核：位置仍是 0 就判为误报，把环放回来。 */
    private fun scheduleExpandConfirm() {
        if (expandConfirmPosted) return
        expandConfirmPosted = true
        handler.postDelayed({
            expandConfirmPosted = false
            runCatching {
                val obj = controller?.get() ?: return@runCatching
                val flag = readExpandedVisible(obj) ?: return@runCatching
                val frac = readPanelExpandedFraction(obj)
                if (flag && frac != null && frac < SHADE_HIDE_MIN_FRACTION) {
                    ModuleLog.i(
                        "通知中心探针：展开事件后 ${EXPAND_CONFIRM_DELAY_MS}ms 面板位置仍只有 $frac" +
                            "（标志=true），判为悬浮通知/过渡误报 → 放环回来",
                    )
                    staleHits = 0
                    driveByValue(false, "expand-confirm")
                } else {
                    driveByValue(flag, "expand-confirm")
                }
            }
        }, EXPAND_CONFIRM_DELAY_MS)
    }

    /** 读权威字段：优先 `mExpandedVisible`，退回 `isExpandedVisible()`。读不到返回 null。 */
    private fun readExpandedVisible(target: Any? = controller?.get()): Boolean? {
        val obj = target ?: return null
        runCatching {
            XposedHelpers.getBooleanField(obj, FIELD_EXPANDED_VISIBLE)
        }.getOrNull()?.let { return it }
        return runCatching {
            XposedHelpers.callMethod(obj, "isExpandedVisible") as? Boolean
        }.getOrNull()
    }

    /**
     * 面板自身的展开比例（`NotificationPanelViewController.mExpandedFraction`）。
     *
     * 这是「面板到底有没有出现在屏幕上」的**位置**证据，与 `mExpandedVisible`
     * 这个**状态标志**来自不同地方。读不到返回 null。
     */
    private fun readPanelExpandedFraction(target: Any?): Float? {
        val obj = target ?: return null
        val npvc = runCatching {
            XposedHelpers.callMethod(obj, "getNpvc")
        }.getOrNull() ?: return null
        runCatching {
            XposedHelpers.getFloatField(npvc, FIELD_EXPANDED_FRACTION)
        }.getOrNull()?.let { return it }
        // 字段读不到就退回面板自己的判据（isFullyCollapsed()）
        val collapsed = runCatching {
            XposedHelpers.callMethod(npvc, "isFullyCollapsed") as? Boolean
        }.getOrNull() ?: return null
        return if (collapsed) 0f else 1f
    }

    /**
     * 轮询 / 复核专用判据：**状态标志与面板位置互相校验**。
     *
     * 为什么不能只信 `mExpandedVisible`（2026-10-03 三次事故后的结论）：
     * 该字段只在 `makeExpandedVisible(Z)` / `makeExpandedInvisible()` 里改写，
     * 一旦膨胀动作被锁屏、解锁过渡、息屏打断，就没有人再把它清回去 ——
     * 于是它在「锁屏正常、解锁进桌面后环消失」的场景里会是**残留 true**。
     * 系统自己的 `expandedVisibleChanged` 回调也是同一份字段，救不了。
     *
     * 因此再加一条独立证据：面板的展开比例。两者矛盾
     * （标志说展开、面板却完全收着）时以**面板位置**为准，把环放回来。
     * 为免误伤「刚开始下拉、面板还没动」的瞬间，矛盾要连续 [STALE_CONFIRM_HITS]
     * 次才采信（面板比例字段是本机系统的自身依赖：`collapseShadeInternal()` 也要
     * 用它判早退，所以它必须是活的）。
     *
     * 事件通道（[hookByName]）保持只看标志，保证「真下拉」时环立刻收起不延迟。
     */
    private fun pollShadeExpanded(target: Any?): Boolean? {
        val visible = readExpandedVisible(target) ?: return null
        if (!visible) {
            staleHits = 0
            return false
        }
        val frac = readPanelExpandedFraction(target)
        // 标志说可见，但面板位置没拉开：悬浮通知 / 过渡态 / 锁屏残留都会这样。
        // 连续两次都是这样才采信「其实没展开」，避免把刚开始下拉的瞬间误判进来。
        if (frac != null && frac < SHADE_HIDE_MIN_FRACTION) {
            staleHits++
            if (staleHits == 1) {
                diag("面板标志=展开但位置仅 $frac（疑似悬浮通知/过渡态），等待复核")
            }
            if (staleHits >= STALE_CONFIRM_HITS) {
                ModuleLog.i(
                    "通知中心探针：确认不是真下拉（位置=$frac，标志=true）→ 环保持显示",
                )
                return false
            }
            // 尚未确认：维持现状，不驱动
            return null
        }
        staleHits = 0
        return true
    }

    /** 连续多少次「标志说展开、面板位置说收起」才判定为残留。 */
    private const val STALE_CONFIRM_HITS = 2

    /** 连续矛盾计数（仅在 SystemUI 主线程读写）。 */
    private var staleHits = 0

    /** 辅通道：状态控制器状态变化后记录 isExpanded（**仅诊断，不驱动**）。 */
    private fun hookStateControllerDiag(cl: ClassLoader) {
        val cls = runCatching {
            XposedHelpers.findClassIfExists(STATE_CONTROLLER, cl)
        }.getOrNull()
        if (cls == null) {
            ModuleLog.i("未找到 $STATE_CONTROLLER（诊断项跳过）")
            return
        }
        try {
            XposedHelpers.findAndHookMethod(
                cls,
                "setState",
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val expanded = runCatching {
                            XposedHelpers.callMethod(param.thisObject, "isExpanded") as? Boolean
                        }.getOrNull() ?: return
                        // anyExpansion 语义，仅供参考；是否收起只认 mExpandedVisible
                        diag("setState → isExpanded=$expanded（anyExpansion，不驱动）")
                    }
                },
            )
            ModuleLog.i("控制中心探针已挂载 $STATE_CONTROLLER.setState（仅诊断）")
        } catch (t: Throwable) {
            ModuleLog.e("Hook $STATE_CONTROLLER.setState 失败", t)
        }
    }

    /** 附加通道：KeyguardUpdateMonitor 的 `onShadeExpandedChanged(boolean)`（**仅诊断**）。 */
    private fun hookKumCallbackDiag(cl: ClassLoader) {
        val cls = runCatching {
            XposedHelpers.findClassIfExists(KUM_CALLBACK, cl)
        }.getOrNull()
        if (cls == null) {
            ModuleLog.i("未找到 $KUM_CALLBACK（诊断项跳过）")
            return
        }
        try {
            XposedHelpers.findAndHookMethod(
                cls,
                "onShadeExpandedChanged",
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val expanded = param.args.getOrNull(0) as? Boolean ?: return
                        diag("onShadeExpandedChanged=$expanded（anyExpansion，不驱动）")
                    }
                },
            )
            ModuleLog.i("控制中心探针已挂载 $KUM_CALLBACK.onShadeExpandedChanged（仅诊断）")
        } catch (t: Throwable) {
            ModuleLog.e("Hook $KUM_CALLBACK.onShadeExpandedChanged 失败（诊断项跳过）", t)
        }
    }

    /** 单纯采集参数：语义确认前不参与驱动。 */
    private fun hookPanelInjectorDiag(cl: ClassLoader) {
        val cls = runCatching {
            XposedHelpers.findClassIfExists(PANEL_INJECTOR, cl)
        }.getOrNull() ?: return
        for (name in arrayOf("onControlCenterAppearChanged", "onNotificationAppearChanged")) {
            try {
                XposedHelpers.findAndHookMethod(
                    cls,
                    name,
                    Boolean::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            diag("$name args=${param.args?.joinToString()}")
                        }
                    },
                )
                ModuleLog.i("控制中心探针已挂载 $PANEL_INJECTOR.$name（仅诊断）")
            } catch (t: Throwable) {
                ModuleLog.e("Hook $PANEL_INJECTOR.$name 失败（仅诊断）", t)
            }
        }
    }

    /** 有上限、去重的诊断日志。 */
    private fun diag(detail: String) {
        if (diagLines >= MAX_DIAG_LINES) return
        diagLines++
        ModuleLog.i("控制中心探针: $detail")
    }

    /**
     * 驱动 [RingState]。
     *
     * [RingState.setShadeExpanded] 自身按值去重，这里只负责日志，
     * 因此自愈复位（RingState.resetCollapseInputs）之后探针仍能再次驱动。
     */
    private fun driveByValue(expanded: Boolean, source: String) {
        if (expanded == RingState.shadeExpandedNow) return
        ModuleLog.i("控制中心探针驱动: 面板展开=$expanded（来源=$source）")
        RingState.setShadeExpanded(expanded)
    }
}
