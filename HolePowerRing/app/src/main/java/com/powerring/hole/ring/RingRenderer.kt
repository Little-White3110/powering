package com.powerring.hole.ring

import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import com.powerring.hole.core.ModuleLog

/**
 * 挖孔环形电量绘制器（方案 A：直接挂在系统挖孔装饰 View 的 onDraw 之后）。
 *
 * 几何规范对齐 miuix CircularProgressIndicator：
 * - 圆形进度，起点 -90°（12 点方向），圆角线帽（StrokeCap.ROUND）
 *
 * 配色策略（2026-10-02 改为四模式互斥，见 RingConfig.MODE_*）：
 * 1. MODE_FIXED_COLOR → 固定用 [RingConfig.customColor]
 * 2. MODE_BATTERY_STATE → 五路用户色（[RingConfig.stateColors]）；
 *    未设置（0）的那一路逐槽回退到系统电池图标色 / 内置语义色
 * 3. MODE_LEVEL_RANGE → 按电量区间表取第一条命中的色，未命中回退跟随系统
 * 4. MODE_FOLLOW_SYSTEM（默认）→ 五种状态全部跟随原生图标颜色
 *    （普通 / 低电 / 省电 / 性能 / 充电），底槽取进度色的低透明度版本
 * 5. 上述任一路径都取不到颜色（Hook 未装上 / 机型类名不同）→ 回退 miuix 语义色：
 *    底槽用 sliderBackground 令牌色、充电 primary 蓝、低电 error 红、
 *    省电琥珀与性能橙（应用自定义语义色）
 */
object RingRenderer {

    /** 低电量阈值（百分比） */
    private const val LOW_BATTERY_THRESHOLD = 15

    /** 迪迦计时器红：胸口彩色计时器告警时的红 */
    private const val TIGA_RED = 0xFFFF2A2A.toInt()

    /** 迪迦计时器脉冲谷底的透明度：暗下去才有「滴、滴」的节奏感 */
    private const val TIGA_DIM = 0.28f

    /** 迪迦计时器周期（ms）：刚跌破阈值时最慢、接近 0% 时最急 */
    private const val TIGA_PERIOD_SLOW_MS = 1350f
    private const val TIGA_PERIOD_FAST_MS = 420f

    /** 平时呼吸周期（ms）：慢呼吸，克制的幅度 */
    private const val BREATH_PERIOD_IDLE_MS = 3200L

    /** 充电呼吸周期（ms）：更明显、稍快 */
    private const val BREATH_PERIOD_CHARGING_MS = 1800L

    /** 进场提示脉冲周期（ms）：通知到达后的「闪一下」，内闪 1–2 次 */
    private const val BLINK_INTRO_PULSE_MS = 680L

    /** 常驻提醒呼吸基准周期（ms）：再按用户速度缩放，最慢可到 20 秒以上 */
    private const val BLINK_BREATH_BASE_MS = 4200f

    /** 常驻呼吸的亮度区间：最低 35%、最高 85%，刻意偏淡不抢视线 */
    private const val BLINK_BREATH_DIM = 0.35f
    private const val BLINK_BREATH_BRIGHT = 0.85f

    /** 平时呼吸周期（ms）：慢呼吸，克制的幅度 */
    private const val GAP_SWEEP_BASE_MS = 2600f

    /** 百分比数字淡出时长（ms） */
    private const val PERCENT_FADE_MS = 400L

    /**
     * 防烧屏漂移步长（ms）：每过这么久把环的亚像素相位推进一格。
     * 0.2px 级别的跳变经反锯齿摊开后肉眼不可见，但子像素覆盖持续变化，
     * 防残影效果不打折（苹果 AOD 同思路：微位移 + 低亮度，而不是大动作）。
     */
    const val BURN_IN_SHIFT_PERIOD_MS = 5_000L

    /** 漂移幅度（物理像素）：峰值不到 1px，永远看不出环歪了 */
    private const val DRIFT_AMP_PX = 0.75f

    /** 两个不相等的周期（步数），叠加出的李萨如轨迹长时间不重复 */
    private const val DRIFT_PERIOD_X = 97.0
    private const val DRIFT_PERIOD_Y = 149.0

    /** 当前时刻的防烧屏漂移（px，亚像素级）；供绘制与重绘调度共用。 */
    fun burnInOffset(nowMs: Long): Pair<Float, Float> {
        val t = (nowMs / BURN_IN_SHIFT_PERIOD_MS).toDouble()
        val x = DRIFT_AMP_PX * kotlin.math.sin(t * 2.0 * Math.PI / DRIFT_PERIOD_X)
        val y = DRIFT_AMP_PX * kotlin.math.sin(t * 2.0 * Math.PI / DRIFT_PERIOD_Y + 1.3)
        return x.toFloat() to y.toFloat()
    }

    /**
     * 单次脉冲包络：相位落在 [start, end] 区间内时按余弦抬起再落下，其余为 0。
     * 迪迦计时器的「双闪」就是两次这样的脉冲拼出来的。
     */
    private fun pulseAt(phase: Float, start: Float, end: Float): Float {
        if (phase < start || phase > end) return 0f
        val t = (phase - start) / (end - start)
        return 0.5f - 0.5f * kotlin.math.cos(2f * Math.PI.toFloat() * t)
    }

