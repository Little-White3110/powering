package com.powerring.hole.ring

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.os.SystemClock
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.graphics.Canvas
import android.view.Surface
import android.view.View
import com.powerring.hole.core.HookPrefs
import com.powerring.hole.core.ModuleLog
import java.util.Collections
import java.util.WeakHashMap

/**
 * 环形电量的全局状态（SystemUI 进程单例）。
 *
 * 负责：配置缓存、电池状态、挖孔 View 注册、收起与环色动画调度。
 * 绘制本身委托给 [RingRenderer]。
 */
object RingState {

    /** 挖孔装饰 View（弱引用，View 销毁后自动移除） */
    private val cutoutViews: MutableSet<View> =
        Collections.newSetFromMap(WeakHashMap())

    @Volatile
    var appContext: Context? = null
        private set

    // ---- 电池状态 ----
    @Volatile var level: Int = 50
        private set
    @Volatile var charging: Boolean = false
        private set
    @Volatile var powerSave: Boolean = false
        private set
    @Volatile var screenOn: Boolean = true
        private set

    // ---- 收起通路（沉浸收起 / 灵动岛显示，共用一条动画通道） ----

    /** 收起进度：0f 完整显示，1f 完全收缩不可见（渲染端按此收缩半径并衰减透明度） */
    @Volatile
    var collapseProgress: Float = 0f
        private set

    /** 最近一次系统上报的状态栏收起状态（沉浸模式） */
    @Volatile
    private var statusBarCollapsed: Boolean = false

    /** 最近一次上报的灵动岛显示状态（由 IslandVisibilityHook 驱动，渲染侧只读） */
    @Volatile
    var islandShowing: Boolean = false
        private set

    private var collapseAnimator: ValueAnimator? = null
    private val collapseInterpolator = DecelerateInterpolator(1.5f)
    private val COLLAPSE_DURATION_MS = 260L

    /** 挖孔填充色（= 状态栏图标色），决定使用浅色还是深色令牌 */
    @Volatile
    var tintColor: Int = 0xFFFFFFFF.toInt()
        private set
    @Volatile
    private var tintInitialized = false

    /** 是否曾经成功解析到挖孔几何——决定能否安全隐藏原电池图标 */
    @Volatile
    var cutoutEverResolved: Boolean = false
        private set

    /**
     * 连 `DarkIconDispatcherImpl.getTintAnimationDuration()` 都拿不到时的最后兜底。
     * 250ms 是 MIUI 状态栏图标反色的常见观感值，仅在系统完全不可读时使用；
     * 正常路径下 [tintAnimationDurationMs] 会被系统真实时长覆盖。
     */
    private const val FALLBACK_TINT_DURATION_MS = 250L

    // ---- 系统电池图标调色板（跟随原生图标颜色） ----

    /**
     * 系统电池图标调色板；[BatteryPalette.ready] 为 false 时
     * [RingRenderer] 回退到 [MiuixPalette] 的固定语义色。
     */
    @Volatile
    var batteryPalette: BatteryPalette = BatteryPalette.EMPTY
        private set

    /**
     * 普通态**当前实际显示**的颜色。
     * 与 [batteryPalette.normal] 的区别：本字段可能处于过渡动画中间值，
     * [RingRenderer] 在普通态分支读的是本字段。
     */
    @Volatile
    var normalColor: Int = 0xFFFFFFFF.toInt()
        private set

    /** 反色过渡时长，优先取系统 LightBarTransitionsController 的常量，保证节奏一致。 */
    @Volatile
    var tintAnimationDurationMs: Long = FALLBACK_TINT_DURATION_MS
        private set

    private var colorAnimator: ValueAnimator? = null
    private val argbEvaluator = ArgbEvaluator()
    private var lastSystemColors: SystemBatteryColors? = null
    private var lastDarkIntensity: Float = Float.NaN

    /**
     * 「岛期间冻结普通态跟随」是否生效。
     * 生效条件：灵动岛显示 且 「有岛时隐藏圆环」关闭（islandShowing && !collapseOnIsland）。
     * 冻结期间系统每帧算出的新目标色只记入 [lastSystemNormalTarget]，不驱动 normalColor，
     * 避免跟随反色动画中间值时环色来回抽搐。只在 SystemUI 主线程读写。
     */
    private var islandColorFrozen = false

