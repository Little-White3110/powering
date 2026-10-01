package com.powerring.hole.hook

import android.view.WindowInsets
import com.powerring.hole.core.ModuleLog
import com.powerring.hole.ring.RingState
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import de.robv.android.xposed.XposedHelpers

/**
 * 沉浸收起探针（兜底路径）。
 *
 * 真机实测结论（详见「挖孔环形电量LSP模块-可行性分析报告.md」）：
 * 1. 环窗口（type=2009）覆盖状态栏区域，系统不给它派发 statusBars insets，
 *    statusTop 恒为 0 —— 计划 Task 4 的 insets 检测在本机不可用。
 * 2. 状态栏窗口**自身**的 onApplyWindowInsets 同样恒为
 *    visible=false / top=0 / mTopInset=0（它自己就是 inset 源），也不可用。
 * 3. 可用信号是系统对「状态栏窗口 shown/hidden」的显式通知：
 *    StatusBarWindowStateController 的 CommandQueue 回调 `setWindowState(III)`，
 *    参数为 (displayId, window, state)：window=1 即状态栏，
 *    state 取 0=SHOWING / 1=HIDING / 2=HIDDEN。
 *    真机复现序列 (0,1,2) → (0,1,0) → (0,1,2)，与图库 immersiveSticky 的
 *    「收起 → 点屏唤出 → 再收起」逐拍对应。
 *
 * 纪律：只 Hook **类自身声明**的方法。findAndHookMethod 会沿父类链解析，
 * 一旦落到 android.view.View 的方法上，就会在 SystemUI 里对每个 View 生效——
 * 既有误判风险，又会拖慢进程。setTranslationY / setVisibility 因此被刻意排除。
 */
object ImmersiveProbeHook {

    private val VIEW_CANDIDATES = listOf(
        // 本机 17.03.260226.r 实测类名（work/sysui/classes3.dex）
        "com.android.systemui.statusbar.window.StatusBarWindowView",
        // 旧版本降级候选
        "com.android.systemui.statusbar.phone.StatusBarWindowView",
        "com.android.systemui.statusbar.phone.MiuiStatusBarWindowView",
    )

    /** 状态栏窗口控制器：其 refreshStatusBarHeight 反映窗口高度决策（仅诊断）。 */
    private const val CONTROLLER =
        "com.android.systemui.statusbar.window.StatusBarWindowControllerImpl"

    /** CommandQueue 回调：系统显式通知状态栏窗口 shown/hidden（驱动信号）。 */
    private const val STATE_CONTROLLER_CALLBACK =
        "com.android.systemui.statusbar.window.StatusBarWindowStateController\$commandQueueCallback\$1"

    /** android.app.StatusBarManager.WINDOW_STATUS_BAR */
    private const val WINDOW_STATUS_BAR = 1

    /** android.app.StatusBarManager.WINDOW_STATE_* */
    private const val STATE_SHOWING = 0
    private const val STATE_HIDING = 1
    private const val STATE_HIDDEN = 2

    /** 诊断日志总行数上限，任何异常情况下都不允许刷屏 */
    private const val MAX_DIAG_LINES = 120

    @Volatile
    private var installed = false

    // ---- 以下状态只在 SystemUI 主线程读写 ----

    /** 上次已上报给 RingState 的取值，避免重复调用 */
    private var lastDriven: Boolean? = null

    private var diagLines = 0

    // 各 Hook 上次的日志内容，用于抑制重复行
    private var lastInsets = ""
    private var lastWindowState = ""
    private var lastBarHeight = ""

    fun install(classLoader: ClassLoader) {
        if (installed) return
        installed = true

        val viewCls = VIEW_CANDIDATES.firstNotNullOfOrNull {
            runCatching { XposedHelpers.findClassIfExists(it, classLoader) }.getOrNull()
        }
        if (viewCls == null) {
            ModuleLog.i("沉浸探针：未找到状态栏窗口根 View 类（诊断项跳过）")
        } else {
            ModuleLog.i("沉浸探针：状态栏窗口根 View 类 = ${viewCls.name}")
            hookInsets(viewCls)
        }

        val ctrlCls = runCatching {
            XposedHelpers.findClassIfExists(CONTROLLER, classLoader)
        }.getOrNull()
        if (ctrlCls == null) {
            ModuleLog.i("沉浸探针：未找到 $CONTROLLER（诊断项跳过）")
        } else {
            hookBarHeight(ctrlCls)
        }

        val stateCls = runCatching {
            XposedHelpers.findClassIfExists(STATE_CONTROLLER_CALLBACK, classLoader)
        }.getOrNull()
        if (stateCls == null) {
            ModuleLog.e("未找到状态栏窗口状态回调类，沉浸收起检测未生效", null)
        } else {
            hookWindowState(stateCls)
        }
    }

    // ---- 仅诊断：状态栏窗口自身的 insets 派发（本机实测恒不可见，故不驱动） ----

