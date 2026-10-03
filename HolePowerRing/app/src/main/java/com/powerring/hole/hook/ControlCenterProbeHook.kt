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
 * 控制中心（HyperOS 右侧 QS 面板）显隐探针。
 *
 * ## 为什么要单独一个探针（2026-10-03 用户反馈「控制中心没效果」）
 *
 * HyperOS 把下拉面板拆成了**两块独立的面板**：
 * - **通知中心**（左侧）：AOSP 的 `NotificationPanelViewController` +
 *   `ShadeControllerImpl.mExpandedVisible`，由 [ShadeProbeHook] 负责；
 * - **控制中心**（右侧）：MIUI 自己的 `com.miui.systemui.controlcenter.*`，
 *   与上面那套完全无关。它有自己的展开控制器
 *   `ControlCenterExpandControllerDelegate`（持有 `_visibleState` /
 *   `_expansionState` 两条 StateFlow），控制中心的展开/收起**不会**动
 *   `mExpandedVisible`。
 *
 * 所以只挂 `ShadeControllerImpl` 时：通知中心下拉能收起环，控制中心下拉毫无反应
 * —— 正是用户看到的现象。这里补上控制中心这一路。
 *
 * ## 信号选取
 *
 * 类名来自真机 SystemUI dex 实测（见 work/sysui，`com.miui.systemui.controlcenter.*`
 * 全部在宿主 classes3.dex 里，**不是插件**，因此可以安全地用宿主 ClassLoader
 * `findClassIfExists` 直接拿类）：
 *
 * - `ControlCenterExpandControllerDelegate.onVisibleChanged(Z)`：面板「可见」的一等事件；
 * - `ControlCenterExpandControllerDelegate.onExpansionChanged(F)`：展开比例（第二通道）；
 * - 自愈轮询：每 600ms 回读 `_visibleState`（Kotlin StateFlow）或转发持有的
 *   `PanelExpandController.getVisible()`，漏事件也能在 1 秒内对齐。
 *
 * 降级：类/字段都拿不到时本探针从不调用 RingState，环保持常显——安全。
 */
object ControlCenterProbeHook {

    /** 控制中心展开控制器（宿主类）：可见性与展开比例都在这里 */
    private const val DELEGATE_CLASS =
        "com.miui.systemui.controlcenter.container.ControlCenterExpandControllerDelegate"

    /** 内部 MutableStateFlow<Boolean>：面板是否可见 */
    private const val FIELD_VISIBLE_STATE = "_visibleState"

    /** 转发持有的展开控制器接口（面板真正实现它，可取 getVisible()） */
    private const val FIELD_EXPAND_CONTROLLER = "expandController"

    /** 自愈轮询间隔（ms） */
    private const val POLL_MS = 600L

    /** 展开比例判据：超过它算「面板已经拉开」 */
    private const val EXPAND_OPEN_FRACTION = 0.55f

    /** 展开比例判据：低于它算「面板已经收起」 */
    private const val EXPAND_CLOSE_FRACTION = 0.05f

    /** 诊断日志上限，异常情况下不许刷屏 */
    private const val MAX_DIAG_LINES = 60

    @Volatile
    private var installed = false

    // ---- 以下状态只在 SystemUI 主线程读写 ----

    private var delegate: WeakReference<Any>? = null

    private var diagLines = 0

    private val handler = Handler(Looper.getMainLooper())

    private val pollRunnable = object : Runnable {
        override fun run() {
            try {
                if (RingState.screenOn) {
                    val v = readVisible()
                    if (v != null) drive(v, "poll")
                }
            } catch (t: Throwable) {
                ModuleLog.e("控制中心探针轮询异常", t)
            }
            handler.postDelayed(this, POLL_MS)
        }
    }