    /** 最近一次由系统色算出的普通态目标色；解锁时一次性补回。 */
    private var lastSystemNormalTarget: Int? = null

    /** 诊断日志节流：反色动画期间 onDarkChanged 每帧回调，逐帧打日志会刷屏。 */
    private var lastColorLogMs = 0L

    val config: RingConfig
        get() = HookPrefs.get()

    /** 上一帧使用的配置签名，变化时补发一次重绘让静态画面也能即时响应调节 */
    @Volatile
    private var lastConfigSig: String = ""

    private fun RingConfig.signature() =
        "$ringEnabled|$hideOnScreenshot|$strokeWidthDp|$offsetXDp|$offsetYDp|$scale|" +
            "$colorMode|$customColor|" +
            "$collapseOnImmersive|$collapseOnIsland|" +
            "${stateColors.normal},${stateColors.low},${stateColors.powerSave}," +
            "${stateColors.performance},${stateColors.charging}|" +
            CustomColors.encodeRanges(levelRanges)

    // ---- 生命周期 ----

    fun attachContext(context: Context) {
        appContext = context.applicationContext
        HookPrefs.hostContext = appContext
    }

    fun attachCutoutView(view: View) {
        cutoutViews.add(view)
    }

    fun detachCutoutView(view: View) {
        cutoutViews.remove(view)
    }

    // ---- 状态更新 ----

    fun setTintColor(color: Int) {
        if (!tintInitialized || tintColor != color) {
            tintColor = color
            tintInitialized = true
            invalidateAll()
        }
    }

    fun updateBattery(level: Int, charging: Boolean, powerSave: Boolean) {
        val changed = this.level != level || this.charging != charging || this.powerSave != powerSave
        this.level = level.coerceIn(0, 100)
        this.charging = charging
        this.powerSave = powerSave
        if (changed) {
            ModuleLog.i("电池状态: level=${this.level} charging=$charging powerSave=$powerSave")
            invalidateAll()
        }
    }

    fun setScreenOn(on: Boolean) {
        if (screenOn != on) {
            screenOn = on
            ModuleLog.i("屏幕状态: screenOn=$on")
            invalidateAll()
        }
    }

    /**
     * 由 BatteryColorHook 在捕获到系统真实时长后调用；非法值忽略。
     *
     * 来源是 `DarkIconDispatcherImpl.getTintAnimationDuration()`，
     * 按 `ComputilityUtils` 设备档位计算，不是编译期常量。
     */
    fun setTintAnimationDuration(ms: Long) {
        if (ms <= 0) return
        if (ms != tintAnimationDurationMs) {
            tintAnimationDurationMs = ms
            ModuleLog.i("环色过渡时长更新为 ${ms}ms（取自系统）")
        }
    }