    private fun hookInsets(cls: Class<*>) {
        try {
            XposedHelpers.findAndHookMethod(
                cls, "onApplyWindowInsets", WindowInsets::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val insets = param.args[0] as? WindowInsets ?: return
                            val visible = runCatching {
                                insets.isVisible(WindowInsets.Type.statusBars())
                            }.getOrDefault(true)
                            val top = runCatching {
                                insets.getInsets(WindowInsets.Type.statusBars()).top
                            }.getOrDefault(-1)
                            val cutoutTop = runCatching {
                                insets.getInsets(WindowInsets.Type.displayCutout()).top
                            }.getOrDefault(-1)
                            val fieldTop = runCatching {
                                XposedHelpers.getIntField(param.thisObject, "mTopInset")
                            }.getOrDefault(Int.MIN_VALUE)
                            val key = "visible=$visible top=$top cutoutTop=$cutoutTop mTopInset=$fieldTop"
                            if (key != lastInsets) {
                                lastInsets = key
                                diag("insets", key)
                            }
                        } catch (t: Throwable) {
                            ModuleLog.e("沉浸探针 onApplyWindowInsets 异常", t)
                        }
                    }
                },
            )
            ModuleLog.i("沉浸探针已挂载 ${cls.name}.onApplyWindowInsets（仅诊断）")
        } catch (t: Throwable) {
            ModuleLog.e("Hook ${cls.name}.onApplyWindowInsets 失败", t)
        }
    }

    // ---- 仅诊断：状态栏窗口高度决策 ----

    private fun hookBarHeight(cls: Class<*>) {
        try {
            XposedHelpers.findAndHookMethod(
                cls, "refreshStatusBarHeight",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val h = runCatching {
                                XposedHelpers.getIntField(param.thisObject, "mBarHeight")
                            }.getOrDefault(Int.MIN_VALUE)
                            val key = "mBarHeight=$h"
                            if (key != lastBarHeight) {
                                lastBarHeight = key
                                diag("refreshStatusBarHeight", key)
                            }
                        } catch (t: Throwable) {
                            ModuleLog.e("沉浸探针 refreshStatusBarHeight 异常", t)
                        }
                    }
                },
            )
            ModuleLog.i("沉浸探针已挂载 ${cls.name}.refreshStatusBarHeight（仅诊断）")
        } catch (t: Throwable) {
            ModuleLog.e("Hook ${cls.name}.refreshStatusBarHeight 失败", t)
        }
    }

    // ---- 驱动信号：系统显式通知状态栏窗口 shown/hidden ----

    private fun hookWindowState(cls: Class<*>) {
        try {
            XposedHelpers.findAndHookMethod(
                cls, "setWindowState",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val displayId = param.args[0] as Int
                            val window = param.args[1] as Int
                            val state = param.args[2] as Int

                            // 读回宿主控制器的 windowState 做交叉验证：该字段只跟随状态栏
                            val host = runCatching {
                                XposedHelpers.getObjectField(param.thisObject, "this\$0")
                            }.getOrNull()
                            val cur = if (host == null) Int.MIN_VALUE else runCatching {
                                XposedHelpers.getIntField(host, "windowState")
                            }.getOrDefault(Int.MIN_VALUE)

                            val key = "displayId=$displayId window=$window state=$state windowState=$cur"
                            if (key != lastWindowState) {
                                lastWindowState = key
                                diag("setWindowState", key)
                            }

                            // 只跟随状态栏窗口；导航栏等其它窗口一律忽略
                            if (window != WINDOW_STATUS_BAR) return
                            val collapsed = when (state) {
                                STATE_SHOWING -> false
                                STATE_HIDING, STATE_HIDDEN -> true
                                // 未知取值不冒险，保持现状
                                else -> return
                            }
                            drive(collapsed)
                        } catch (t: Throwable) {
                            ModuleLog.e("沉浸探针 setWindowState 异常", t)
                        }
                    }
                },
            )
            ModuleLog.i("沉浸探针已挂载 ${cls.name}.setWindowState（驱动信号）")
        } catch (t: Throwable) {
            ModuleLog.e("Hook ${cls.name}.setWindowState 失败", t)
        }
    }

    /** 有上限、去重的诊断日志。 */
    private fun diag(source: String, detail: String) {
        if (diagLines >= MAX_DIAG_LINES) return
        diagLines++
        ModuleLog.i("沉浸探针[$source]: $detail")
    }

    /**
     * 驱动 [RingState]。
     *
     * 为什么不需要 armed 门控：本信号是系统对「状态栏窗口 shown/hidden」的显式
     * 双向通知（state=0 必定恢复），不存在 insets 那种"信号结构性恒为 0、
     * 一旦采信就把环永久藏掉"的风险。若某机型根本不派发该回调，则本探针从不
     * 调用 RingState，环保持常显——安全降级。
     */
    private fun drive(collapsed: Boolean) {
        if (collapsed == lastDriven) return
        lastDriven = collapsed
        ModuleLog.i("沉浸探针驱动: collapsed=$collapsed")
        RingState.setStatusBarCollapsed(collapsed)
    }
}