    fun install(classLoader: ClassLoader) {
        if (installed) return
        installed = true

        val cls = runCatching {
            XposedHelpers.findClassIfExists(DELEGATE_CLASS, classLoader)
        }.getOrNull()
        if (cls == null) {
            ModuleLog.e(
                "未找到 $DELEGATE_CLASS，控制中心展开检测不可用" +
                    "（下拉控制中心不会收起圆环，其它功能不受影响）",
                null,
            )
            return
        }

        hookMethod(cls, "onVisibleChanged", Boolean::class.javaPrimitiveType) { param ->
            val d = param.thisObject
            delegate = WeakReference(d)
            val visible = param.args.getOrNull(0) as? Boolean ?: return@hookMethod
            diag("onVisibleChanged=$visible")
            drive(visible, "onVisibleChanged")
        }
        hookMethod(cls, "onExpansionChanged", Float::class.javaPrimitiveType) { param ->
            val d = param.thisObject
            delegate = WeakReference(d)
            val frac = param.args.getOrNull(0) as? Float ?: return@hookMethod
            diag("onExpansionChanged=$frac")
            // 第二通道：比例明显拉开/完全收起时也驱动一次（可见事件漏了也能兜住）
            when {
                frac >= EXPAND_OPEN_FRACTION -> drive(true, "onExpansionChanged")
                frac <= EXPAND_CLOSE_FRACTION -> drive(false, "onExpansionChanged")
            }
        }
        // 尽早捕获实例（初始化时系统会注入展开控制器）：即使一次显隐事件都没来，
        // 轮询也能从它身上回读状态
        for (init in arrayOf("setExpandController", "onSnapshot")) {
            for (m in cls.declaredMethods) {
                if (m.name != init) continue
                runCatching {
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            delegate = WeakReference(param.thisObject)
                        }
                    })
                }
            }
        }

        handler.removeCallbacks(pollRunnable)
        handler.postDelayed(pollRunnable, POLL_MS)
        ModuleLog.i("控制中心探针已挂载 $DELEGATE_CLASS（事件 + 自愈轮询 ${POLL_MS}ms）")
    }

    /** 挂一个「按参数类型筛重载」的方法；paramType 为 null 时挂无参方法。 */
    private fun hookMethod(
        cls: Class<*>,
        name: String,
        paramType: Class<*>?,
        body: (MethodHookParam) -> Unit,
    ) {
        val methods = cls.declaredMethods.filter {
            it.name == name &&
                if (paramType == null) it.parameterTypes.isEmpty()
                else it.parameterTypes.size == 1 && it.parameterTypes[0] == paramType
        }
        if (methods.isEmpty()) {
            ModuleLog.i("控制中心探针：$DELEGATE_CLASS.$name 未找到（跳过该通道）")
            return
        }
        for (m in methods) {
            runCatching {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        runCatching { body(param) }
                    }
                })
            }.onFailure { ModuleLog.e("Hook $DELEGATE_CLASS.$name 失败", it) }
        }
    }

    /** 立刻按实时值对齐一次（屏幕点亮 / 解锁完成时由 RingState 请求）。 */
    fun reconcile() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { reconcile() }
            return
        }
        val v = readVisible() ?: return
        drive(v, "reconcile")
    }

    /**
     * 回读「控制中心是否可见」。
     *
     * 优先读控制器自己的 `_visibleState`（Kotlin StateFlow，宿主类，最稳）；
     * 退而读它转发的 `expandController.getVisible()`。都拿不到返回 null（不作判断）。
     */
    private fun readVisible(): Boolean? {
        val d = delegate?.get() ?: return null
        runCatching {
            val flow = XposedHelpers.getObjectField(d, FIELD_VISIBLE_STATE)
            (XposedHelpers.callMethod(flow, "getValue") as? Boolean)?.let { return it }
        }
        runCatching {
            val ec = XposedHelpers.getObjectField(d, FIELD_EXPAND_CONTROLLER) ?: return@runCatching
            (XposedHelpers.callMethod(ec, "getVisible") as? Boolean)?.let { return it }
        }
        return null
    }

    /** 有上限、去重的诊断日志（长这样：控制中心探针: onVisibleChanged=true） */
    private fun diag(detail: String) {
        if (diagLines >= MAX_DIAG_LINES) return
        diagLines++
        ModuleLog.i("控制中心探针: $detail")
    }

    /**
     * 驱动 [RingState]。
     *
     * 与 ShadeProbeHook 同构：去重基准取 RingState 上的实时值，
     * 因此自愈复位之后探针仍能再次驱动同一取值。
     */
    private fun drive(showing: Boolean, source: String) {
        if (showing == RingState.controlCenterShowingNow) return
        ModuleLog.i("控制中心探针驱动: 显示=$showing（来源=$source）")
        RingState.setControlCenterShowing(showing)
    }
}