    /**
     * 写入系统电池图标的颜色与状态位。
     *
     * 普通态颜色的过渡分两种情况，这是"跟手又不突兀"的关键：
     * - [SystemBatteryColors.darkIntensity] 变化：说明系统反色动画正在推进，
     *   此时算出的目标色本身就是连续的，直接跟随，**不叠加**二次动画
     *   （叠加会慢一拍，反而与状态栏错位）；
     * - darkIntensity 没变、但浅/深色目标被换掉（主题切换、tint 区域变化）：
     *   用与系统相同长度的 Argb 过渡补一次，避免硬切。
     */
    fun setSystemBatteryColors(colors: SystemBatteryColors) {
        val last = lastSystemColors
        if (last == colors) return
        lastSystemColors = colors

        val intensity = colors.darkIntensity.coerceIn(0f, 1f)
        // 时钟路径每帧来，此时 intensity 本身就是连续的插值结果；
        // 渲染路径是离散跳变，需要自己补一次等长过渡。
        val continuous = colors.fromClock
        val intensityMoved = lastDarkIntensity.isNaN() || intensity != lastDarkIntensity
        lastDarkIntensity = intensity

        // 与系统 onDarkChangeInternal 一致：useTint 时用 tint 色作浅色端
        val base = if (colors.useTint && colors.tint != 0) colors.tint else colors.light
        val target = argbEvaluator.evaluate(intensity, base, colors.dark) as Int

        batteryPalette = BatteryPalette(
            ready = true,
            normal = target,
            low = colors.low,
            powerSave = colors.powerSave,
            performance = colors.performance,
            charging = colors.charging,
            chargingNow = colors.chargingNow,
            performanceNow = colors.performanceNow,
            powerSaveNow = colors.powerSaveNow,
            lowNow = colors.lowNow,
            darkIntensity = intensity,
        )

        lastSystemNormalTarget = target
        if (islandColorFrozen) {
            // 岛显示期间不跟随：冻结/解锁切换见 applyIslandColorFreeze，解锁时补这一次动画。
        } else if (continuous || intensityMoved) {
            // 连续路径：目标色每帧都在变，直接跟随系统时钟，不叠加二次动画
            colorAnimator?.cancel()
            colorAnimator = null
            normalColor = target
        } else {
            animateNormalColorTo(target)
        }

        // 反色动画期间本方法每帧被调用，日志必须节流，否则一秒能刷出几十行
        val now = SystemClock.uptimeMillis()
        if (now - lastColorLogMs >= 1000L) {
            lastColorLogMs = now
            ModuleLog.i(
                "环色跟随系统电池图标: di=$intensity src=${if (continuous) "clock" else "view"} " +
                    "normal=#${hex(target)} " +
                    "low=#${hex(colors.low)} save=#${hex(colors.powerSave)} " +
                    "perf=#${hex(colors.performance)} charge=#${hex(colors.charging)} " +
                    "flags(charge=${colors.chargingNow},perf=${colors.performanceNow}," +
                    "save=${colors.powerSaveNow},low=${colors.lowNow})",
            )
        }
        invalidateAll()
    }

    /** 「取消旧动画 → 平滑过渡」：环色过渡统一走这一条通道。 */
    private fun animateNormalColorTo(target: Int) {
        val start = normalColor
        colorAnimator?.let { if (it.isRunning) it.cancel() }
        if (start == target) return
        colorAnimator = ValueAnimator.ofObject(argbEvaluator, start, target).apply {
            duration = tintAnimationDurationMs
            // 系统 LightBarTransitionsController.animateIconTint 用的是 Interpolators.LINEAR，
            // 曲线形状保持一致，环与状态栏图标才会严丝合缝地同步变色
            interpolator = LinearInterpolator()
            addUpdateListener {
                normalColor = it.animatedValue as Int
                invalidateAll()
            }
        }.also { it.start() }
    }

    private fun hex(color: Int): String = String.format("%08X", color)

    /**
     * 状态栏是否被系统自动收起（沉浸模式）。
     * 由 ImmersiveProbeHook 的系统窗口状态回调驱动（环窗口自身不派发
     * statusBars insets，见 ImmersiveProbeHook 文件头），在 SystemUI 主线程调用。
     */
    fun setStatusBarCollapsed(collapsed: Boolean) {
        if (statusBarCollapsed == collapsed) return
        statusBarCollapsed = collapsed
        val target = collapseTarget()
        ModuleLog.i("状态栏沉浸收起状态: collapsed=$collapsed 岛显示=$islandShowing 收起目标=$target")
        animateCollapseTo(target)
    }

    /**
     * 灵动岛（超级岛）是否正在显示。
     *
     * 与沉浸收起共用同一条 collapseProgress 动画通道：任一条件成立即收缩到 1f，
     * 两者都不成立才弹回。两个开关互不影响，各自控制自己的场景。
     */
    fun setIslandShowing(showing: Boolean) {
        if (islandShowing == showing) return
        islandShowing = showing
        val target = collapseTarget()
        ModuleLog.i("灵动岛显示状态: showing=$showing 沉浸=$statusBarCollapsed 收起目标=$target")
        animateCollapseTo(target)
        applyIslandColorFreeze()
    }

