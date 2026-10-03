package com.powerring.hole.ring

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.KeyguardManager
import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
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
    @Volatile
    var charging: Boolean = false
        private set
    @Volatile var powerSave: Boolean = false
        private set

    /** 当前是否有音乐在播放（BatteryObserver 轮询 AudioManager 驱动音乐律动） */
    @Volatile
    var musicActive: Boolean = false
        private set
    @Volatile var screenOn: Boolean = true
        private set

    // ---- 呼吸 / 提醒闪烁 / 点击显示电量（v0.2 新增渲染驱动状态） ----

    /**
     * 当前通知栏是否存在可清除的未处理通知。
     *
     * 两条独立通道：
     * - 应用侧「通知使用权」通道（主通道，公开 API，跨版本稳定）
     * - SystemUI 侧反射 Hook（兜底，未授权或机型不匹配时才用得上）
     *
     * **权威优先（v0.7.0）**：一旦应用侧通道证明自己确实持有通知使用权
     * （[notifByAppAuthoritative]），就只认它、完全忽略反射兜底通道。因为反射通道
     * 只能「posted 加、removed 减」，漏一次 removed 就永久卡在「有通知」，
     * 表现为「通知早清了、环还在慢慢呼吸」；两通道取并集会把这种脏状态一直带下去。
     * 未授权时（拿不到公开 API）才退回「并集」。
     */
    val notificationsActive: Boolean
        get() = notifActiveByApp ||
            (!notifByAppAuthoritative && notifActiveByHook) ||
            SystemClock.uptimeMillis() < notifPreviewUntil

    /** 应用侧通道是否具备「权威」身份（= 本应用已拿到通知使用权） */
    @Volatile
    private var notifByAppAuthoritative: Boolean = false

    @Volatile
    private var notifActiveByApp: Boolean = false

    @Volatile
    private var notifActiveByHook: Boolean = false

    /**
     * 最近一条可提醒通知的到达时刻（uptimeMillis）。
     *
     * 用于「提醒随时间衰减」：通知刚到时闪得快、颜色鲜艳，停留越久越慢越淡。
     * uptimeMillis 是全系统统一时钟（跨进程一致），因此应用侧算出的时间
     * 可以直接和 SystemUI 侧的 now 相减。
     */
    @Volatile
    private var notifSinceMs: Long = 0L

    /** 提醒已存在多久（ms）；当前没有提醒时返回 0。 */
    fun notifAgeMs(): Long {
        if (!notificationsActive) return 0L
        val since = notifSinceMs
        if (since <= 0L) return 0L
        return (SystemClock.uptimeMillis() - since).coerceAtLeast(0L)
    }

    /** 是否知道最近一条提醒的到达时刻（进场快闪依赖它；Hook 兑底通道拿不到）。 */
    fun hasNotifAge(): Boolean = notificationsActive && notifSinceMs > 0L

    /**
     * 当前这一帧该不该播报「消息提醒」。
     *
     * 三层判定：
     * 1. 确实有可提醒通知（[notificationsActive]）；
     * 2. 当前屏幕点亮（[RingConfig.blinkOnScreen]）——息屏时环整体不绘制，不再播报；
     * 3. 持续方式：[RingConfig.BLINK_NOTIF_MODE_ALWAYS] 常驻（只要有未读就淡淡呼吸），
     *    [RingConfig.BLINK_NOTIF_MODE_TIMED] 只在通知到达后的限定秒数内播报。
     *
     * 渲染与帧调度必须共用这一个判据，否则会出现「不再重绘但画面停在提醒态」。
     */
    fun notifBlinkActive(c: RingConfig): Boolean {
        if (!c.blinkOnNotification || !notificationsActive) return false
        // v1.2.0：息屏提醒整块下线，息屏时不播报
        if (!screenOn || !c.blinkOnScreen) return false
        if (c.blinkNotifMode == RingConfig.BLINK_NOTIF_MODE_ALWAYS) return true
        // 预览通道必须完整播完，不受限时约束
        if (SystemClock.uptimeMillis() < notifPreviewUntil) return true
        val sec = c.blinkNotifDurationSeconds.coerceIn(
            RingConfig.NOTIF_DURATION_MIN, RingConfig.NOTIF_DURATION_MAX,
        )
        // 拿不到到达时刻时按「刚到」处理，宁可多播一会儿也不漏
        if (!hasNotifAge()) return true
        return notifAgeMs() < sec * 1000L
    }

    /**
     * Hook 兜底通道收到「新通知」时刷新到达时刻。
     *
     * Hook 侧拿不到系统的时间戳，只能用本机时钟打点；取最大值是为了不覆盖
     * 应用侧权威通道已经写入的、更准确的时刻。
     */
    fun onNotificationArrived() {
        val now = SystemClock.uptimeMillis()
        if (now <= notifSinceMs) return
        notifSinceMs = now
        invalidateAll()
    }

    /** 设置页「预览提醒效果」的临时状态截止时刻；不影响真实通知标志位 */
    @Volatile
    private var notifPreviewUntil: Long = 0L

    /** 设置页「预览音乐律动」的临时状态截止时刻 */
    @Volatile
    private var musicPreviewUntil: Long = 0L

    /** 百分比数字显示截止时刻（uptimeMillis），0 表示未显示 */
    @Volatile
    var percentFlashUntil: Long = 0L
        private set

    // ---- 收起通路（沉浸收起 / 灵动岛显示，共用一条动画通道） ----

    /** 收起进度：0f 完整显示，1f 完全收缩不可见（渲染端按此收缩半径并衰减透明度） */
    @Volatile
    var collapseProgress: Float = 0f
        private set

    /** 最近一次系统上报的状态栏收起状态（沉浸模式） */
    @Volatile
    private var statusBarCollapsed: Boolean = false

    /** 最近一次上报的面板（通知栏/控制中心）拉起进度，0f 收起 / 1f 完全展开 */
    @Volatile
    private var shadeCollapseFraction: Float = 0f

    /** 只读快照：沉浸收起探针据此做「值未变则不驱动」，自愈复位后仍能再次驱动。 */
    val statusBarCollapsedNow: Boolean get() = statusBarCollapsed

    /** 最近一次上报的灵动岛显示状态（由 IslandVisibilityHook 驱动，渲染侧只读） */
    @Volatile
    var islandShowing: Boolean = false
        private set

    private var collapseAnimator: ValueAnimator? = null
    private val collapseInterpolator = DecelerateInterpolator(1.5f)

    /**
     * 「环回场时先等电池淡出」的延迟（ms，v1.3.2）。
     *
     * 用户反馈：上划收起面板很慢时（尤其最后一段），圆环和原生电池图标会
     * **同框**出现。根因是电池淡出（260ms）与环回场（300ms）各自跑各自的动画，
     * 中间那段两者都在屏幕上。现在环回场延后这么久再开始，让电池先退干净。
     */
    private const val COLLAPSE_RETURN_DELAY_MS = 220L

    /**
     * 收起动画「跑到头」时回调（v1.3.2）。
     *
     * 电池图标是否隐藏现在要等环**真的收完**才放开（见 [shouldForceHideBattery]），
     * 因此动画结束这一刻必须通知电池侧重新评估一次，否则图标会一直不回来。
     * 由 SystemUiHooks 接到 `BatteryHideHook.refreshAll()`。
     */
    @Volatile
    var onCollapseSettled: (() -> Unit)? = null

    private fun notifyCollapseSettled() {
        try {
            onCollapseSettled?.invoke()
        } catch (t: Throwable) {
            ModuleLog.e("收起完成回调异常", t)
        }
    }

    /**
     * 收起 / 弹回动画时长（ms）。
     * v1.2.0：260 → 300。用户反馈控制中心出现时「太突兀」，稍长一点的收缩
     * 让圆环的退场更从容（与原生电池图标的淡入淡出放在一起看更协调）。
     */
    private val COLLAPSE_DURATION_MS = 300L

    /**
     * 控制中心 / 通知面板是否展开（由 ShadeProbeHook 驱动）。
     * 展开时环收起并把原生电池图标交还系统，见 [setShadeExpanded]。
     */
    @Volatile
    private var shadeExpanded: Boolean = false

    /** 只读快照：控制中心探针据此做「值未变则不驱动」并配合自愈轮询。 */
    val shadeExpandedNow: Boolean get() = shadeExpanded

    /**
     * 控制中心（HyperOS 右侧 QS 面板）是否正在显示。
     *
     * 与 [shadeExpanded]（通知中心）是两条独立输入：HyperOS 把两块面板拆成了
     * 两个控制器，信号源也不同。由 hook/ControlCenterProbeHook 驱动。
     */
    @Volatile
    private var controlCenterShowing: Boolean = false

    /** 只读快照：控制中心探针去重与自愈轮询用。 */
    val controlCenterShowingNow: Boolean get() = controlCenterShowing

    /** 控制中心展开状态变化回调（hook 层据此交还/收回原生电池图标）。 */
    @Volatile
    var onShadeExpandedChanged: (() -> Unit)? = null

    // ---- 锁屏（v1.3.3：保留系统原锁屏，只去掉挖孔圆环） ----

    /**
     * 锁屏是否正在显示（v1.3.3）。
     *
     * 由看门狗低频回读 `KeyguardManager.isKeyguardLocked()`（[syncLockScreenState]）
     * 驱动，用于「锁屏时隐藏圆环」选项：勾选后锁屏上把环收起、并把原生电池图标
     * 交还系统，于是**保留系统原锁屏**、只在锁屏上去掉挖孔圆环；解锁后环自动回来。
     */
    @Volatile
    private var lockScreenShowing: Boolean = false

    /** 只读快照：看门狗据此做「值未变则不驱动」。 */
    val lockScreenShowingNow: Boolean get() = lockScreenShowing

    /**
     * 锁屏显隐变化时调用：刷新收起目标（勾选「锁屏时隐藏圆环」时收起）并重绘。
     */
    fun setLockScreenShowing(showing: Boolean) {
        if (lockScreenShowing == showing) return
        lockScreenShowing = showing
        ModuleLog.i("锁屏显示状态: showing=$showing 收起目标=${collapseTarget()}")
        animateCollapseTo(collapseTarget())
        invalidateAll()
        try {
            // 锁屏隐藏期间原生电池要交还系统（见 [shouldForceHideBattery]），踢一次电池侧
            onShadeExpandedChanged?.invoke()
        } catch (t: Throwable) {
            ModuleLog.e("锁屏显示回调异常", t)
        }
    }

    /**
     * 回读一次锁屏状态（公开 API [KeyguardManager.isKeyguardLocked]，跨版本稳定）。
     *
     * 由看门狗低频调用；同时在「屏幕点亮 / 解锁完成」这两个稳定时刻主动调一次，
     * 保证解锁进桌面后瞬间就能拿到「已不锁屏」，不会再拖成延迟显示。
     */
    fun syncLockScreenState() {
        val ctx = appContext ?: return
        val km = runCatching {
            ctx.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        }.getOrNull() ?: return
        val locked = runCatching { km.isKeyguardLocked }.getOrDefault(false)
        setLockScreenShowing(locked)
    }

    // ---- 动效淡入淡出包络（v1.2.0） ----

    /**
     * 提醒 / 音乐律动这两个「外来效果」的淡入淡出进度（0–1）。
     *
     * 用户反馈「各种通知和音乐律动效果到正常变化太突兀」，因此不再让效果直接
     * 硬切上/下场：效果出现时从 0 平滑升到 1，消失时从 1 平滑降回 0，渲染端按
     * 这个值把「效果色」与「正常环色」做插值。这样通知来/走、音乐开始/停止
     * 都是渐变而不是闪切。
     *
     * 时间驱动的推进放在 [effectMix] 里（每次绘制调用一次），
     * [effectTransitioning] 负责在过渡期间让入口持续产帧。
     */
    private const val EFFECT_FADE_MS = 460L

    @Volatile
    private var effectMixFrom: Float = 0f
    @Volatile
    private var effectMixTarget: Float = 0f
    @Volatile
    private var effectMixStartMs: Long = 0L

    /** 当前「外来效果」是否应当显示（提醒或音乐律动）。 */
    fun effectActiveNow(c: RingConfig): Boolean =
        notifBlinkActive(c) || (c.musicPulseEnabled && musicPulsing)

    private fun effectMixNow(): Float {
        val start = effectMixStartMs
        if (start <= 0L) return effectMixTarget
        val t = ((SystemClock.uptimeMillis() - start).toFloat() / EFFECT_FADE_MS).coerceIn(0f, 1f)
        return effectMixFrom + (effectMixTarget - effectMixFrom) * t
    }

    /**
     * 取当前的淡入淡出进度（0–1），并在目标变化时从当前值重新起一段过渡。
     * 只在绘制线程调用（每次 [RingRenderer.draw] 调一次）。
     */
    fun effectMix(c: RingConfig): Float {
        val target = if (effectActiveNow(c)) 1f else 0f
        if (target != effectMixTarget) {
            effectMixFrom = effectMixNow()
            effectMixTarget = target
            effectMixStartMs = SystemClock.uptimeMillis()
        }
        return effectMixNow()
    }

    /** 包络是否还在过渡中（过渡期间必须持续重绘）。 */
    fun effectTransitioning(c: RingConfig): Boolean {
        val target = if (effectActiveNow(c)) 1f else 0f
        val now = effectMixNow()
        return if (target >= 1f) now < 0.999f else now > 0.001f
    }

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

    /** 上一帧的「下拉面板收起」开关值，用于检出热翻转（见 onShadeCollapseToggled） */
    @Volatile
    private var lastCollapseOnShade: Boolean = false

    private fun RingConfig.signature() =
        "$ringEnabled|$hideOnScreenshot|$strokeWidthDp|$gapDp|$offsetXDp|$offsetYDp|$scale|" +
            "$colorMode|$customColor|" +
            "$collapseOnImmersive|$collapseOnIsland|$collapseOnShade|" +
            "${stateColors.normal},${stateColors.low},${stateColors.powerSave}," +
            "${stateColors.performance},${stateColors.charging}|" +
            CustomColors.encodeRanges(levelRanges) +
            "|$breathingEnabled|$breathingOnIdle|$blinkOnNotification|$tapShowPercent|" +
            "$tapPercentDurationMs|" +
            "$blinkColorAlert|$blinkColorAlt|$burnInProtection|$maxBrightnessPercent|" +
            "$glowStrengthPercent|$trackOpacityPercent|" +
            "$chargingStyle|$chargingColor|$chargingSpeedPercent|$chargingAnimMode|" +
            "$chargingFullRingThreshold|$chargingStrengthPercent|" +
            "$breathingStyle|$breathingColor|$breathingSpeedPercent|" +
            "$blinkStyle|$blinkSpeedPercent|$blinkStrengthPercent|$blinkIntroEnabled|$blinkIntroSeconds|" +
            "$percentNodesEnabled|${PercentNodes.encode(percentNodes)}|" +
            "$musicPulseEnabled|$musicPulseStyle|$musicCycleSeconds|$musicColorCycleEnabled|" +
            "$musicColor|$musicPulseStrengthPercent|$musicFlashRangePercent|$musicBeatBpm|" +
            "$musicAudioReactive|$musicReactSource|$musicAudioSensitivityPercent|" +
            "$hideInShade|$hideInControlCenter|" +
            "$lowBatteryTigaEnabled|$lowBatteryTigaThreshold|$lowBatteryTigaSpeedPercent|" +
            "$blinkOnScreen|$blinkNotifMode|$blinkNotifDurationSeconds|" +
            "$hideRingOnLockScreen"

    // ---- 生命周期 ----

    fun attachContext(context: Context) {
        appContext = context.applicationContext
        HookPrefs.hostContext = appContext
        startCollapseWatchdog()
    }

    // ---- 收起状态看门狗 ----

    private val watchdogHandler = Handler(Looper.getMainLooper())

    /**
     * 看门狗周期（ms）：比探针的 600ms 轮询稍密一点，专门纠正「状态与画面不一致」。
     *
     * 它只管一件事：**动画既没在跑、画面进度又和目标对不上时，把进度直接对齐**。
     * 典型场景是收起动画被打断（解锁、窗口切换）后停在中间，或某个探针复位了状态
     * 却没触发到重绘。这一层与探针的「字段回读」互相独立：探针管「标志对不对」，
     * 看门狗管「画面跟没跟上」。
     */
    private const val WATCHDOG_MS = 400L

    @Volatile
    private var watchdogStarted = false

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            try {
                if (screenOn) {
                    // v1.3.3：顺带回读一次锁屏状态（约每 3 个 tick ≈ 1.2s 一次，
                    // 避免每秒多次 binder 调用），驱动「锁屏时隐藏圆环」
                    lockPollTick++
                    if (lockPollTick >= LOCK_POLL_TICKS) {
                        lockPollTick = 0
                        syncLockScreenState()
                    }
                    val anim = collapseAnimator
                    val animating = anim != null && anim.isRunning
                    val target = collapseTarget()
                    if (!animating && kotlin.math.abs(target - collapseProgress) > 0.01f) {
                        ModuleLog.i(
                            "收起看门狗纠正：progress=$collapseProgress → target=$target" +
                                "（沉浸=$statusBarCollapsed 岛=$islandShowing " +
                                "通知中心=$shadeExpanded 控制中心=$controlCenterShowing 锁屏=$lockScreenShowing）",
                        )
                        collapseProgress = target
                        invalidateAll()
                    }
                }
            } catch (t: Throwable) {
                ModuleLog.e("收起看门狗异常", t)
            }
            watchdogHandler.postDelayed(this, WATCHDOG_MS)
        }
    }

    /** 锁屏状态回读节流：每 [LOCK_POLL_TICKS] 个看门狗周期（≈1.2s）回读一次。 */
    private var lockPollTick = 0
    private const val LOCK_POLL_TICKS = 3

    private fun startCollapseWatchdog() {
        if (watchdogStarted) return
        watchdogStarted = true
        watchdogHandler.postDelayed(watchdogRunnable, WATCHDOG_MS)
        ModuleLog.i("收起状态看门狗已启动（${WATCHDOG_MS}ms）")
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
        val oldLevel = this.level
        val changed = oldLevel != level || this.charging != charging || this.powerSave != powerSave
        this.level = level.coerceIn(0, 100)
        this.charging = charging
        this.powerSave = powerSave
        if (changed) {
            ModuleLog.i("电池状态: level=${this.level} charging=$charging powerSave=$powerSave")
            maybeFlashPercentNode(oldLevel, this.level)
            invalidateAll()
        }
    }

    /**
     * 电量跨过用户设置的节点（如 20 / 80 / 100）时，短暂显示一次具体电量数字。
     *
     * 判据是「跨过」而不是「等于」：电量以 1% 步进上报，跨过瞬间新旧值通常
     * 都不等于节点本身；用区间比较（上行 old<n≤new / 下行 old>n≥new）才能
     * 稳定命中，也不会因上报抖动重复触发。
     */
    private fun maybeFlashPercentNode(oldLevel: Int, newLevel: Int) {
        if (oldLevel == newLevel) return
        val c = config
        if (!c.percentNodesEnabled || c.percentNodes.isEmpty()) return
        val crossed = c.percentNodes.firstOrNull { n ->
            (oldLevel < n && newLevel >= n) || (oldLevel > n && newLevel <= n)
        } ?: return
        ModuleLog.i("电量节点命中: ${oldLevel}% -> ${newLevel}% 节点=${crossed}%")
        flashPercent()
    }

    fun setScreenOn(on: Boolean) {
        if (screenOn != on) {
            screenOn = on
            ModuleLog.i("屏幕状态: screenOn=$on")
            invalidateAll()
        }
    }

    /** 音乐播放状态变化（BatteryObserver 轮询驱动，驱动「音乐律动」） */
    fun setMusicActive(active: Boolean) {
        if (musicActive == active) return
        musicActive = active
        ModuleLog.i("音乐播放状态: active=$active")
        invalidateAll()
    }

    /** 当前是否按音乐律动渲染（真实播放 或 设置页正在预览） */
    val musicPulsing: Boolean
        get() = musicActive || SystemClock.uptimeMillis() < musicPreviewUntil

    /**
     * 麦克风实时音量（0–1，v1.3.0「音频驱动律动」用）。
     *
     * 由模块应用侧的 AudioReactiveService 采集后广播过来；未开音频驱动时恒为 0。
     * 只影响律动的亮度/幅度，不参与其它任何判据。
     */
    @Volatile
    var audioLevel: Float = 0f
        private set

    /**
     * 音频内容类型（v1.3.0）：0 = 未知/静音，1 = 音乐，2 = 人声。
     * 供「人声/音乐」律动形式与「反应源」筛选使用。
     */
    @Volatile
    var audioKind: Int = 0
        private set

    /** 音频内容类型常量（与 AudioReactiveService 的 KIND_* 对齐）。 */
    const val AUDIO_KIND_UNKNOWN = 0
    const val AUDIO_KIND_MUSIC = 1
    const val AUDIO_KIND_VOICE = 2

    /** 音频驱动数据到达（应用侧采集 → 广播 → BatteryObserver 调用）。 */
    fun setAudioLevel(level: Float, kind: Int) {
        val l = level.coerceIn(0f, 1f)
        if (l == audioLevel && kind == audioKind) return
        audioLevel = l
        audioKind = kind
        // 只有开了音频驱动才值得为它产帧，避免无谓唤醒
        if (config.musicAudioReactive) invalidateAll()
    }

    /** 设置页「预览音乐律动」：临时按律动渲染一小段时间。 */
    fun previewMusic(durationMs: Long = 6000L) {
        musicPreviewUntil = SystemClock.uptimeMillis() + durationMs
        ModuleLog.i("预览音乐律动: ${durationMs}ms")
        invalidateAll()
    }

    /** 通知栏可清除通知存在性变化（由 NotificationBlinkHook 调用，兜底通道） */
    fun setNotificationsActive(active: Boolean) = setNotificationsActiveFromHook(active)

    /**
     * 应用侧「通知使用权」通道推来的提醒状态（主通道）。
     *
     * [authoritative] = true 表示推送方确实持有通知使用权、数据来自系统真实回调。
     * 此时把反射兜底通道的历史状态一并清掉——否则它漏掉的 removed 会让
     * [notificationsActive] 永久为真（症状：通知清了环还在呼吸）。
     */
    fun setNotificationsActiveFromApp(
        active: Boolean,
        sinceMs: Long = 0L,
        authoritative: Boolean = false,
    ) {
        val authChanged = notifByAppAuthoritative != authoritative
        notifByAppAuthoritative = authoritative
        noteNotifTime(active, sinceMs)
        if (authChanged) {
            if (authoritative && notifActiveByHook) {
                notifActiveByHook = false
                ModuleLog.i("通知提醒：应用侧已授权，丢弃反射兜底通道的残留状态")
            }
            ModuleLog.i("通知提醒通道切换：应用侧权威=$authoritative")
            invalidateAll()
        }
        if (notifActiveByApp == active) return
        notifActiveByApp = active
        ModuleLog.i("通知提醒状态(app): active=$active 权威=$authoritative 合计=$notificationsActive")
        invalidateAll()
    }

    /** SystemUI 侧反射 Hook 推来的提醒状态（兜底通道） */
    fun setNotificationsActiveFromHook(active: Boolean) {
        noteNotifTime(active, 0L)
        if (notifActiveByHook == active) return
        notifActiveByHook = active
        ModuleLog.i("通知提醒状态(hook): active=$active 合计=$notificationsActive")
        invalidateAll()
    }

    /**
     * 维护「提醒起始时刻」：用于随时间衰减。
     *
     * - 应用侧能提供真实的「最新一条通知到达时间」时用它（更准，第二条通知到达
     *   会把计时重置回新鲜期）；
     * - 拿不到时（Hook 兜底通道）在**状态由无到有**的那一刻记本地时间；
     * - 只允许时间往前推，避免两条通道交替上报把计时来回拨动。
     */
    private fun noteNotifTime(active: Boolean, sinceMs: Long) {
        if (!active) return
        val stamp = if (sinceMs > 0L) sinceMs else {
            if (notificationsActive) notifSinceMs else SystemClock.uptimeMillis()
        }
        if (stamp > notifSinceMs) notifSinceMs = stamp
    }

    /**
     * 设置页「预览提醒效果」：临时把提醒状态置真一小段时间。
     *
     * 单独用一条预览通道而不是直接改真实标志位，预览结束不会把真实提醒误清。
     * 连续动画调度由 [needsContinuousAnimation] 负责，预览到点后自然停在正常画面。
     */
    fun previewNotifications(durationMs: Long = 4200L) {
        val now = SystemClock.uptimeMillis()
        notifPreviewUntil = now + durationMs
        // 预览也重置「到达时刻」，这样进场快闪 + 慢呼吸两段都能看到
        if (notifSinceMs <= 0L) notifSinceMs = now
        ModuleLog.i("预览提醒效果: ${durationMs}ms")
        invalidateAll()
    }

    /**
     * 点击挖孔（或电量跨过节点）后短暂显示具体电量数字。
     *
     * 时长默认取配置项 [RingConfig.tapPercentDurationMs]，并夹到合法范围，
     * 配置写入异常值时也不会出现「一闪而过」或「一直不消失」。
     */
    fun flashPercent(durationMs: Long = config.tapPercentDurationMs.toLong()) {
        val d = durationMs.coerceIn(
            RingConfig.TAP_DURATION_MIN_MS.toLong(),
            RingConfig.TAP_DURATION_MAX_MS.toLong(),
        )
        percentFlashUntil = SystemClock.uptimeMillis() + d
        invalidateAll()
    }

    /**
     * 是否需要逐帧连续重绘：呼吸光晕、提醒闪烁、百分比淡出都是时间驱动的
     * 动画，静止画面不会自己重绘，必须由绘制入口自续帧。全部关闭或不在
     * 生效状态时返回 false，避免常态 60fps 空转。
     */
    fun needsContinuousAnimation(c: RingConfig): Boolean {
        if (!c.ringEnabled) return false
        // 息屏时环整体不绘制，无需任何帧
        if (!screenOn) return false
        if (SystemClock.uptimeMillis() < percentFlashUntil) return true
        if (notifBlinkActive(c)) return true
        if (c.musicPulseEnabled && musicPulsing) return true
        // 音频驱动：实时音量/类型在变，需要持续产帧（哪怕系统侧检测到「没在放音乐」）
        if (c.musicPulseEnabled && c.musicAudioReactive &&
            (audioLevel > 0.01f || audioKind != 0)
        ) {
            return true
        }
        // 提醒 / 音乐律动淡入淡出还在过渡中：必须继续产帧
        if (effectTransitioning(c)) return true
        val chargingNow = batteryPalette.chargingNow || charging
        // 低电量迪迦计时器：脉冲是时间驱动的，必须逐帧重绘
        if (tigaActive(c, chargingNow)) return true
        // 充电动画（非「进度弧」）是持续环绕的，必须逐帧重绘
        if (chargingNow && c.chargingStyle != RingConfig.CHARGING_STYLE_PROGRESS) return true
        if (c.breathingEnabled && (chargingNow || c.breathingOnIdle)) return true
        return false
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
     * 控制中心 / 通知面板展开状态（由 ShadeProbeHook 驱动）。
     *
     * 展开时：环收起（它层带比面板高，不收起来会压在控制中心上），同时把状态栏
     * 原生电池图标交还系统，让控制中心里显示正常的电量效果；面板收起后自动恢复。
     * 是否生效由 [RingConfig.hideInShade] 控制，关闭时本状态不产生任何影响。
     */
    fun setShadeExpanded(expanded: Boolean) {
        if (shadeExpanded == expanded) return
        shadeExpanded = expanded
        ModuleLog.i("通知中心展开状态: expanded=$expanded 收起目标=${collapseTarget()}")
        animateCollapseTo(collapseTarget())
        invalidateAll()
        try {
            onShadeExpandedChanged?.invoke()
        } catch (t: Throwable) {
            ModuleLog.e("通知中心展开回调异常", t)
        }
    }

    /**
     * 控制中心（右侧 QS 面板）显示状态（由 ControlCenterProbeHook 驱动）。
     *
     * 与 [setShadeExpanded] 完全同构：面板展开时环收起并把状态栏原生电池图标
     * 交还系统，面板收起后自动恢复；是否生效由 [RingConfig.hideInControlCenter] 控制。
     */
    fun setControlCenterShowing(showing: Boolean) {
        if (controlCenterShowing == showing) return
        controlCenterShowing = showing
        ModuleLog.i("控制中心显示状态: showing=$showing 收起目标=${collapseTarget()}")
        animateCollapseTo(collapseTarget())
        invalidateAll()
        try {
            onShadeExpandedChanged?.invoke()
        } catch (t: Throwable) {
            ModuleLog.e("控制中心显示回调异常", t)
        }
    }

    /**
     * 低电量「迪迦计时器」是否生效：开关开、未充电、电量不高于阈值。
     * 渲染与帧调度共用同一判据，避免两处口径漂移。
     */
    fun tigaActive(
        c: RingConfig = config,
        chargingNow: Boolean = batteryPalette.chargingNow || charging,
    ): Boolean {
        if (!c.lowBatteryTigaEnabled || chargingNow) return false
        val thr = c.lowBatteryTigaThreshold.coerceIn(
            RingConfig.TIGA_THRESHOLD_MIN, RingConfig.TIGA_THRESHOLD_MAX,
        )
        return level <= thr
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

    /**
     * 三条收起输入的统一自愈复位（2026-10-03 事故根治手段之一）。
     *
     * 三个探针都是「事件驱动」的：一旦某条通道给出一次假信号、而对应的
     * 「还原」事件没有到达（锁屏 / 开机 / 过渡态最容易发生），环就会被永久
     * 收缩成不可见。这里在「屏幕点亮」与「解锁完成」两个稳定的
     * 「此刻不可能有面板展开 / 沉浸收起」的时刻，把所有输入强制拉回未收起，
     * 让画面必定恢复；随后各探针的下一次真实事件会重新对齐。
     *
     * 与各探针的配合：探针改为读 [statusBarCollapsedNow] / [shadeExpandedNow] /
     * [islandShowing] 做去重，因此本方法复位后探针仍能再次驱动同一取值。
     */
    fun resetCollapseInputs(reason: String) {
        // 三条输入都为假、且画面本来就没有收缩时才无事可做；
        // 收缩进度残留（动画被打断 / 假值已清但画面没跟上）也必须纠正。
        if (!statusBarCollapsed && !islandShowing && !shadeExpanded && !controlCenterShowing &&
            kotlin.math.abs(collapseProgress) < 0.01f
        ) {
            return
        }
        ModuleLog.i(
            "收起状态自愈复位（$reason）: 沉浸=$statusBarCollapsed 岛=$islandShowing " +
                "通知中心=$shadeExpanded 控制中心=$controlCenterShowing",
        )
        statusBarCollapsed = false
        islandShowing = false
        shadeExpanded = false
        controlCenterShowing = false
        animateCollapseTo(collapseTarget())
        applyIslandColorFreeze()
        invalidateAll()
        try {
            onShadeExpandedChanged?.invoke()
        } catch (t: Throwable) {
            ModuleLog.e("收起自愈回调异常", t)
        }
    }

    /**
     * 「收起沉降窗口」截止时刻（uptimeMillis）。
     *
     * 为什么需要它（2026-10-03 用户反馈「解锁进桌面后要过一会儿才有环」）：
     * 解锁动画本身会让系统连着发好几个窗口/面板状态回调，其中夹杂**过渡态**的
     * 假 true（面板瞬间被判定展开、状态栏窗口瞬间 HIDDEN）。探针即使有 1s 轮询，
     * 也要等 1–3 秒才把它纠回来 —— 用户看到的就是「进桌面半天没环，过一会儿才冒出来」。
     *
     * 解锁/亮屏后的极短时间里，用户**不可能**真的在拉控制中心或看全屏视频，
     * 因此窗口内直接判定「没有任何收起」：环立刻回到完整显示，探针随后的真实
     * 事件仍能正常驱动（窗口只持续几百毫秒到 1.6 秒）。
     */
    @Volatile
    private var settleUntilMs: Long = 0L

    /**
     * 进入收起沉降窗口：窗口内 [collapseTarget] 一律返回 0（不收起），
     * 并立刻把画面拉回完整显示，保证「解锁完成 → 环马上就在」。
     */
    fun beginSettleWindow(durationMs: Long) {
        val now = SystemClock.uptimeMillis()
        val until = now + durationMs
        if (until > settleUntilMs) settleUntilMs = until
        // v1.3.3：解锁完成时同步一次锁屏状态（此刻锁屏已离开，立即让环回来）。
        // 放在复位之前，避免「锁屏隐藏」残留 true 把环多压一会儿。
        syncLockScreenState()
        statusBarCollapsed = false
        islandShowing = false
        shadeExpanded = false
        controlCenterShowing = false
        collapseAnimator?.let { if (it.isRunning) it.cancel() }
        collapseAnimator = null
        // 用目标值定进度：常规解锁 → 0（环立刻回来）；万一仍处锁屏隐藏 → 1（保持收起）
        collapseProgress = collapseTarget()
        ModuleLog.i("收起沉降窗口开始: ${durationMs}ms（其间忽略全部收起信号，环保持完整显示）")
        invalidateAll()
        applyIslandColorFreeze()
        try {
            onShadeExpandedChanged?.invoke()
        } catch (t: Throwable) {
            ModuleLog.e("收起沉降窗口回调异常", t)
        }
    }

    /** 当前是否处于收起沉降窗口内。 */
    private fun inSettleWindow(): Boolean = SystemClock.uptimeMillis() < settleUntilMs

    /**
     * 三条收起通路合并成一个目标值：沉浸收起、灵动岛显示、控制中心展开，
     * 任一命中即收缩。三个开关互不影响，各自控制自己的场景。
     */
    private fun collapseTarget(): Float {
        val c = config
        // v1.3.3：锁屏隐藏圆环不受沉降窗口影响——锁屏上就是要立刻收起，
        // 否则「亮屏 → 1.6s 沉降窗口」会让环在锁屏上闪一下再消失。
        val byLockScreen = lockScreenShowing && c.hideRingOnLockScreen
        // 解锁/亮屏后的沉降窗口内一律不收起：治「进桌面后要等一会儿才有环」
        if (inSettleWindow() && !byLockScreen) return 0f
        val byImmersive = statusBarCollapsed && c.collapseOnImmersive
        val byIsland = islandShowing && c.collapseOnIsland
        val byShade = shadeExpanded && c.hideInShade
        val byControlCenter = controlCenterShowing && c.hideInControlCenter
        val byShadeCollapse = shadeCollapseFraction > 0f && c.collapseOnShade
        return if (byImmersive || byIsland || byShade || byControlCenter || byLockScreen || byShadeCollapse) 1f else 0f
    }

    /**
     * 面板（通知栏/控制中心）展开状态，0f 收起 / 1f 展开。
     * 由 ShadeCollapseHook 在 SystemUI 主线程调用。
     */
    fun setShadeCollapse(fraction: Float) {
        val clamped = fraction.coerceIn(0f, 1f)
        if (!RingCollapseLogic.shouldEmit(shadeCollapseFraction, clamped)) return
        shadeCollapseFraction = clamped
        val target = collapseTarget()
        ModuleLog.i("面板收起状态: fraction=$clamped 收起目标=$target")
        animateCollapseTo(target)
    }

    /**
     * 「下拉面板收起」开关热翻转后的清理（见 onShadeCollapseToggled）。
     * 关闭方向：丢弃开关关闭期间无门控保护留下的残留 fraction；
     * 开启方向：让驱动侧清掉同值去重缓存并按系统当前展开态补一次真实同步。
     */
    private fun resetShadeCollapseForToggle() {
        shadeCollapseFraction = 0f
        if (config.collapseOnShade) {
            try {
                onShadeCollapseToggled?.invoke()
            } catch (t: Throwable) {
                ModuleLog.e("下拉收起开关回调异常", t)
            }
        }
    }

    /** 「取消旧动画→立即到位或平滑过渡」。 */
    private fun animateCollapseTo(target: Float) {
        val start = collapseProgress
        collapseAnimator?.let { if (it.isRunning) it.cancel() }
        // 全部收起开关都关掉时压根不需要播动画，直接到位
        // （v1.2.0 修正：原先漏了 hideInControlCenter，只开「控制中心里隐藏」时
        //  会跳过动画、硬切上/下场，就是用户说的「控制中心太突兀」）
        val noCollapseFeature = !config.collapseOnImmersive && !config.collapseOnIsland &&
            !config.hideInShade && !config.hideInControlCenter && !config.hideRingOnLockScreen &&
            !config.collapseOnShade
        if (noCollapseFeature || kotlin.math.abs(target - start) < 0.01f) {
            val changed = kotlin.math.abs(target - start) >= 0.01f
            collapseProgress = target
            invalidateAll()
            // 只在实际到位时通知一次，避免配置刷新等无变化路径反复踢电池侧
            if (changed) notifyCollapseSettled()
            return
        }
        // v1.3.2：环「回场」（收起进度从大变小）时先等一会儿，让原生电池图标
        // 淡出跑完，两者错开就不会在屏幕上同框。
        val returning = target < start - 0.01f
        collapseAnimator = ValueAnimator.ofFloat(start, target).apply {
            duration = COLLAPSE_DURATION_MS
            interpolator = collapseInterpolator
            startDelay = if (returning && start > 0.5f) COLLAPSE_RETURN_DELAY_MS else 0L
            addUpdateListener {
                collapseProgress = it.animatedValue as Float
                invalidateAll()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (collapseProgress >= 0.985f || collapseProgress <= 0.015f) {
                        notifyCollapseSettled()
                    }
                }
            })
        }.also { it.start() }
    }

    /** 挖孔几何首次解析成功的回调（由隐藏 Hook 注册，用于时序补偿）。 */
    @Volatile
    var onCutoutResolved: (() -> Unit)? = null

    /**
     * 每次挖孔绘制的回调（主线程）。供轻量的自愈式同步用：环窗口的
     * SurfaceControl 会在灭屏/旋转时被系统重建，SF 层的采集排除标志
     * 需要跟着新身份重打。实现方必须自己做去重，绝不能在这里做重活。
     */
    @Volatile
    var onCutoutFrame: (() -> Unit)? = null

    /**
     * 「下拉面板收起」开关翻转的回调（主线程，配置签名分支内触发）。
     */
    @Volatile
    var onShadeCollapseToggled: (() -> Unit)? = null

    /**
     * 屏幕方向变化回调。与 [onCutoutResolved] 同一模式：由 SystemUiHooks 接到
     * `BatteryHideHook.refreshAll()`，data 层不直接依赖 hook 层。
     */
    @Volatile
    var onOrientationChanged: (() -> Unit)? = null

    /**
     * 「让各收起探针立刻回读一次自己的实时信号」的请求。
     *
     * 由 SystemUiHooks 接到三个探针的 `reconcile()`。用在「屏幕点亮 / 解锁完成」
     * 这类稳定时刻：此刻不可能真有面板展开、也不可能真在沉浸模式里，让探针把
     * 自己记录的值与系统实时值重新对齐一次，就能把锁屏 / 解锁过渡期间残留的
     * 假值放掉（[resetCollapseInputs] 是强制清零，这里是**按实时值对齐**，
     * 因此不会误伤「真的展开了」的场景）。
     */
    @Volatile
    var onReconcileRequested: (() -> Unit)? = null

    fun requestReconcile() {
        try {
            onReconcileRequested?.invoke()
        } catch (t: Throwable) {
            ModuleLog.e("收起状态复核回调异常", t)
        }
    }

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
     *
     * 控制中心 / 通知面板展开时同样交还图标：面板里要看得见正常电量，
     * 此时环也已经收起，不存在重复显示。
     */
    fun shouldForceHideBattery(view: View?): Boolean {
        val c = config
        val base = c.ringEnabled && c.hideBattery && cutoutEverResolved && !isLandscape(view)
        if (!base) return false
        // v1.3.2：只要环还没**完全收干净**，就一律继续藏着原生图标。
        // 用户反馈「上划很慢（尤其最后一段）时环和电池同框」——根因是面板刚判定
        // 展开、环还在退场（300ms）时，电池就已经淡入（260ms）。现在电池要等环
        // 彻底不见才允许回来，退场这段时间它就是不给显。
        if (collapseProgress < 0.985f) return true
        // v1.3.3：锁屏隐藏圆环期间，环不可见 → 把原生电池图标交还系统，
        // 否则锁屏上既没有环、又看不到电量（「保留原锁屏」的一部分）
        if (lockScreenShowing && c.hideRingOnLockScreen) return false
        // 环已收干净：面板/控制中心展开时把图标交还系统（面板里要看得见正常电量）
        if (shadeExpanded && c.hideInShade) return false
        if (controlCenterShowing && c.hideInControlCenter) return false
        return true
    }

    // ---- 绘制入口（由 Hook 在 DisplayCutoutBaseView.onDraw 后调用） ----

    /**
     * 绘制入口（环窗口的唯一渲染通路；v1.2.0 起息屏注入视图已移除）。
     */
    fun onCutoutDraw(view: View, canvas: Canvas) {
        val c = config
        attachCutoutView(view)
        // 配置发生变化时补发一帧，使滑杆/颜色调节在静止状态下也即时可见
        val sig = c.signature()
        if (sig != lastConfigSig) {
            lastConfigSig = sig
            if (c.collapseOnShade != lastCollapseOnShade) {
                lastCollapseOnShade = c.collapseOnShade
                resetShadeCollapseForToggle()
            }
            animateCollapseTo(collapseTarget())
            applyIslandColorFreeze()
            invalidateAll()
        }
        try {
            onCutoutFrame?.invoke()
        } catch (t: Throwable) {
            ModuleLog.e("环帧回调异常", t)
        }
        if (!c.ringEnabled) return
        if (!screenOn) return
        try {
            RingRenderer.draw(view, canvas, c, this)
        } catch (t: Throwable) {
            // 绘制异常绝不能影响系统挖孔本身的渲染
            ModuleLog.e("环形电量绘制异常", t)
        }
        // 呼吸/闪烁/百分比淡出期间自续帧；全部静止时不发，避免常态空转
        if (needsContinuousAnimation(c)) {
            view.postInvalidateOnAnimation()
        } else if (c.burnInProtection) {
            // 静止画面下唯一需要唤醒的场景：防烧屏位移到下一格时重绘一次
            scheduleBurnInShift(view)
        }
    }

    /** 防烧屏位移调度：只在下一格边界唤醒一帧，不常驻帧循环。 */
    @Volatile
    private var burnInShiftScheduled = false

    private fun scheduleBurnInShift(view: View) {
        if (burnInShiftScheduled) return
        burnInShiftScheduled = true
        val period = RingRenderer.BURN_IN_SHIFT_PERIOD_MS
        val delay = period - (SystemClock.uptimeMillis() % period) + 80L
        view.postDelayed({
            burnInShiftScheduled = false
            invalidateAll()
        }, delay)
    }

    /** 取一个已附加的环 View（点击命中判定用）；无则 null。 */
    fun firstAttachedRingView(): View? {
        val snapshot = synchronized(cutoutViews) { cutoutViews.toList() }
        return snapshot.firstOrNull { it.isAttachedToWindow }
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