    /**
     * 跟随系统电池图标颜色时，底槽取进度色该比例的透明度。
     * 与自定义色分支的 0x26（约 15%）保持同一量级，视觉上与原生图标一致。
     */
    private const val TRACK_ALPHA = 0x33

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    /** 呼吸光晕：比环更宽的一圈柔光，画在底槽之下 */
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    /** 跑马/彗尾：细长拖尾用平头（BUTT）逐段叠加，避免圆头在接缝处鼓起 */
    private val runnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }

    /** 光点头部亮核：圆头，画在拖尾之上 */
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    /** 点击挖孔后显示的电量数字 */
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.LEFT
    }

    /** 充电「能量扩散」向外扩散的波纹 */
    private val echoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    /** 「人声/音乐」律动形式里，识别到人声时的暖色（v1.3.0）。 */
    private const val VOICE_WARM_COLOR = 0xFFFF8A5C.toInt()

    /** 充电「整环脉冲」/「呼吸光点」的基准周期（ms） */
    private const val CHARGING_PULSE_BASE_MS = 1_700f

    /**
     * 音频驱动律动的「呼吸变速增益」（v1.3.2 引入，v1.3.3 增强）。
     *
     * 实时音量拉到 1 时，呼吸周期最短缩到 1/(1+GAIN)：响的地方呼吸明显更快、
     * 静的地方慢下来，让「频率」真正跟着声音走（节拍感因此更明显）。
     * v1.3.3：3.0 → 4.5，用户反馈「节奏感不够强」，加大音量对速度的驱动幅度。
     */
    private const val AUDIO_BREATH_RATE_GAIN = 4.5f

    /**
     * 音频驱动的律动相位累加器（v1.3.2）。
     *
     * 为什么不用 `now % period`：周期随音量实时变化，取模会让相位在变速瞬间跳变
     * （看起来像「闪一下」）。累加式推进则始终连续，变速只改变推进的快慢。
     */
    private var musicAudioPhase = 0f
    private var musicAudioPhaseMs = 0L

    /**
     * 按实时音量推进一次律动相位，返回 0–1 的循环相位。
     *
     * @param nowMs 本帧时间
     * @param basePeriodMs 基准周期（安静时走完一整圈的时间）
     * @param level 0–1 的实时音量（已乘灵敏度）
     * @param rateGain 音量对速度的增益
     */
    private fun advanceAudioPhase(
        nowMs: Long,
        basePeriodMs: Float,
        level: Float,
        rateGain: Float,
    ): Float {
        val last = musicAudioPhaseMs
        // 首帧 / 长时间没画（帧循环停过）：给一个安全的小步长，避免相位瞬跳
        val dt = if (last <= 0L) 16f else (nowMs - last).coerceIn(0L, 120L).toFloat()
        musicAudioPhaseMs = nowMs
        val speed = (1f + rateGain * level.coerceIn(0f, 1f)).coerceAtLeast(0.25f)
        val effPeriod = (basePeriodMs / speed).coerceAtLeast(140f)
        musicAudioPhase += dt / effPeriod
        musicAudioPhase -= kotlin.math.floor(musicAudioPhase)
        return musicAudioPhase
    }

    /** 能量扩散（ECHO）波纹的最大放大倍数与同时存在的波纹数 */
    private const val ECHO_MAX_SCALE = 1.55f
    private const val ECHO_COUNT = 2

    @Volatile
    private var clipRiskLogged = false

    @Volatile
    private var diagLogged = false

    /** 配色变化日志：与 RingState 里的日志同样必须节流，跟随系统时每帧都在变。 */
    @Volatile
    private var lastColorLogMs = 0L

    @Volatile
    private var lastLoggedColor = 0

    /**
     * 当前正在淡出的是哪种效果（0=无，[EFFECT_BLINK]=提醒，[EFFECT_MUSIC]=音乐）。
     *
     * v1.2.0：效果（提醒/音乐律动）消失后，RingState.effectMix 会在 460ms 内从 1 降回 0。
     * 若此时立刻停画效果，就会「一下变回正常」——正是用户反馈的突兀点。这里记住
     * 上一个生效的效果，在淡出期间继续按它绘制、并按 effectMix 把颜色插值回正常色。
     */
    @Volatile
    private var lastEffectKind = 0

    private const val EFFECT_BLINK = 1
    private const val EFFECT_MUSIC = 2

    fun draw(view: View, canvas: Canvas, config: RingConfig, state: RingState) {
        val density = view.resources.displayMetrics.density
        val stroke = config.strokeWidthDp * density
        // 贴孔间隙：用户可调（含负值），系统安全区普遍比物理孔大一圈，
        // 缝隙感明显时往负调可以让环直接压到物理孔边缘
        val gap = config.gapDp.coerceIn(RingConfig.GAP_MIN, RingConfig.GAP_MAX) * density

        val hole = CutoutGeometry.resolve(view, gap + stroke / 2f) ?: return
        state.markCutoutResolved()

        if (hole.clipRisk && !clipRiskLogged) {
            clipRiskLogged = true
            ModuleLog.i("环有越出窗口边界的部分（用户可通过缩放/偏移修正）")
        }

        val darkIcons = MiuixPalette.isDarkIconMode(state.tintColor)

        // 沉浸收缩：expand=1 完整显示，expand→0 半径向内收敛、透明度同步淡出
        var expand = 1f - state.collapseProgress.coerceIn(0f, 1f)

        if (expand <= 0.01f) return

        // 四种模式互斥；分支只决定「进度色从哪来」，几何与动画一律不受影响。
        val palette = state.batteryPalette
        val fromUserOrSystem: Boolean
        val modeColor: Int = when (config.colorMode) {
            RingConfig.MODE_FIXED_COLOR -> {
                fromUserOrSystem = true
                config.customColor
            }

            RingConfig.MODE_BATTERY_STATE -> {
                fromUserOrSystem = true
                batteryStateColor(config, palette, state, darkIcons)
            }

            RingConfig.MODE_LEVEL_RANGE -> {
                fromUserOrSystem = true
                levelRangeColor(config, palette, state, darkIcons)
            }

            else -> {
                val followSystem = palette.ready
                fromUserOrSystem = followSystem
                // 顺序与系统 MiuiBatteryMeterIconView.getProgressStatus() 一致：
                // 充电（含快充）> 性能模式 > 省电模式 > 低电量 > 普通
                when {
                    followSystem && palette.chargingNow -> palette.charging
                    followSystem && palette.performanceNow -> palette.performance
                    followSystem && palette.powerSaveNow -> palette.powerSave
                    followSystem && palette.lowNow -> palette.low
                    followSystem -> state.normalColor
                    state.level <= LOW_BATTERY_THRESHOLD -> MiuixPalette.errorColor(darkIcons)
                    state.charging -> MiuixPalette.primaryColor(darkIcons)
                    state.powerSave -> MiuixPalette.POWER_SAVE_AMBER
                    else -> MiuixPalette.foregroundColor(darkIcons)
                }
            }
        }
        // 灵动岛保护：岛是黑色药丸，环色跟到近黑就不可见，改白（保留 alpha）。
        // 与 RingState 的岛色冻结是两层、各管各的：跟随系统的普通态由冻结机制
        // 定格成白后流到这里已是白色、守卫 no-op；三种自定义配色模式与取色回退
        // 链不经过 normalColor，只靠这里的守卫兜底。别删任何一层。
        val rawProgress = IslandColorGuard.ensureVisible(
            modeColor,
            islandShowing = state.islandShowing,
            collapseOnIsland = config.collapseOnIsland,
        )
        // 来自用户配置或系统调色板时，底槽取进度色的低透明度版本，视觉更统一；
        // 只有退回内置令牌时才用 sliderBackground 槽色。
        val rawTrack = if (fromUserOrSystem) {
            (TRACK_ALPHA shl 24) or (rawProgress and 0x00FFFFFF)
        } else {
            MiuixPalette.trackColor(darkIcons)
        }
        val progressColor = scaleAlpha(rawProgress, expand)
        // 底槽浓度：白底上浅灰轨道圆像「印记」，调到 0 可完全隐藏
        val trackK = config.trackOpacityPercent.coerceIn(
            RingConfig.TRACK_OPACITY_MIN, RingConfig.TRACK_OPACITY_MAX,
        ) / 100f
        val trackColor = scaleAlpha(scaleAlpha(rawTrack, trackK), expand)

        // ---- 动画效果（v0.3：充电动画 / 呼吸灯 / 消息提醒，形式、颜色、速度均可配置） ----
        // 优先级：消息提醒 > 充电动画 > 呼吸灯。三者互斥地决定环的呈现方式，
        // 底层几何（半径、进度、防烧屏位移）不受影响。
        val nowMs = android.os.SystemClock.uptimeMillis()
        val chargingNow = state.batteryPalette.chargingNow || state.charging
        // 电量比例（0–1）：动画与几何共用，故在此处先算好
        val fraction = (state.level / 100f).coerceIn(0f, 1f)

        // 用户自定义动画色：0 或全透明表示「跟随环色」
        fun pickAnimColor(custom: Int, fallback: Int): Int =
            if (custom == 0 || custom ushr 24 == 0) fallback else custom

        var ringColor = progressColor
        var ringTrack = trackColor
        var glowScale = 0f
        // 跑马/彗尾光点（可为 0/1/2 个）；空表示当前不画
        var runners: List<Runner> = emptyList()
        // 充电动画「能量扩散」向外扩散的波纹；空表示当前不画
        var echoes: List<Echo> = emptyList()

        // 消息提醒 / 音乐律动「淡入淡出」的基准色（v1.2.0）：
        // 效果出现/消失时不再硬切，而是按 RingState.effectMix 在「正常环色」与
        // 「效果色」之间插值。基准 = 没有任何动效时的进度色 / 底槽色。
        val baseRingForEffect = progressColor
        val baseTrackForEffect = trackColor
        // 效果淡入淡出包络（v1.2.0）：每次绘制推进一次，值域 0–1，表示「效果色」
        // 相对「正常环色」的权重。效果出现→1、消失→0，两端都平滑，不再硬切。
        val effectMix = state.effectMix(config)

        val blinkActive = state.notifBlinkActive(config)
        // 低电量迪迦计时器（未充电 + 电量不高于阈值）；判据与帧调度共用 RingState.tigaActive
        val tigaActive = state.tigaActive(config, chargingNow)
        when {
            // 1) 消息提醒：优先于一切。两段式：到达瞬间快闪 1–2 下，
            //    之后转入很慢、很淡的常驻呼吸（速度滑杆控制的就是这段呼吸）。
            //    v1.2.0：提醒消失后仍按 effectMix 续绘约 460ms 淡出，不再硬切回正常态。
            blinkActive || (effectMix > 0.001f && lastEffectKind == EFFECT_BLINK) -> {
                val alert = pickAnimColor(config.blinkColorAlert, MiuixPalette.errorColor(darkIcons))
                val alt = pickAnimColor(config.blinkColorAlt, alert)
                ringTrack = scaleAlpha(trackColor, 0.45f)
                // v1.3.3「幅度」：整体缩放提醒摆动的深浅与光晕（0 = 几乎不摆，200 = 最强）
                val bAmp = config.blinkStrengthPercent.coerceIn(
                    RingConfig.STRENGTH_MIN, RingConfig.STRENGTH_MAX,
                ) / 100f
                val ageMs = state.notifAgeMs()
                val introMs = if (config.blinkIntroEnabled) {
                    config.blinkIntroSeconds.coerceIn(
                        RingConfig.INTRO_SECONDS_MIN, RingConfig.INTRO_SECONDS_MAX,
                    ) * 1000L
                } else 0L
                // 拿不到通知到达时刻（Hook 兑底通道）时直接进慢呼吸
                val inIntro = introMs > 0L && state.hasNotifAge() && ageMs < introMs

                if (inIntro) {
                    // ---- 进场：快闪 1–2 下，又快又鲜艳 ----
                    val period = BLINK_INTRO_PULSE_MS
                    val step = ((ageMs / period) % 2).toInt()
                    val visible = IslandColorGuard.ensureVisible(
                        if (step == 0) alert else alt,
                        islandShowing = state.islandShowing,
                        collapseOnIsland = config.collapseOnIsland,
                    )
                    val phase = (ageMs % period).toFloat() / period
                    val pulse = 0.5f - 0.5f * kotlin.math.cos(2f * Math.PI.toFloat() * phase)
                    when (config.blinkStyle) {
                        RingConfig.BLINK_STYLE_RUNNER -> {
                            ringColor = scaleAlpha(progressColor, 0.35f * expand)
                            runners = listOf(
                                Runner(
                                    color = visible,
                                    headDeg = (ageMs / 1000f * 260f) % 360f,
                                    tailDeg = 90f,
                                    glow = (0.5f + (pulse - 0.5f) * bAmp) * expand,
                                ),
                            )
                        }

                        else -> {
                            ringColor = scaleAlpha(
                                visible,
                                (0.65f + (pulse - 0.5f) * 0.70f * bAmp)
                                    .coerceIn(0f, 1f) * expand,
                            )
                            glowScale = (0.475f + (pulse - 0.5f) * 0.95f * bAmp)
                                .coerceAtLeast(0f)
                        }
                    }
                } else {
                    // ---- 常驻：很慢、很淡的呼吸 ----
                    val period = BLINK_BREATH_BASE_MS / speedFactor(config.blinkSpeedPercent)
                    val phase = (nowMs % period.toLong()) / period
                    val breath = 0.5f - 0.5f * kotlin.math.cos(2f * Math.PI.toFloat() * phase)
                    // 交替式：每半个周期在主/副色之间缓慢切换，其余形式固定主色
                    val swapped = (nowMs / (period / 2).toLong()).toInt() % 2 == 1
                    val base =
                        if (config.blinkStyle == RingConfig.BLINK_STYLE_ALTERNATE && swapped) alt else alert
                    val visible = IslandColorGuard.ensureVisible(
                        base,
                        islandShowing = state.islandShowing,
                        collapseOnIsland = config.collapseOnIsland,
                    )
                    // v1.3.3「幅度」：以呼吸中点为轴，按 bAmp 缩放明暗摆幅。
                    // bAmp=1 → 与旧版一致（0.35–0.85），0 → 恒定中点不再摆，>1 → 更明显
                    val bMid = (BLINK_BREATH_DIM + BLINK_BREATH_BRIGHT) / 2f
                    val bHalf = (BLINK_BREATH_BRIGHT - BLINK_BREATH_DIM) / 2f
                    val alpha = (bMid + (breath - 0.5f) * 2f * bHalf * bAmp)
                        .coerceIn(0f, 1f) * expand
                    when (config.blinkStyle) {
                        RingConfig.BLINK_STYLE_RUNNER -> {
                            ringColor = scaleAlpha(progressColor, 0.80f * expand)
                            runners = listOf(
                                Runner(
                                    color = visible,
                                    headDeg = (nowMs / 1000f * 30f *
                                        speedFactor(config.blinkSpeedPercent)) % 360f,
                                    tailDeg = 120f,
                                    glow = (0.32f + 0.33f * breath) * bAmp * expand,
                                ),
                            )
                        }

                        RingConfig.BLINK_STYLE_DOUBLE_FLASH -> {
                            // 双闪（v1.3.0 新增）：一周期快速闪两下（一强一弱），
                            // 像通知指示灯，静默间隙更长
                            val fl = maxOf(
                                pulseAt(phase, 0.02f, 0.16f),
                                pulseAt(phase, 0.22f, 0.36f) * 0.7f,
                            )
                            ringColor = scaleAlpha(
                                visible,
                                (bMid + (fl - 0.5f) * 2f * bHalf * bAmp)
                                    .coerceIn(0f, 1f) * expand,
                            )
                            glowScale = 0.32f * fl * bAmp
                        }

                        RingConfig.BLINK_STYLE_RIPPLE -> {
                            // 涟漪警示（v1.3.0 新增）：一圈警示光由内向外扩散淡出
                            ringColor = scaleAlpha(visible, 0.70f * expand)
                            val p1 = phase
                            val p2 = (phase + 0.5f) % 1f
                            echoes = listOf(
                                Echo(
                                    1f + 0.70f * p1,
                                    (1f - p1) * (1f - p1) * 0.85f * bAmp * expand,
                                ),
                                Echo(
                                    1f + 0.70f * p2,
                                    (1f - p2) * (1f - p2) * 0.85f * bAmp * expand,
                                ),
                            )
                            glowScale = 0.25f * breath * bAmp
                        }

                        else -> {
                            ringColor = scaleAlpha(visible, alpha)
                            glowScale = 0.30f * breath * bAmp
                        }
                    }
                }
                // 淡入淡出：提醒刚出现 / 刚消失时，在正常环色与提醒色之间平滑过渡
                if (effectMix < 0.999f) {
                    ringColor = lerpColor(baseRingForEffect, ringColor, effectMix)
                    ringTrack = lerpColor(baseTrackForEffect, ringTrack, effectMix)
                    glowScale *= effectMix
                    runners = runners.map { it.copy(glow = it.glow * effectMix) }
                }
                if (blinkActive) lastEffectKind = EFFECT_BLINK
            }

            // 2) 充电动画：非「进度弧」形式时用跑马光点驱动，进度弧保留（电量可读）。
            //    覆盖范围由 chargingAnimMode 决定：智能模式按电量阈值在「空白段」与
            //    「整圈」之间自动切换；也可强制整圈或强制空白段。
            chargingNow && config.chargingStyle != RingConfig.CHARGING_STYLE_PROGRESS -> {
                val visible = IslandColorGuard.ensureVisible(
                    pickAnimColor(config.chargingColor, progressColor),
                    islandShowing = state.islandShowing,
                    collapseOnIsland = config.collapseOnIsland,
                )
                // 已充部分保持全亮（这是最该被读到的信息），改为压暗**空槽**：
                // 流动光在暗背景上更突出，同时进度弧一眼可读，两者不再互相削弱。
                ringColor = scaleAlpha(progressColor, expand)
                ringTrack = scaleAlpha(trackColor, 0.45f)

                // 「整环脉冲 / 呼吸光点」共用的心跳相位：整圈一起明暗起伏
                val chargingPulse = when (config.chargingStyle) {
                    RingConfig.CHARGING_STYLE_PULSE, RingConfig.CHARGING_STYLE_BREATH_DOT -> {
                        val period = (CHARGING_PULSE_BASE_MS /
                            speedFactor(config.chargingSpeedPercent)).coerceAtLeast(320f)
                        val ph = (nowMs % period.toLong()).toFloat() / period
                        0.5f - 0.5f * kotlin.math.cos(2f * Math.PI.toFloat() * ph)
                    }

                    else -> 0f
                }

                // v1.3.3「幅度」：整体缩放充电动画的起伏与光晕强弱。
                // 0 = 只剩平静的进度弧（无脉动 / 无光点光晕），100 = 基准，200 = 最强。
                val cAmp = config.chargingStrengthPercent.coerceIn(
                    RingConfig.STRENGTH_MIN, RingConfig.STRENGTH_MAX,
                ) / 100f
                // 把「0..1 的脉动」按幅度缩放：0 → 恒定满值（不脉动），100 → 原样，
                // >100 → 摆幅更宽。用它替换样式里的 chargingPulse 即可统一生效。
                val pulseEff = (1f - (1f - chargingPulse) * cAmp).coerceIn(0f, 1f)

                if (config.chargingStyle == RingConfig.CHARGING_STYLE_ECHO) {
                    // ---- 能量扩散（v1.0.0 新增）：一圈柔光由内向外扩散并淡出，像在充能 ----
                    val period = (GAP_SWEEP_BASE_MS * 0.5f /
                        speedFactor(config.chargingSpeedPercent)).coerceAtLeast(420f)
                    val echoAmp = cAmp.coerceIn(0f, 1f)
                    val list = ArrayList<Echo>(ECHO_COUNT)
                    var lead = 0f
                    for (i in 0 until ECHO_COUNT) {
                        var ph = (nowMs % period.toLong()).toFloat() / period +
                            i.toFloat() / ECHO_COUNT
                        ph -= kotlin.math.floor(ph)
                        if (i == 0) lead = ph
                        list.add(
                            Echo(
                                scale = 1f + (ECHO_MAX_SCALE - 1f) * ph,
                                alpha = (1f - ph) * (1f - ph) * 0.8f * echoAmp,
                            ),
                        )
                    }
                    echoes = list
                    // 环本体保持稳定亮度（进度可读），扩散波纹只做氛围
                    ringColor = scaleAlpha(
                        progressColor,
                        (1f - 0.38f * echoAmp * (1f - lead)) * expand,
                    )
                    glowScale = 0.22f * echoAmp * expand
                } else if (config.chargingStyle == RingConfig.CHARGING_STYLE_PULSE) {
                    // ---- 整环脉冲（v1.0.0 新增）：没有光点，整圈一起心跳式明暗 ----
                    ringColor = scaleAlpha(progressColor, (0.30f + 0.70f * pulseEff) * expand)
                    ringTrack = scaleAlpha(trackColor, 0.30f)
                    glowScale = (0.30f + 0.70f * pulseEff) * cAmp * expand
                } else if (config.chargingStyle == RingConfig.CHARGING_STYLE_PULSE_SWEEP) {
                    // ---- 脉冲扫掠（v1.3.0 新增）：一段亮弧绕行，同时整环轻微脉冲 ----
                    ringColor = scaleAlpha(progressColor, (0.55f + 0.45f * pulseEff) * expand)
                    ringTrack = scaleAlpha(trackColor, 0.35f)
                    glowScale = (0.25f + 0.45f * pulseEff) * cAmp * expand
                    val h = (nowMs / 1000f * 170f * speedFactor(config.chargingSpeedPercent)) % 360f
                    runners = listOf(
                        Runner(visible, h, 170f, (0.55f + 0.45f * pulseEff) * cAmp * expand),
                    )
                } else {
                if (config.chargingStyle == RingConfig.CHARGING_STYLE_BREATH_DOT) {
                    // ---- 呼吸光点（v1.0.0 新增）：光点照常绕行 + 整环轻微呼吸 ----
                    ringColor = scaleAlpha(progressColor, (0.62f + 0.38f * pulseEff) * expand)
                    glowScale = 0.45f * pulseEff * cAmp * expand
                }
                val baseTail = when (config.chargingStyle) {
                    RingConfig.CHARGING_STYLE_DUAL -> 70f
                    RingConfig.CHARGING_STYLE_COMET -> 155f
                    RingConfig.CHARGING_STYLE_DOUBLE_COMET -> 150f
                    else -> 55f // CHARGING_STYLE_DOT
                }
                val twoHeads = config.chargingStyle == RingConfig.CHARGING_STYLE_DUAL ||
                    config.chargingStyle == RingConfig.CHARGING_STYLE_DOUBLE_COMET
                // v1.3.3「幅度」：光点/拖尾的亮度也随幅度缩放，0 时只剩进度弧
                val glow = cAmp * expand

                // 未充空白段：从进度弧末端（-90°+360°·fraction）到整圈起点
                val gapStart = -90f + 360f * fraction
                val gapSpan = 360f * (1f - fraction)

                // 覆盖范围：智能（按电量阈值在「空白段」与「整圈」之间自动切换）/
                // 一直整圈 / 一直空白段
                val gapOnly = when (config.chargingAnimMode) {
                    RingConfig.CHARGING_ANIM_FULL -> false
                    RingConfig.CHARGING_ANIM_GAP -> true
                    else -> fraction * 100f < config.chargingFullRingThreshold.coerceIn(
                        RingConfig.THRESHOLD_MIN, RingConfig.THRESHOLD_MAX,
                    )
                }

                if (gapOnly && gapSpan > 8f) {
                    // 空白段内匀速推进：整段耗时固定，避免剩余空间很小时光点疯狂打转
                    val periodMs = (GAP_SWEEP_BASE_MS / speedFactor(config.chargingSpeedPercent))
                        .coerceAtLeast(350f)
                    val phase01 = (nowMs % periodMs.toLong()).toFloat() / periodMs
                    val p = gapSpan * phase01
                    val list = ArrayList<Runner>(2)
                    // 拖尾不能越过进度弧末端：贴边界时自然收短，像光从分界处生长出来
                    list.add(Runner(visible, gapStart + p, minOf(baseTail, p), glow))
                    if (twoHeads) {
                        val p2 = (p + gapSpan / 2f) % gapSpan
                        list.add(Runner(visible, gapStart + p2, minOf(baseTail, p2), glow))
                    }
                    runners = list
                } else {
                    // 整圈模式（旧行为）：恒定角速度绕行
                    val h = (nowMs / 1000f * 150f * speedFactor(config.chargingSpeedPercent)) % 360f
                    val list = ArrayList<Runner>(2)
                    list.add(Runner(visible, h, baseTail, glow))
                    if (twoHeads) list.add(Runner(visible, (h + 180f) % 360f, baseTail, glow))
                    runners = list
                }
                }
            }

            // 3) 低电量「迪迦计时器」：电量跌破阈值且没在充电时，环像迪迦胸口的
            //    彩色计时器一样红色双闪，电量越低闪得越急。充电中不生效——相当于
            //    能量补满、计时器停响，让位给充电动画 / 呼吸灯。
            tigaActive -> {
                val thr = config.lowBatteryTigaThreshold.coerceIn(
                    RingConfig.TIGA_THRESHOLD_MIN, RingConfig.TIGA_THRESHOLD_MAX,
                ).coerceAtLeast(1)
                // 紧迫度 0→1：刚从阈值掉下来时最慢，逼近 0% 时最急
                val urgency = ((thr - state.level).toFloat() / thr).coerceIn(0f, 1f)
                val basePeriod = TIGA_PERIOD_SLOW_MS +
                    (TIGA_PERIOD_FAST_MS - TIGA_PERIOD_SLOW_MS) * urgency
                // 「闪烁频率」滑杆在这条曲线上再整体乘一个手调倍率：
                // 100% = 基准，调大更急促、调小更沉稳
                val speedK = config.lowBatteryTigaSpeedPercent.coerceIn(
                    RingConfig.SPEED_MIN, RingConfig.SPEED_MAX,
                ) / 100f
                val period = (basePeriod / speedK).coerceAtLeast(120f)
                val phase = (nowMs % period.toLong()).toFloat() / period
                // 双闪心跳：一周期两次，第二下稍弱，读起来就是计时器的「滴、滴」
                val pulse = maxOf(
                    pulseAt(phase, 0.02f, 0.18f),
                    pulseAt(phase, 0.24f, 0.38f) * 0.7f,
                )
                val timer = IslandColorGuard.ensureVisible(
                    TIGA_RED,
                    islandShowing = state.islandShowing,
                    collapseOnIsland = config.collapseOnIsland,
                )
                ringColor = scaleAlpha(timer, (TIGA_DIM + (1f - TIGA_DIM) * pulse) * expand)
                ringTrack = scaleAlpha(trackColor, 0.32f)
                glowScale = (0.20f + 0.80f * pulse) * expand
            }

            // 4) 呼吸灯：平时（或充电但选「进度弧」时）按所选形式呼吸
            config.breathingEnabled && (chargingNow || config.breathingOnIdle) -> {
                val period = breathPeriodMs(config, chargingNow)
                val amp = if (chargingNow) 0.40f else 0.18f
                val phase = (nowMs % period) / period
                val breath = 0.5f - 0.5f * kotlin.math.cos(2f * Math.PI.toFloat() * phase)
                when (config.breathingStyle) {
                    RingConfig.BREATHING_STYLE_RUNNER -> {
                        val visible = IslandColorGuard.ensureVisible(
                            pickAnimColor(config.breathingColor, progressColor),
                            islandShowing = state.islandShowing,
                            collapseOnIsland = config.collapseOnIsland,
                        )
                        ringColor = scaleAlpha(progressColor, 0.85f * expand)
                        runners = listOf(
                            Runner(
                                color = visible,
                                headDeg = (nowMs / 1000f * 45f * speedFactor(config.breathingSpeedPercent)) % 360f,
                                tailDeg = 110f,
                                glow = (0.55f + 0.45f * breath) * expand,
                            ),
                        )
                    }

                    RingConfig.BREATHING_STYLE_BRIGHTNESS -> {
                        ringColor = scaleAlpha(progressColor, (1f - amp + amp * breath) * expand)
                        ringTrack = scaleAlpha(trackColor, 1f - 0.30f * amp * (1f - breath))
                        glowScale = (if (chargingNow) 0.34f else 0.14f) * breath
                    }

                    RingConfig.BREATHING_STYLE_DOUBLE_GLOW -> {
                        // 双段柔光（v1.3.0 新增）：两段柔光在对面同步呼吸
                        val visible = IslandColorGuard.ensureVisible(
                            pickAnimColor(config.breathingColor, progressColor),
                            islandShowing = state.islandShowing,
                            collapseOnIsland = config.collapseOnIsland,
                        )
                        ringColor = scaleAlpha(progressColor, (1f - amp + amp * breath) * expand)
                        ringTrack = scaleAlpha(trackColor, 1f - 0.30f * amp * (1f - breath))
                        glowScale = if (chargingNow) 0.50f * breath else 0.20f * breath
                        val h = (nowMs / 1000f * 30f *
                            speedFactor(config.breathingSpeedPercent)) % 360f
                        val g = (0.50f + 0.50f * breath) * expand
                        runners = listOf(
                            Runner(visible, h, 150f, g),
                            Runner(visible, (h + 180f) % 360f, 150f, g),
                        )
                    }

                    RingConfig.BREATHING_STYLE_BREATH_RUNNER -> {
                        // 呼吸跑马（v1.3.0 新增）：一段柔光绕行 + 整环明暗呼吸
                        val visible = IslandColorGuard.ensureVisible(
                            pickAnimColor(config.breathingColor, progressColor),
                            islandShowing = state.islandShowing,
                            collapseOnIsland = config.collapseOnIsland,
                        )
                        ringColor = scaleAlpha(progressColor, (0.75f + 0.25f * breath) * expand)
                        glowScale = (if (chargingNow) 0.40f else 0.16f) * breath
                        runners = listOf(
                            Runner(
                                color = visible,
                                headDeg = (nowMs / 1000f * 60f *
                                    speedFactor(config.breathingSpeedPercent)) % 360f,
                                tailDeg = 120f,
                                glow = (0.50f + 0.50f * breath) * expand,
                            ),
                        )
                    }

                    else -> { // BREATHING_STYLE_GLOW
                        ringColor = scaleAlpha(progressColor, (1f - amp + amp * breath) * expand)
                        ringTrack = scaleAlpha(trackColor, 1f - 0.30f * amp * (1f - breath))
                        glowScale = if (chargingNow) 0.55f * breath else 0.22f * breath
                    }
                }
            }
        }
        // 提醒/呼吸/充电分支若引入用户自定义色，均已过岛色守卫；跟随系统的普通态
        // 由 RingState 的岛色冻结处理，两处各管各的，别删任何一层。

        // ---- 音乐律动（叠加层）：听歌时环像呼吸灯一样柔和地动 ----
        // 呼吸 / 跑马灯两种形式 + 颜色缓慢渐变；幅度克制，不抢屏幕主视觉。
        var glowColor = ringColor
        val musicNow = config.musicPulseEnabled && state.musicPulsing
        // v1.2.0：音乐停止后仍按 effectMix 续绘约 460ms 淡出，不再硬切回正常态。
        if (musicNow || (effectMix > 0.001f && lastEffectKind == EFFECT_MUSIC)) {
            // 记录叠加前的基础色，供淡入淡出插值（音乐停止时从音乐色平滑退回它）
            val preMusicRing = ringColor
            val preMusicTrack = ringTrack
            val preMusicGlow = glowScale
            val preMusicGlowColor = glowColor
            // 只有跑马灯/波浪形式会把主角交给 runners，淡出时需一并压暗
            val musicUsesRunners = config.musicPulseStyle == RingConfig.MUSIC_STYLE_RUNNER ||
                config.musicPulseStyle == RingConfig.MUSIC_STYLE_WAVE
            val strength = config.musicPulseStrengthPercent.coerceIn(
                RingConfig.MUSIC_STRENGTH_MIN, RingConfig.MUSIC_STRENGTH_MAX,
            ) / 100f
            // 「闪动范围」：一次脉动里明暗摆动的跨度。0 = 几乎不摆，越大事越明显。
            val flash = config.musicFlashRangePercent.coerceIn(
                RingConfig.MUSIC_FLASH_MIN, RingConfig.MUSIC_FLASH_MAX,
            ) / 100f
            val cycleMs = config.musicCycleSeconds.coerceIn(
                RingConfig.MUSIC_CYCLE_MIN, RingConfig.MUSIC_CYCLE_MAX,
            ) * 1000f
            // v1.3.2：开了音频驱动时，律动相位改用「实时音量驱动」的累加相位 ——
            // 呼吸 / 绕行的**频率**跟着声音走（响处快、静处慢），而不是固定周期空转。
            // 用户反馈「只有边缘在动、呼吸频率不跟着动，很割裂」，就是这里的问题。
            val liveSens = config.musicAudioSensitivityPercent.coerceIn(
                RingConfig.AUDIO_SENS_MIN, RingConfig.AUDIO_SENS_MAX,
            ) / 100f
            val liveLevel = (state.audioLevel * liveSens).coerceIn(0f, 1f)
            val useAudioPhase = config.musicAudioReactive && liveLevel > 0.004f
            val phase = if (useAudioPhase) {
                advanceAudioPhase(nowMs, cycleMs, liveLevel, AUDIO_BREATH_RATE_GAIN)
            } else {
                // 没开音频驱动 / 此刻没声音：复位累加器，下一段从 0 开始，避免相位残留
                musicAudioPhaseMs = 0L
                (nowMs % cycleMs.toLong()) / cycleMs
            }
            val breath = 0.5f - 0.5f * kotlin.math.cos(2f * Math.PI.toFloat() * phase)
            val baseForHue = pickAnimColor(config.musicColor, progressColor)
            // 颜色缓慢渐变：一个周期转一圈色相环；白/灰这类低饱和基准会被提到
            // 柔和但可辨的水准，否则根本看不出颜色在变
            val cycle = if (config.musicColorCycleEnabled) {
                musicCycleColor(baseForHue, phase * 360f)
            } else null
            if (cycle != null) {
                ringColor = scaleAlpha(cycle, android.graphics.Color.alpha(ringColor) / 255f)
                glowColor = cycle
            }
            when (config.musicPulseStyle) {
                RingConfig.MUSIC_STYLE_RUNNER -> {
                    // 跑马灯：一段柔光缓慢绕行，环本体稍压暗让光点成为主角
                    ringColor = scaleAlpha(ringColor, 0.82f)
                    ringTrack = scaleAlpha(ringTrack, 0.75f)
                    runners = listOf(
                        Runner(
                            color = cycle ?: baseForHue,
                            headDeg = phase * 360f,
                            tailDeg = 130f,
                            glow = (0.40f + 0.28f * breath) * strength * expand,
                        ),
                    )
                }

                RingConfig.MUSIC_STYLE_BEAT -> {
                    // 节拍闪动：按可调节拍柔和亮闪一下再回落。
                    // v1.2.0：默认 72 → 56 BPM、下限 40 → 24，脉冲再放软一点。
                    // 说明：系统不允许模块读取音乐真实波形/节拍（需录音权限且机型
                    // 支持不一），这里是「有音乐就按设定速度柔和地闪」的近似实现。
                    val bpm = config.musicBeatBpm.coerceIn(
                        RingConfig.MUSIC_BEAT_BPM_MIN, RingConfig.MUSIC_BEAT_BPM_MAX,
                    )
                    val beatMs = (60_000f / bpm).coerceAtLeast(320f)
                    val bph = (nowMs % beatMs.toLong()).toFloat() / beatMs
                    val punch = softBeatPulse(bph)
                    val dim = (0.14f + 0.20f * flash.coerceAtMost(1f)) * strength
                    ringColor = scaleAlpha(
                        ringColor,
                        (1f - dim * (1f - punch)).coerceIn(0.30f, 1f),
                    )
                    val g = (0.08f + 0.45f * punch) * strength * (1f + flash)
                    if (g > glowScale) glowScale = g
                }

                RingConfig.MUSIC_STYLE_HEARTBEAT -> {
                    // 心跳（v1.2.0 新增）：一周期两次「扑通」——第一下强、第二下弱，
                    // 之后一段平缓，像安静的背景心跳。比节拍闪动更慢、更柔。
                    val bpm = config.musicBeatBpm.coerceIn(
                        RingConfig.MUSIC_BEAT_BPM_MIN, RingConfig.MUSIC_BEAT_BPM_MAX,
                    )
                    // 一整组心跳（扑通两下）耗时 = 两个节拍，所以比 BEAT 慢一倍
                    val heartMs = (120_000f / bpm).coerceAtLeast(700f)
                    val hp = (nowMs % heartMs.toLong()).toFloat() / heartMs
                    val lub = pulseAt(hp, 0.02f, 0.16f)
                    val dub = pulseAt(hp, 0.22f, 0.34f) * 0.72f
                    val thump = maxOf(lub, dub)
                    val dim = (0.12f + 0.18f * flash.coerceAtMost(1f)) * strength
                    ringColor = scaleAlpha(
                        ringColor,
                        (1f - dim * (1f - thump)).coerceIn(0.35f, 1f),
                    )
                    val g = (0.06f + 0.40f * thump) * strength * (1f + flash)
                    if (g > glowScale) glowScale = g
                }

                RingConfig.MUSIC_STYLE_WAVE -> {
                    // 波浪（v1.2.0 新增）：两段很宽的光带在环上对向缓慢推进，
                    // 像水波一圈圈荡开。环本体保持基本亮度，光带很宽所以读起来
                    // 是「波」而不是「点」（与跑马灯的窄亮核区分开）。
                    ringColor = scaleAlpha(ringColor, 0.90f)
                    val bandGlow = (0.30f + 0.22f * breath) * strength * expand
                    runners = listOf(
                        Runner(cycle ?: baseForHue, phase * 360f, 190f, bandGlow),
                        Runner(cycle ?: baseForHue, (phase * 360f + 180f) % 360f, 190f, bandGlow),
                    )
                }

                RingConfig.MUSIC_STYLE_VOLUME -> {
                    // 音量脉冲（v1.3.0 新增）：亮度直接跟随**实时音量**；
                    // 未开音频驱动/拿不到音量时退化为呼吸，保证「有音乐就有动」。
                    val sens = config.musicAudioSensitivityPercent.coerceIn(
                        RingConfig.AUDIO_SENS_MIN, RingConfig.AUDIO_SENS_MAX,
                    ) / 100f
                    val lvl = if (config.musicAudioReactive) {
                        (state.audioLevel * sens).coerceIn(0f, 1f)
                    } else {
                        breath
                    }
                    val dim = (0.45f * strength * (1f + flash)).coerceAtMost(0.90f)
                    ringColor = scaleAlpha(ringColor, (1f - dim * (1f - lvl)).coerceIn(0.15f, 1f))
                    val g = (0.10f + 0.70f * lvl) * strength * (1f + flash)
                    if (g > glowScale) glowScale = g
                }

                RingConfig.MUSIC_STYLE_VOICE -> {
                    // 人声/音乐（v1.3.0 新增）：人声偏暖色呼吸、音乐偏冷色脉动。
                    // 需开音频驱动；未开时按收律动颜色做普通呼吸。
                    val isVoice = config.musicAudioReactive &&
                        state.audioKind == RingState.AUDIO_KIND_VOICE
                    val base = if (isVoice) VOICE_WARM_COLOR else (cycle ?: baseForHue)
                    val lvl = if (config.musicAudioReactive) state.audioLevel else breath
                    val dim = (0.24f * strength * (1f + flash)).coerceAtMost(0.85f)
                    ringColor = scaleAlpha(base, (1f - dim * (1f - lvl)) * expand)
                    ringTrack = scaleAlpha(ringTrack, 0.70f)
                    val g = (0.10f + 0.55f * lvl) * strength * (1f + flash)
                    if (g > glowScale) glowScale = g
                }

                else -> { // MUSIC_STYLE_BREATH：亮度缓慢起伏（最低压到 65%）
                    // flash 越大，明暗摆动跨度越大（默认 60% 已比旧版明显）
                    val dim = (0.30f * strength * (1f + flash * 1.2f)).coerceAtMost(0.90f)
                    // v1.3.2：音频驱动下把环本体亮度也直接绑到实时音量（响处更亮、
                    // 静处收暗），这样「环」和「外圈光晕」是同一个节奏在动，不再割裂。
                    // v1.3.3：把耦合加深（0.72+0.28 → 0.55+0.45），并让光晕也随音量起伏，
                    // 节拍更明显
                    val live = if (useAudioPhase) 0.55f + 0.45f * liveLevel else 1f
                    ringColor = scaleAlpha(ringColor, (1f - dim * (1f - breath)) * live)
                    val liveGlow = if (useAudioPhase) 0.45f + 0.85f * liveLevel else 1f
                    val g = 0.26f * breath * strength * (1f + flash) * liveGlow
                    if (g > glowScale) glowScale = g
                }
            }
            val custom = config.musicColor
            if (custom != 0 && custom ushr 24 != 0 && cycle == null) {
                glowColor = IslandColorGuard.ensureVisible(
                    custom,
                    islandShowing = state.islandShowing,
                    collapseOnIsland = config.collapseOnIsland,
                )
            }
            // 音频驱动（v1.3.0）：把实时音量叠加成额外的亮度脉冲，对任何形式都生效
            // （音量/人声两种形式已经直接吃音量，这里不重复叠加）
            val usesAudioLevel = config.musicPulseStyle == RingConfig.MUSIC_STYLE_VOLUME ||
                config.musicPulseStyle == RingConfig.MUSIC_STYLE_VOICE
            if (config.musicAudioReactive && !usesAudioLevel && state.audioLevel > 0.001f) {
                val sens = config.musicAudioSensitivityPercent.coerceIn(
                    RingConfig.AUDIO_SENS_MIN, RingConfig.AUDIO_SENS_MAX,
                ) / 100f
                val lvl = (state.audioLevel * sens).coerceIn(0f, 1f)
                // v1.3.3：加深音量对亮度/光晕的叠加，节拍感更足
                ringColor = scaleAlpha(ringColor, (0.68f + 0.32f * lvl).coerceIn(0f, 1f))
                val boost = 0.10f + 0.75f * lvl
                if (boost > glowScale) glowScale = boost
            }
            // 淡入淡出：音乐开始 / 结束时，在正常环色与音乐色之间平滑过渡
            if (effectMix < 0.999f) {
                ringColor = lerpColor(preMusicRing, ringColor, effectMix)
                ringTrack = lerpColor(preMusicTrack, ringTrack, effectMix)
                glowScale = preMusicGlow + (glowScale - preMusicGlow) * effectMix
                glowColor = lerpColor(preMusicGlowColor, glowColor, effectMix)
                if (musicUsesRunners) {
                    runners = runners.map { it.copy(glow = it.glow * effectMix) }
                }
            }
            if (musicNow) lastEffectKind = EFFECT_MUSIC
        }


        // ---- 发光强度（统一缩放所有光晕；0 = 完全不发光）----
        // 白底/浅色环上，光晕会把亚像素位移的观感放大，关掉更干净；
        // 深色背景想更醒目就调高。只影响光晕，不吃环与进度本身的可见度。
        val glowK = config.glowStrengthPercent.coerceIn(RingConfig.GLOW_MIN, RingConfig.GLOW_MAX) / 100f
        glowScale *= glowK

        // 发电量数字与光晕在统一的光晕缩放之后不再做场景补偿：
        // v1.2.0 起息屏（AOD）注入视图已移除，渲染只发生在常亮屏上。

        val colorNow = android.os.SystemClock.uptimeMillis()
        if (progressColor != lastLoggedColor && colorNow - lastColorLogMs >= 500L) {
            lastLoggedColor = progressColor
            lastColorLogMs = colorNow
            ModuleLog.i(
                "环配色: mode=${config.colorMode} color=#${Integer.toHexString(progressColor)} " +
                    "level=${state.level} ranges=${config.levelRanges.size}",
            )
        }

        // 用户手动微调：缩放（半径）+ 上下左右偏移
        val baseRadius = (hole.holeRadius + gap + stroke / 2f) * config.scale
        val cxRaw = hole.cx + config.offsetXDp * density
        val cyRaw = hole.cy + config.offsetYDp * density
        val baseRingRadius = baseRadius * expand

        // 防烧屏：位移 + 亮度上限。位移走九宫格慢速路径，亮度上限压住高亮白/
        // 饱和色长期静置的累积——OLED 残影主要由「固定位置 + 高亮度」两个因素叠加造成。
        val burnIn = config.burnInProtection
        val (shiftX, shiftY) = if (burnIn) burnInOffset(nowMs) else 0f to 0f
        // 主环保持原始几何不变（「细胞分裂」里主环不动，只有小环分裂出去）
        val radius = baseRingRadius
        val drawCx = cxRaw + shiftX
        val drawCy = cyRaw + shiftY
        if (burnIn) {
            val cap = config.maxBrightnessPercent.coerceIn(30, 100) / 100f
            ringColor = scaleAlpha(ringColor, cap)
            ringTrack = scaleAlpha(ringTrack, cap)
            runners = runners.map { it.copy(color = scaleAlpha(it.color, cap), glow = it.glow * cap) }
            echoes = echoes.map { it.copy(alpha = it.alpha * cap) }
        }

        if (!diagLogged) {
            diagLogged = true
            ModuleLog.i(
                "环绘制参数: fraction=$fraction level=${state.level} charging=${state.charging} " +
                    "color=#${Integer.toHexString(progressColor)} cx=$cxRaw cy=$cyRaw radius=$radius " +
                    "stroke=$stroke scale=${config.scale} off=(${config.offsetXDp},${config.offsetYDp}), collapse=${state.collapseProgress}",
            )
        }

        canvas.save()
        try {
            // 0) 呼吸光晕（最底层，只在呼吸/闪烁/音乐律动时出现）
            if (glowScale > 0.02f) {
                val glowA = (0x55 * glowScale).toInt().coerceIn(0, 255)
                glowPaint.strokeWidth = stroke * 2.6f
                glowPaint.color = (glowA shl 24) or (glowColor and 0x00FFFFFF)
                canvas.drawCircle(drawCx, drawCy, radius, glowPaint)
            }

            // 1) 底槽整圆
            trackPaint.strokeWidth = stroke
            trackPaint.color = ringTrack
            canvas.drawCircle(drawCx, drawCy, radius, trackPaint)

            // 换电量为 0% 且当前没有任何动效呈现时，整环无可画内容，直接跳过。
            if (fraction <= 0f && nowMs >= state.percentFlashUntil &&
                runners.isEmpty() && echoes.isEmpty()
            ) {
                return
            }

            // 2) 电量进度弧
            if (fraction > 0f) {
                progressPaint.strokeWidth = stroke
                progressPaint.color = ringColor
                if (fraction >= 0.999f) {
                    // 满电直接画整圆，避免 ROUND 线帽在 360° 接缝处产生凸起
                    canvas.drawCircle(drawCx, drawCy, radius, progressPaint)
                } else {
                    val sweep = 360f * fraction
                    // drawArc 参数为 left, top, right, bottom（边界坐标，不是宽高）
                    canvas.drawArc(
                        drawCx - radius, drawCy - radius, drawCx + radius, drawCy + radius,
                        -90f, sweep, false, progressPaint,
                    )
                }
            }

            // 2b) 跑马光点 / 彗尾：充电动画、呼吸跑马、提醒跑马共用同一条绘制通路
            for (r in runners) {
                drawRunner(canvas, drawCx, drawCy, radius, stroke, r)
            }

            // 2b-2) 充电「能量扩散」：向外扩散并淡出的柔光波纹（画在进度弧之上、光点之下）
            for (e in echoes) {
                val alpha = (0x66 * e.alpha * glowK).toInt().coerceIn(0, 255)
                if (alpha <= 2) continue
                echoPaint.strokeWidth = stroke * 0.85f
                echoPaint.color = (alpha shl 24) or (ringColor and 0x00FFFFFF)
                canvas.drawCircle(drawCx, drawCy, radius * e.scale, echoPaint)
            }

            // 3) 点击挖孔后显示的电量数字（挖孔正下方，末段淡出）
            val remain = state.percentFlashUntil - android.os.SystemClock.uptimeMillis()
            if (remain > 0 && config.tapShowPercent && expand > 0.01f) {
                val fade = (remain.coerceAtMost(PERCENT_FADE_MS).toFloat() / PERCENT_FADE_MS)
                val alpha = (0xFF * fade).toInt().coerceIn(0, 255)
                textPaint.textSize = 11f * density
                textPaint.color = (alpha shl 24) or (ringColor and 0x00FFFFFF)
                val label = "${state.level}%"
                val tw = textPaint.measureText(label)
                val ty = drawCy + radius + stroke * 2.4f + textPaint.textSize
                canvas.drawText(label, drawCx - tw / 2f, ty, textPaint)
            }
        } finally {
            canvas.restore()
        }
    }

    /**
     * 「按电池状态」：状态判定优先级与系统 `getProgressStatus()` 一致
     * （充电 > 性能 > 省电 > 低电 > 普通）。系统调色板可用时借用它的状态位，
     * 否则退回本模块从广播侧能拿到的 charging / powerSave / level。
     * 用户未设置（0）的槽位逐槽回退：系统色 → 内置令牌色。
     */
    private fun batteryStateColor(
        config: RingConfig,
        palette: BatteryPalette,
        state: RingState,
        darkIcons: Boolean,
    ): Int {
        val useSystem = palette.ready
        val charging = if (useSystem) palette.chargingNow else state.charging
        val performance = useSystem && palette.performanceNow
        val powerSave = if (useSystem) palette.powerSaveNow else state.powerSave
        val low = if (useSystem) palette.lowNow else state.level <= LOW_BATTERY_THRESHOLD
        val s = config.stateColors
        return when {
            charging -> s.charging.orElse(
                if (useSystem) palette.charging else MiuixPalette.primaryColor(darkIcons),
            )

            performance -> s.performance.orElse(
                if (useSystem) palette.performance else MiuixPalette.PERFORMANCE_ORANGE,
            )

            powerSave -> s.powerSave.orElse(
                if (useSystem) palette.powerSave else MiuixPalette.POWER_SAVE_AMBER,
            )

            low -> s.low.orElse(
                if (useSystem) palette.low else MiuixPalette.errorColor(darkIcons),
            )

            else -> s.normal.orElse(
                if (useSystem) state.normalColor else MiuixPalette.foregroundColor(darkIcons),
            )
        }
    }

    /**
     * 「按电量区间」：按列表顺序取第一条命中 `state.level` 的区间色。
     * 过边界时颜色就该明确跳变，不做插值——否则颜色会与弧度不同步。
     * 未命中任何区间时退回「跟随系统」那条链，保证环在深浅背景下都可见。
     */
    private fun levelRangeColor(
        config: RingConfig,
        palette: BatteryPalette,
        state: RingState,
        darkIcons: Boolean,
    ): Int {
        val hit = CustomColors.colorForLevel(config.levelRanges, state.level)
        if (hit != 0) return hit
        return when {
            palette.ready -> state.normalColor
            state.level <= LOW_BATTERY_THRESHOLD -> MiuixPalette.errorColor(darkIcons)
            state.charging -> MiuixPalette.primaryColor(darkIcons)
            state.powerSave -> MiuixPalette.POWER_SAVE_AMBER
            else -> MiuixPalette.foregroundColor(darkIcons)
        }
    }

    /**
     * 0（未设置）或全透明（alpha=00）都取回退色。
     * 全透明按未设置处理：色盘初值、十六进制输入都可能带出 alpha=0 的颜色，
     * 若照单画出来环会整个消失，比「没变色」更让人困惑。
     */
    private fun Int.orElse(fallback: Int): Int =
        if (this == 0 || this ushr 24 == 0) fallback else this

    /** 按比例缩放颜色透明度，用于沉浸收缩的淡出 */
    private fun scaleAlpha(color: Int, factor: Float): Int {
        val a = (android.graphics.Color.alpha(color) * factor).toInt().coerceIn(0, 255)
        return (a shl 24) or (color and 0x00FFFFFF)
    }

    /**
     * 两个颜色按 [t]（0–1）线性插值（含 alpha）。
     * 用于「效果淡入淡出」：t=0 取 [from]（正常环色），t=1 取 [to]（效果色）。
     */
    private fun lerpColor(from: Int, to: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        val a = (android.graphics.Color.alpha(from) +
            (android.graphics.Color.alpha(to) - android.graphics.Color.alpha(from)) * k)
            .toInt().coerceIn(0, 255)
        val r = (android.graphics.Color.red(from) +
            (android.graphics.Color.red(to) - android.graphics.Color.red(from)) * k)
            .toInt().coerceIn(0, 255)
        val g = (android.graphics.Color.green(from) +
            (android.graphics.Color.green(to) - android.graphics.Color.green(from)) * k)
            .toInt().coerceIn(0, 255)
        val b = (android.graphics.Color.blue(from) +
            (android.graphics.Color.blue(to) - android.graphics.Color.blue(from)) * k)
            .toInt().coerceIn(0, 255)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    // ---- 动画驱动（v0.3） ----

    /** 动画速度百分数 → 倍率（20–200 映射到 0.2×–2×）。 */
    private fun speedFactor(percent: Int): Float =
        percent.coerceIn(RingConfig.SPEED_MIN, RingConfig.SPEED_MAX) / 100f

    /**
     * 提醒进场判断用的「通知到达时刻」在 [RingState.notifSinceMs]，
     * 渲染端直接读 [RingState.notifAgeMs] 与 [RingState.hasNotifAge]。
     */

    /**
     * 音乐律动的「缓慢变色」：把基准色提到柔和可辨的水准后按 degrees 转色相。
     * 白/灰这类低饱和颜色转色相看不出变化，所以饱和度/明度会被抬到
     * 「柔和但明确」的水准（0.55 / 0.90），观感接近 RGB 呼吸灯但更克制。
     */
    private fun musicCycleColor(base: Int, degrees: Float): Int {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(base, hsv)
        hsv[1] = maxOf(hsv[1], 0.55f)
        hsv[2] = maxOf(hsv[2], 0.90f)
        hsv[0] = (hsv[0] + degrees) % 360f
        return android.graphics.Color.HSVToColor(android.graphics.Color.alpha(base), hsv)
    }

    // ---- 音乐律动 ----

    /** 呼吸周期（ms）：速度系数越大周期越短；充电时基准本身更快更明显。 */
    private fun breathPeriodMs(config: RingConfig, charging: Boolean): Float {
        val base = if (charging) BREATH_PERIOD_CHARGING_MS else BREATH_PERIOD_IDLE_MS
        return base / speedFactor(config.breathingSpeedPercent)
    }

    /**
     * 跑马/彗尾光点参数。
     *
     * [headDeg] 为头部起始角度（顺时针为正，0° 在 12 点方向），
     * [tailDeg] 为拖尾总张角，[glow] 同时充当整体亮度与脉动系数（0–1）。
     * 「双光点追逐」通过传入两个 Runner 实现（各自拖尾单独裁剪），
     * 因此这里不再需要 duo 之类的合并字段。
     */
    private data class Runner(
        val color: Int,
        val headDeg: Float,
        val tailDeg: Float,
        val glow: Float,
    )

    /** 光点拖尾分段数：18 段在细描边上已看不出台阶。 */
    private const val RUNNER_SEGMENTS = 18

    /**
     * 画一段「光点 + 渐变拖尾」（魅族环形呼吸灯的观感）。
     *
     * 拖尾用若干首尾相接的小弧叠出，透明度按二次曲线从头部向尾部衰减，
     * 线宽也随尾部收细；头部再补一段圆头亮核。角度增大方向为顺时针，
     * 与进度弧一致（拖尾落在头部「身后」）。
     */
    private fun drawRunner(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        stroke: Float,
        runner: Runner,
    ) {
        val step = runner.tailDeg / RUNNER_SEGMENTS

        fun drawHead(headDeg: Float) {
            for (i in 0 until RUNNER_SEGMENTS) {
                val frac = i / RUNNER_SEGMENTS.toFloat()
                val falloff = (1f - frac) * (1f - frac)
                val alpha = (0xFF * runner.glow * falloff).toInt().coerceIn(0, 255)
                if (alpha <= 2) continue
                runnerPaint.strokeWidth = stroke * (1f - 0.30f * frac)
                runnerPaint.color = (alpha shl 24) or (runner.color and 0x00FFFFFF)
                canvas.drawArc(
                    cx - radius, cy - radius, cx + radius, cy + radius,
                    headDeg - frac * runner.tailDeg, -step, false, runnerPaint,
                )
            }
            val headAlpha = (0xFF * runner.glow).toInt().coerceIn(0, 255)
            headPaint.strokeWidth = stroke * 1.1f
            headPaint.color = (headAlpha shl 24) or (runner.color and 0x00FFFFFF)
            canvas.drawArc(
                cx - radius, cy - radius, cx + radius, cy + radius,
                headDeg, -step, false, headPaint,
            )
        }

        drawHead(runner.headDeg)
    }


    /**
     * 充电「能量扩散」波纹：只记「相对半径的倍数 + 透明度」，
     * 实际半径在绘制时乘以环半径，因此缩放/偏移变化都能自动跟随。
     */
    private data class Echo(val scale: Float, val alpha: Float)

    /** 二次缓出的收尾曲线：起步快、收尾稳，撑大/收拢都不会显得突兀。 */
    private fun easeOut(t: Float): Float {
        val k = t.coerceIn(0f, 1f)
        return 1f - (1f - k) * (1f - k)
    }

    /**
     * 「节拍闪动」用的柔和脉冲曲线（v1.0.0）。
     *
     * 一个周期内：前 60% 用 sin 缓入爬到峰值，后 40% 缓出。相比旧版
     * `pulseAt(t, 0, 0.30)` 那种窄而硬的尖峰，这里起落都缓、覆盖大半拍，
     * 观感像「跟着节拍轻轻亮一下」而不是「抽搐式闪一下」。
     */
    private fun softBeatPulse(t: Float): Float {
        val p = t.coerceIn(0f, 1f)
        val halfPi = (Math.PI / 2).toFloat()
        // v1.2.0：起落更缓——前 70% 缓入、后 30% 缓出，并留一点底（不压到全暗）
        val v = if (p <= 0.7f) {
            kotlin.math.sin((p / 0.7f) * halfPi)
        } else {
            kotlin.math.cos(((p - 0.7f) / 0.3f) * halfPi)
        }
        return (0.06f + 0.94f * v).coerceIn(0f, 1f)
    }
}