    /**
     * 岛色冻结的进出场。
     *
     * 进场：cancel 正在跑的跟随动画后**立即定格**为守卫判定的颜色（近黑→纯白，
     *       本就非近黑则一动不动，用户规则：不是黑的就不修改）。
     *       不能用动画滑过去：RingRenderer 每帧还会对渲染色套 [IslandColorGuard]
     *       兜底自定义配色，动画前段 ≤64 的中间值被守卫顶成白、越过阈值又掉回
     *       深灰，肉眼就是「闪一下、颜色不对」。定格后 normalColor 已是纯白，
     *       守卫对它是 no-op，两层不再重叠。
     * 退场：动画回到最近一次系统目标色（冻结期间记下的 [lastSystemNormalTarget]），
     *       此时岛已消失、守卫停判，动画全程干净。
     * 与 collapseOnIsland 开关联动：配置翻转时也应重算（onCutoutDraw 的签名变化分支会调用）。
     */
    private fun applyIslandColorFreeze() {
        val shouldFreeze = islandShowing && !config.collapseOnIsland
        if (shouldFreeze == islandColorFrozen) return
        islandColorFrozen = shouldFreeze
        if (shouldFreeze) {
            colorAnimator?.cancel()
            colorAnimator = null
            val target = IslandColorGuard.ensureVisible(
                normalColor,
                islandShowing = true,
                collapseOnIsland = false,
            )
            ModuleLog.i(
                "岛色冻结: current=#${hex(normalColor)} target=#${hex(target)} " +
                    "paletteNormal=#${hex(batteryPalette.normal)}",
            )
            if (target != normalColor) {
                normalColor = target
                invalidateAll()
            }
        } else {
            val back = lastSystemNormalTarget
            ModuleLog.i("岛色解锁: current=#${hex(normalColor)} back=${if (back == null) "null" else "#${hex(back)}"}")
            if (back != null) animateNormalColorTo(back)
        }
    }

    /** 两条收起通路合并成一个目标值：沉浸收起、灵动岛显示，任一命中即收缩。 */
    private fun collapseTarget(): Float {
        val c = config
        val byImmersive = statusBarCollapsed && c.collapseOnImmersive
        val byIsland = islandShowing && c.collapseOnIsland
        return if (byImmersive || byIsland) 1f else 0f
    }

    /** 「取消旧动画→立即到位或平滑过渡」。 */
    private fun animateCollapseTo(target: Float) {
        val start = collapseProgress
        collapseAnimator?.let { if (it.isRunning) it.cancel() }
        // 两个收起开关都关掉时压根不需要播动画，直接到位
        val noCollapseFeature = !config.collapseOnImmersive && !config.collapseOnIsland
        if (noCollapseFeature || kotlin.math.abs(target - start) < 0.01f) {
            collapseProgress = target
            invalidateAll()
            return
        }
        collapseAnimator = ValueAnimator.ofFloat(start, target).apply {
            duration = COLLAPSE_DURATION_MS
            interpolator = collapseInterpolator
            addUpdateListener {
                collapseProgress = it.animatedValue as Float
                invalidateAll()
            }
        }.also { it.start() }
    }

    /** 挖孔几何首次解析成功的回调（由隐藏 Hook 注册，用于时序补偿）。 */
    @Volatile
    var onCutoutResolved: (() -> Unit)? = null

    /**
     * 配置签名变化的回调（主线程，onCutoutDraw 签名分支内触发）。
     * 窗口 flags 类配置（如截图隐藏的 FLAG_SECURE）必须在主线程
     * updateViewLayout，不能走绘制路径——由 RingWindowController 注册。
     */
    @Volatile
    var onConfigApplied: (() -> Unit)? = null

    /**
     * 每次挖孔绘制的回调（主线程）。供轻量的自愈式同步用：环窗口的
     * SurfaceControl 会在灭屏/旋转时被系统重建，SF 层的采集排除标志
     * 需要跟着新身份重打。实现方必须自己做去重，绝不能在这里做重活。
     */
    @Volatile
    var onCutoutFrame: (() -> Unit)? = null

    /**
     * 屏幕方向变化回调。与 [onCutoutResolved] 同一模式：由 SystemUiHooks 接到
     * `BatteryHideHook.refreshAll()`，data 层不直接依赖 hook 层。
     */
    @Volatile
    var onOrientationChanged: (() -> Unit)? = null

    /** 由 BatteryObserver 在 ACTION_CONFIGURATION_CHANGED 时调用。 */
    fun notifyOrientationChanged() {
        invalidateAll()
        try {
            onOrientationChanged?.invoke()
        } catch (t: Throwable) {
            ModuleLog.e("方向变化回调异常", t)
        }
    }

    fun markCutoutResolved() {
        if (!cutoutEverResolved) {
            cutoutEverResolved = true
            ModuleLog.i("已确认挖孔几何可用，允许隐藏原电池图标")
            try {
                onCutoutResolved?.invoke()
            } catch (t: Throwable) {
                ModuleLog.e("挖孔就绪回调异常", t)
            }
        }
    }

    /**
     * 该 View 所在屏幕当前是否横屏。
     *
     * 判据取 `View.getDisplay().getRotation()`：横屏时挖孔安全区换到屏幕侧边，
     * 顶部环窗口的几何不再成立、圆环必然画在窗口外，此时不该继续隐藏原生图标。
     * 用 View 自己的 display 而不是全局状态：状态栏窗口与应用窗口的方向可能不一致。
     *
     * display 取不到（View 尚未 attach）时退回配置方向；再取不到按竖屏处理（保持现状）。
     */
    @Suppress("DEPRECATION")
    fun isLandscape(view: View?): Boolean {
        if (!config.restoreBatteryOnLandscape) return false
        return try {
            val rotation = view?.display?.rotation
            if (rotation != null) {
                rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270
            } else {
                val ctx = appContext ?: view?.context
                ctx?.resources?.configuration?.orientation == Configuration.ORIENTATION_LANDSCAPE
            }
        } catch (t: Throwable) {
            // 判定失败一律按竖屏走，宁可维持原有隐藏行为也不误改系统状态
            ModuleLog.e("横屏判定失败，按竖屏处理", t)
            false
        }
    }

    /**
     * 是否强制隐藏状态栏原电池图标：环开启 + 隐藏选项开启 + 挖孔确实存在，
     * 且当前不是横屏（横屏时环不可见，图标交还系统，见 [isLandscape]）。
     */
    fun shouldForceHideBattery(view: View?): Boolean {
        val c = config
        return c.ringEnabled && c.hideBattery && cutoutEverResolved && !isLandscape(view)
    }

    // ---- 绘制入口（由 Hook 在 DisplayCutoutBaseView.onDraw 后调用） ----

    fun onCutoutDraw(view: View, canvas: Canvas) {
        val c = config
        attachCutoutView(view)
        // 配置发生变化时补发一帧，使滑杆/颜色调节在静止状态下也即时可见
        val sig = c.signature()
        if (sig != lastConfigSig) {
            lastConfigSig = sig
            animateCollapseTo(collapseTarget())
            applyIslandColorFreeze()
            try {
                onConfigApplied?.invoke()
            } catch (t: Throwable) {
                ModuleLog.e("配置应用回调异常", t)
            }
            invalidateAll()
        }
        try {
            onCutoutFrame?.invoke()
        } catch (t: Throwable) {
            ModuleLog.e("环帧回调异常", t)
        }
        if (!c.ringEnabled || !screenOn) return
        try {
            RingRenderer.draw(view, canvas, c, this)
        } catch (t: Throwable) {
            // 绘制异常绝不能影响系统挖孔本身的渲染
            ModuleLog.e("环形电量绘制异常", t)
        }
    }

    /** 请求所有挖孔 View 重绘（postInvalidate 可在任意线程调用）。 */
    fun invalidateAll() {
        val snapshot = synchronized(cutoutViews) { cutoutViews.toList() }
        for (v in snapshot) {
            if (!v.isAttachedToWindow) {
                cutoutViews.remove(v)
                continue
            }
            v.postInvalidateOnAnimation()
        }
    }
}
