package com.powerring.hole.ring

/**
 * 模块配置（在 SystemUI 进程中只读）。
 *
 * 配置页写入本模块私有 SharedPreferences，再由 [com.powerring.hole.core.HookPrefs]
 * 跨进程读取。所有 key 必须与 ui/PrefsStore 保持一致。
 */
data class RingConfig(
    /** 总开关：挖孔环形电量 */
    val ringEnabled: Boolean = true,
    /** 隐藏状态栏原电池图标（仅在环可用时生效） */
    val hideBattery: Boolean = true,
    /** 状态栏被系统自动收起（沉浸模式）时，环向内收缩并隐藏 */
    val collapseOnImmersive: Boolean = true,
    /** 灵动岛（超级岛）显示时，圆环像全屏沉浸时一样向内收缩并淡出 */
    val collapseOnIsland: Boolean = false,
    /**
     * 下拉通知栏/控制中心时，圆环向内收缩并淡出（面板收起后弹回）。
     * 默认关闭：环窗口（层带 231000）压在面板（171000）之上，
     * 开启后面板展开期间环不可见，指关节截图会同时不含环。
     */
    val collapseOnShade: Boolean = false,
    /**
     * 截图时不出现在画面里（窗口层 FLAG_SECURE）。
     * 同一规则使录屏/投屏画面也不含环；环在物理屏幕上始终正常显示。
     */
    val hideOnScreenshot: Boolean = true,
    /** 横屏（挖孔换到侧边、环无法显示）时恢复显示状态栏原生电池图标 */
    val restoreBatteryOnLandscape: Boolean = true,
    /** 环描边宽度（dp） */
    val strokeWidthDp: Float = 2.5f,
    /**
     * 底槽浓度（百分比，0–100）：未充满部分的浅色轨道圆的可见度。
     * 0 = 完全隐藏底槽（白底上不会留下一圈浅灰印记），100 = 默认观感。
     */
    val trackOpacityPercent: Int = 100,
    /**
     * 贴孔间隙（dp）：环内边与挖孔**安全区**边缘的距离。
     * 系统上报的安全区矩形通常比物理挖孔大一圈（保护边距），
     * 默认缝隙感明显时把这里往负调，让环直接压到物理孔边缘。
     */
    val gapDp: Float = 0.8f,
    /** 水平偏移（dp），正值向右 */
    val offsetXDp: Float = 0f,
    /** 垂直偏移（dp），正值向下 */
    val offsetYDp: Float = 0f,
    /** 环半径缩放，1.0 为默认，用于精确贴合挖孔 */
    val scale: Float = 1f,
    /**
     * 遗留字段：旧版本的「自定义颜色」开关。现由 [colorMode] 取代，
     * 仅在 [colorMode] 尚未写入配置时用于推导模式（见 `PrefsStore.resolveColorMode`）。
     */
    val useCustomColor: Boolean = false,
    /** 自定义颜色（ARGB）；仅 [colorMode] 为 MODE_FIXED_COLOR 时生效 */
    val customColor: Int = 0xFF277AF7.toInt(),
    /** 配色模式，四选一互斥，取值见 [MODE_FOLLOW_SYSTEM] 等常量 */
    val colorMode: Int = MODE_FOLLOW_SYSTEM,
    /** 「按电池状态」模式下五路用户色；单路为 0 表示未设置、该状态回退系统色 */
    val stateColors: StateColors = StateColors.DEFAULT,
    /** 「按电量区间」模式的区间表；列表顺序即优先级，第一条命中生效 */
    val levelRanges: List<ColorRange> = emptyList(),
    /** 呼吸灯效：环外圈叠加一圈缓慢呼吸的光晕 */
    val breathingEnabled: Boolean = true,
    /** 平时（未充电）也保持呼吸；关闭后仅在充电时呼吸 */
    val breathingOnIdle: Boolean = true,
    /** 有未读通知时红/橙交替呼吸闪烁 */
    val blinkOnNotification: Boolean = true,
    /** 提醒闪烁主色（新鲜期内与副色交替） */
    val blinkColorAlert: Int = 0xFFFF3B30.toInt(),
    /** 提醒闪烁副色 */
    val blinkColorAlt: Int = 0xFFFF9F0A.toInt(),
    /**
     * 新消息快速提示：通知到达瞬间先快速闪 1–2 下（又快又鲜艳），
     * 之后转入很慢、很淡的常驻呼吸，不会一直高频闪烁。
     * 关闭后跳过闪烁，直接进入慢呼吸。
     */
    val blinkIntroEnabled: Boolean = true,
    /** 进场提示时长（秒，1–5）：这段时间内快速闪 1–2 下 */
    val blinkIntroSeconds: Int = 2,
    /**
     * 亮屏（正常使用）期间播报提醒。
     *
     * v1.2.0：息屏提醒（AOD）整块下线后，提醒只保留这一个时机开关；
     * 息屏时环一律不绘制（既不播报也不残留，省电且不会在息屏页面留下亮斑）。
     */
    val blinkOnScreen: Boolean = true,
    /**
     * 提醒持续方式，取值见 [BLINK_NOTIF_MODE_ALWAYS] 等：
     * - 常驻：只要还有未读通知就一直（很淡地）呼吸；
     * - 限时：通知到达后只播报 [blinkNotifDurationSeconds] 秒，之后就恢复普通环。
     */
    val blinkNotifMode: Int = BLINK_NOTIF_MODE_ALWAYS,
    /** 限时模式的持续时长（秒，[NOTIF_DURATION_MIN]–[NOTIF_DURATION_MAX]） */
    val blinkNotifDurationSeconds: Int = 12,
    /**
     * 锁屏时隐藏圆环（v1.3.3 新增）。
     *
     * 打开后：锁屏（尚未解锁）期间**圆环整体收起不绘制**，并把状态栏原生电池图标
     * 交还系统——观感就是「保留系统原锁屏、去掉圆环」。解锁进桌面后圆环自动回来。
     */
    val hideRingOnLockScreen: Boolean = false,
    /** 点击挖孔区域，在环下方短暂显示具体电量数字 */
    val tapShowPercent: Boolean = true,
    /** 电量数字显示时长（ms，1000–6000）：点击挖孔与「电量节点」自动弹出共用 */
    val tapPercentDurationMs: Int = 2600,
    /** 防烧屏：环整体亚像素级缓慢漂移 + 限制最高亮度，避免固定图形长期静置留下残影 */
    val burnInProtection: Boolean = true,
    /** 亮度上限（百分比，30–100）：环、底槽与光晕的透明度都不会超过它 */
    val maxBrightnessPercent: Int = 85,
    /**
     * 发光强度（百分比，0–150）：统一缩放所有光晕（呼吸/充电/提醒/音乐律动）。
     * 0 = 完全不发光——白底浅色环上光晕会把细微位移放大，关掉更干净；
     * 深色背景想更醒目就往上调。
     */
    val glowStrengthPercent: Int = 100,
    /** 充电时环的动画形式，取值见 [CHARGING_STYLE_PROGRESS] 等 */
    val chargingStyle: Int = CHARGING_STYLE_DOT,
    /** 充电动画颜色（ARGB）；0 或全透明表示跟随环色 */
    val chargingColor: Int = 0,
    /** 充电动画速度（百分数，[SPEED_MIN]–[SPEED_MAX]，100 为基准） */
    val chargingSpeedPercent: Int = 100,
    /**
     * 充电动画幅度（百分数，[STRENGTH_MIN]–[STRENGTH_MAX]，100 为基准，v1.3.3）。
     *
     * 与音乐律动的「幅度」同一个意思：整体缩放充电动画的亮度起伏与光晕强弱。
     * 0 = 只剩平静的进度弧（几乎不动效），调大更醒目。
     */
    val chargingStrengthPercent: Int = 100,
    /**
     * 充电动画的覆盖范围，取值见 [CHARGING_ANIM_SMART] 等：
     * - 智能：电量低于 [chargingFullRingThreshold] 时只在空白段流动，达到阈值后整圈流动；
     * - 全环：无论电量多少都整圈流动；
     * - 空白段：只在未充的空白段流动（进度弧 = 已充部分，一眼看出充到哪）。
     */
    val chargingAnimMode: Int = CHARGING_ANIM_SMART,
    /** 智能模式的切换阈值（百分比，10–95）：电量达到它就改为整圈流动 */
    val chargingFullRingThreshold: Int = 60,
    /** 呼吸灯形式，取值见 [BREATHING_STYLE_GLOW] 等 */
    val breathingStyle: Int = BREATHING_STYLE_GLOW,
    /** 呼吸灯颜色（ARGB）；0 或全透明表示跟随环色 */
    val breathingColor: Int = 0,
    /** 呼吸速度（百分数，50–200） */
    val breathingSpeedPercent: Int = 100,
    /** 提醒闪烁形式，取值见 [BLINK_STYLE_ALTERNATE] 等 */
    val blinkStyle: Int = BLINK_STYLE_ALTERNATE,
    /** 提醒闪烁速度（百分数，[SPEED_MIN]–[SPEED_MAX]） */
    val blinkSpeedPercent: Int = 100,
    /**
     * 提醒闪烁幅度（百分数，[STRENGTH_MIN]–[STRENGTH_MAX]，100 为基准，v1.3.3）。
     *
     * 与音乐律动的「幅度」同一个意思：整体缩放提醒明暗摆动的跨度与光晕强弱。
     * 0 = 只保留提醒颜色、几乎不再明暗闪动；调大更醒目。
     */
    val blinkStrengthPercent: Int = 100,
    /**
     * 电量节点自动显示：电量跨过 [percentNodes] 中任一节点时，
     * 环下方短暂显示一次当前电量数字。
     */
    val percentNodesEnabled: Boolean = true,
    /** 电量节点列表（1–100），默认 20 / 80 / 100 */
    val percentNodes: List<Int> = PercentNodes.DEFAULT,
    /**
     * 音乐律动：检测到音乐播放时，环按节拍脉动（只调亮度与光晕，
     * 不改弧形与进度，因此可与充电动画/呼吸/提醒叠加）。
     */
    val musicPulseEnabled: Boolean = true,
    /** 律动形式，取值见 [MUSIC_STYLE_BREATH] 等 */
    val musicPulseStyle: Int = MUSIC_STYLE_BREATH,
    /** 呼吸/绕行一个周期的时间（秒，2–16），越大越慢越不抢戏 */
    val musicCycleSeconds: Int = 6,
    /** 颜色缓慢渐变：律动期间色相随时间慢慢转，像 RGB 呼吸灯 */
    val musicColorCycleEnabled: Boolean = true,
    /** 律动颜色（ARGB）；0 或全透明表示跟随环色。颜色渐变开启时作为基准色相 */
    val musicColor: Int = 0,
    /** 律动幅度（百分比，0–200）：100 为克制的呼吸灯观感 */
    val musicPulseStrengthPercent: Int = 100,
    /**
     * 音乐律动的「闪动范围」（百分比，0–200）。
     *
     * [musicPulseStrengthPercent] 决定「整体有多亮」，本项决定「一次脉动里明暗
     * 摆动的跨度」。默认 60：观感比旧版明显（旧版摆动过小，用户反馈「看不出来」）；
     * 调到 0 = 只保留极轻微呼吸，调到 200 = 明暗反差很大、像心跳一样跳。
     */
    val musicFlashRangePercent: Int = 60,
    /**
     * 「节拍闪动 / 心跳」形式的节拍速度（BPM，[MUSIC_BEAT_BPM_MIN]–[MUSIC_BEAT_BPM_MAX]）。
     *
     * v1.0.0：用户反馈「跟拍太快、也没完全按节奏来、太难看」，因此把默认节拍从
     * 120 BPM 降到 72 BPM（慢近一半），并单独给出速度滑杆。系统不允许模块读取
     * 音乐真实波形/节拍（需录音权限且机型支持不一），这里是「有音乐就按这个速度
     * 柔和地闪」的近似实现，放慢后更像背景呼吸灯、不抢戏。
     * v1.2.0：用户反馈「拉到最低还是太快、不够优雅」，默认再降到 56，下限放宽到 24。
     */
    val musicBeatBpm: Int = 56,
    /**
     * 音频驱动律动（v1.3.0）：用麦克风实时读取音量/人声-音乐特征，让律动与声音同步。
     *
     * 需要 RECORD_AUDIO 权限（在模块应用里采集后广播给 SystemUI）。默认关：
     * 打开后若未授权会提示去授权；机型不支持时可手动关掉。关闭时退回「有音乐就按
     * 设定速度柔和脉动」的近似实现。
     */
    val musicAudioReactive: Boolean = false,
    /**
     * 音频驱动的「反应源」，取值见 [MUSIC_REACT_ALL] 等：
     * 全部（人声与音乐都反应）/ 只随音乐 / 只随人声。
     */
    val musicReactSource: Int = MUSIC_REACT_ALL,
    /** 音频驱动的灵敏度（百分数，[AUDIO_SENS_MIN]–[AUDIO_SENS_MAX]）：越大越容易点亮 */
    val musicAudioSensitivityPercent: Int = 100,
    /**
     * 下拉控制中心 / 通知面板展开时隐藏圆环。
     *
     * 环是独立窗口（层带 231000），比控制中心面板还高，下拉时会压在控制中心上，
     * 而原生电池图标又已被本模块隐藏 —— 于是控制中心顶部既没有图标也不好看。
     * 打开本项后：面板展开时环收起，同时把状态栏原生电池图标交还系统，
     * 控制中心里显示正常的电量效果；面板收起后环自动回来。
     */
    val hideInShade: Boolean = true,
    /**
     * 下拉**控制中心**（HyperOS 右侧那一屏，QS 开关/亮度/音量）时隐藏圆环。
     *
     * 与 [hideInShade]（通知中心）是两个独立开关：HyperOS 把通知中心与控制中心
     * 做成了两块不同的面板，各自的显隐信号也不同（通知中心走
     * `ShadeControllerImpl.mExpandedVisible`，控制中心走
     * `ControlCenterExpandControllerDelegate`）。两块都要勾选才会各自收起。
     */
    val hideInControlCenter: Boolean = true,
    /**
     * 低电量「迪迦计时器」特效：电量低于 [lowBatteryTigaThreshold] 时，
     * 环像迪迦胸口的彩色计时器一样红色双闪脉动，电量越低跳得越急。
     * 充电中不启用（相当于补充能量，计时器停止告警）。默认关，避免打扰。
     */
    val lowBatteryTigaEnabled: Boolean = false,
    /** 迪迦特效触发电量（百分比，[TIGA_THRESHOLD_MIN]–[TIGA_THRESHOLD_MAX]） */
    val lowBatteryTigaThreshold: Int = 20,
    /**
     * 迪迦特效闪烁频率（百分数，[SPEED_MIN]–[SPEED_MAX]，100 为基准）。
     *
     * 迪迦计时器的双闪周期本身会随电量降低而变急（刚跌破阈值最慢、逼近 0% 最急）；
     * 本项是在那条曲线上再整体乘一个手调倍率：调小更沉稳、调大更急促。
     */
    val lowBatteryTigaSpeedPercent: Int = 100,
) {
    companion object {
        const val PREFS_NAME = "hole_power_ring_prefs"

        const val KEY_RING_ENABLED = "ring_enabled"
        const val KEY_HIDE_BATTERY = "hide_battery"
        const val KEY_COLLAPSE_ON_IMMERSIVE = "collapse_on_immersive"
        const val KEY_COLLAPSE_ON_ISLAND = "collapse_on_island"
    const val KEY_COLLAPSE_ON_SHADE = "collapse_on_shade"
    const val KEY_HIDE_ON_SCREENSHOT = "hide_on_screenshot"
        const val KEY_RESTORE_BATTERY_ON_LANDSCAPE = "restore_battery_on_landscape"
        const val KEY_STROKE_WIDTH = "stroke_width_dp"
        const val KEY_TRACK_OPACITY = "track_opacity_percent"
        const val KEY_GAP_DP = "gap_dp"
        const val KEY_OFFSET_X = "offset_x_dp"
        const val KEY_OFFSET_Y = "offset_y_dp"
        const val KEY_SCALE = "ring_scale"
        const val KEY_USE_CUSTOM_COLOR = "use_custom_color"
        const val KEY_CUSTOM_COLOR = "custom_color_argb"
        const val KEY_COLOR_MODE = "color_mode"
        const val KEY_STATE_COLOR_NORMAL = "state_color_normal"
        const val KEY_STATE_COLOR_LOW = "state_color_low"
        const val KEY_STATE_COLOR_POWER_SAVE = "state_color_power_save"
        const val KEY_STATE_COLOR_PERFORMANCE = "state_color_performance"
        const val KEY_STATE_COLOR_CHARGING = "state_color_charging"
        const val KEY_LEVEL_RANGES = "level_range_colors"
        const val KEY_BREATHING_ENABLED = "breathing_enabled"
        const val KEY_BREATHING_ON_IDLE = "breathing_on_idle"
        const val KEY_BLINK_ON_NOTIFICATION = "blink_on_notification"
        const val KEY_BLINK_COLOR_ALERT = "blink_color_alert"
        const val KEY_BLINK_COLOR_ALT = "blink_color_alt"
        // key 沿用旧名，兼容老用户已保存的配置；语义已改为「进场提示」
        const val KEY_BLINK_INTRO_ENABLED = "blink_decay_enabled"
        const val KEY_BLINK_INTRO_SECONDS = "blink_fresh_seconds"
        const val KEY_BLINK_ON_SCREEN = "blink_on_screen"
        const val KEY_BLINK_NOTIF_MODE = "blink_notif_mode"
        const val KEY_BLINK_NOTIF_DURATION = "blink_notif_duration_seconds"
        const val KEY_TAP_SHOW_PERCENT = "tap_show_percent"
        const val KEY_TAP_PERCENT_DURATION = "tap_percent_duration_ms"
        const val KEY_BURN_IN_PROTECTION = "burn_in_protection"
        const val KEY_MAX_BRIGHTNESS_PERCENT = "max_brightness_percent"
        const val KEY_GLOW_STRENGTH = "glow_strength_percent"
        const val KEY_CHARGING_STYLE = "charging_style"
        const val KEY_CHARGING_COLOR = "charging_color"
        const val KEY_CHARGING_SPEED = "charging_speed_percent"
        const val KEY_CHARGING_STRENGTH = "charging_strength_percent"
        const val KEY_BREATHING_STYLE = "breathing_style"
        const val KEY_BREATHING_COLOR = "breathing_color"
        const val KEY_BREATHING_SPEED = "breathing_speed_percent"
        const val KEY_BLINK_STYLE = "blink_style"
        const val KEY_BLINK_SPEED = "blink_speed_percent"
        const val KEY_BLINK_STRENGTH = "blink_strength_percent"
        const val KEY_CHARGING_ANIM_MODE = "charging_anim_mode"
        const val KEY_CHARGING_FULL_THRESHOLD = "charging_full_ring_threshold"
        const val KEY_PERCENT_NODES_ENABLED = "percent_nodes_enabled"
        const val KEY_PERCENT_NODES = "percent_nodes"
        const val KEY_MUSIC_PULSE_ENABLED = "music_pulse_enabled"
        const val KEY_MUSIC_PULSE_STYLE = "music_pulse_style"
        const val KEY_MUSIC_CYCLE_SECONDS = "music_cycle_seconds"
        const val KEY_MUSIC_COLOR_CYCLE = "music_color_cycle"
        const val KEY_MUSIC_COLOR = "music_color"
        const val KEY_MUSIC_PULSE_STRENGTH = "music_pulse_strength_percent"
        const val KEY_MUSIC_FLASH_RANGE = "music_flash_range_percent"
        const val KEY_MUSIC_BEAT_BPM = "music_beat_bpm"
        const val KEY_MUSIC_AUDIO_REACTIVE = "music_audio_reactive"
        const val KEY_MUSIC_REACT_SOURCE = "music_react_source"
        const val KEY_MUSIC_AUDIO_SENS = "music_audio_sensitivity_percent"
        const val KEY_HIDE_RING_ON_LOCK_SCREEN = "hide_ring_on_lock_screen"
        const val KEY_HIDE_IN_SHADE = "hide_in_shade"
        const val KEY_HIDE_IN_CONTROL_CENTER = "hide_in_control_center"
        const val KEY_LOW_BATTERY_TIGA = "low_battery_tiga_enabled"
        const val KEY_LOW_BATTERY_TIGA_THRESHOLD = "low_battery_tiga_threshold"
        const val KEY_LOW_BATTERY_TIGA_SPEED = "low_battery_tiga_speed_percent"

        /** 跟随系统电池图标色（改动前的默认行为） */
        const val MODE_FOLLOW_SYSTEM = 0

        /** 固定单色，取 [customColor] */
        const val MODE_FIXED_COLOR = 1

        /** 按电池状态五路取色，取 [stateColors] */
        const val MODE_BATTERY_STATE = 2

        /** 按电量区间取色，取 [levelRanges] */
        const val MODE_LEVEL_RANGE = 3

        // 滑杆范围
        const val STROKE_MIN = 1f
        const val STROKE_MAX = 8f
        // 贴孔间隙范围：负值 = 环压进安全区（贴近物理孔）
        const val GAP_MIN = -6f
        const val GAP_MAX = 6f
        const val OFFSET_MIN = -20f
        const val OFFSET_MAX = 20f
        const val SCALE_MIN = 0.7f
        const val SCALE_MAX = 1.5f

        // 防烧屏亮度上限滑杆范围
        const val BRIGHTNESS_MIN = 30
        const val BRIGHTNESS_MAX = 100

        // 电量数字显示时长（ms）
        const val TAP_DURATION_MIN_MS = 1000
        const val TAP_DURATION_MAX_MS = 6000

        // 动画速度滑杆范围（百分数）：下限放到 10%，让呼吸/闪烁能真正「慢下来」
        const val SPEED_MIN = 10
        const val SPEED_MAX = 300

        // 发光强度滑杆范围（百分数，0 = 完全不发光）
        const val GLOW_MIN = 0
        const val GLOW_MAX = 200

        // 充电动画 / 提醒闪烁「幅度」滑杆范围（百分数，100 = 基准）
        const val STRENGTH_MIN = 0
        const val STRENGTH_MAX = 200

        // 充电动画覆盖范围（互斥）
        /** 智能：低于阈值只跑空白段，达到阈值整圈流动 */
        const val CHARGING_ANIM_SMART = 0
        /** 一直整圈流动 */
        const val CHARGING_ANIM_FULL = 1
        /** 一直只在未充空白段流动 */
        const val CHARGING_ANIM_GAP = 2

        // 智能模式的全环切换阈值（百分数）
        const val THRESHOLD_MIN = 5
        const val THRESHOLD_MAX = 95

        // 提醒进场提示时长（秒）
        const val INTRO_SECONDS_MIN = 1
        const val INTRO_SECONDS_MAX = 5

        // 底槽浓度（百分比，0 = 隐藏底槽）
        const val TRACK_OPACITY_MIN = 0
        const val TRACK_OPACITY_MAX = 100

        // 音乐律动幅度（百分数）；v1.3.3 上限 200 → 300
        const val MUSIC_STRENGTH_MIN = 0
        const val MUSIC_STRENGTH_MAX = 300

        // 音乐律动「闪动范围」（百分数）：0 = 几乎不摆，越大明暗反差越大；v1.3.3 上限 200 → 300
        const val MUSIC_FLASH_MIN = 0
        const val MUSIC_FLASH_MAX = 300

        // 「节拍闪动」的节拍速度（BPM）
        // v1.0.0：默认由固定 120 降为 72；v1.2.0：下限 40 → 24、默认 72 → 56
        //（用户反馈「拉到最低还是太快，不够优雅」）
        const val MUSIC_BEAT_BPM_MIN = 24
        const val MUSIC_BEAT_BPM_MAX = 140

        // 充电动画形式（互斥）
        /** 经典环形进度弧：只按电量填充，不做动效 */
        const val CHARGING_STYLE_PROGRESS = 0
        /** 光点绕圈：单亮点带短拖尾顺时针绕行（魅族环形呼吸灯观感） */
        const val CHARGING_STYLE_DOT = 1
        /** 双光点追逐：两个亮点在对面同步绕行 */
        const val CHARGING_STYLE_DUAL = 2
        /** 彗尾流水：长拖尾连续流动，像加载动画 */
        const val CHARGING_STYLE_COMET = 3
        /** 整环脉冲：整圈一起明暗心跳式脉动（无光点流动） */
        const val CHARGING_STYLE_PULSE = 4
        /** 能量扩散：一圈柔光由内向外扩散、放大淡出，像在「充能」 */
        const val CHARGING_STYLE_ECHO = 5
        /** 呼吸光点：光点绕行同时环整体做呼吸，动静结合 */
        const val CHARGING_STYLE_BREATH_DOT = 6

        // 呼吸灯形式（互斥）
        /** 光晕呼吸：环外圈叠加一圈柔光做明暗脉动 */
        const val BREATHING_STYLE_GLOW = 0
        /** 亮度脉动：环整体明暗呼吸，不加光晕 */
        const val BREATHING_STYLE_BRIGHTNESS = 1
        /** 跑马光点：一段柔光沿环缓慢绕行 */
        const val BREATHING_STYLE_RUNNER = 2

        // 提醒闪烁形式（互斥）
        /** 交替闪烁：主色/副色交替做呼吸式闪烁 */
        const val BLINK_STYLE_ALTERNATE = 0
        /** 呼吸脉冲：主色明暗脉冲（副色不用） */
        const val BLINK_STYLE_PULSE = 1
        /** 跑马警示：主色光点沿环绕行警示 */
        const val BLINK_STYLE_RUNNER = 2

        // 音乐律动形式（互斥；呼吸灯观感，克制不抢戏）
        /** 呼吸：环整体明暗缓慢起伏 + 颜色慢慢变 */
        const val MUSIC_STYLE_BREATH = 0
        /** 跑马灯：一段柔光沿环缓慢绕行 + 颜色慢慢变 */
        const val MUSIC_STYLE_RUNNER = 1
        /** 节拍闪动：按可调节拍（默认 56 BPM）柔和亮闪，配合音乐像在跟拍 */
        const val MUSIC_STYLE_BEAT = 2
        /**
         * 心跳（v1.2.0 新增）：一周期两下「扑通」——先一下强的、紧接一下弱的，
         * 再长间歇，像心跳节律，比匀速闪动更有生命感。
         */
        const val MUSIC_STYLE_HEARTBEAT = 3
        /**
         * 波浪（v1.2.0 新增）：两段宽柔光沿环对向缓慢推进、交叠处自然消长，
         * 像水面起伏，观感最舒缓、最不抢戏。
         */
        const val MUSIC_STYLE_WAVE = 4
        /**
         * 音量脉冲（v1.3.0 新增）：亮度直接跟随**实时音量**起伏；
         * 需开「音频驱动」，未开时退化为呼吸。声音越大环越亮，最「跟声音」。
         */
        const val MUSIC_STYLE_VOLUME = 5
        /**
         * 人声/音乐（v1.3.0 新增）：识别到人声时偏向暖色呼吸、识别到音乐时偏向
         * 冷色脉动，动静分明；需开「音频驱动」，未开时退化为呼吸。
         */
        const val MUSIC_STYLE_VOICE = 6

        // 音频驱动「反应源」（互斥）
        /** 人声与音乐都反应 */
        const val MUSIC_REACT_ALL = 0
        /** 只随音乐反应 */
        const val MUSIC_REACT_MUSIC = 1
        /** 只随人声反应 */
        const val MUSIC_REACT_VOICE = 2

        // 音频驱动灵敏度（百分数）；v1.3.3：上限 300 → 500
        const val AUDIO_SENS_MIN = 30
        const val AUDIO_SENS_MAX = 500

        // 新增充电动画形式（v1.3.0）
        /** 双彗尾：两条长拖尾在对面反向流动 */
        const val CHARGING_STYLE_DOUBLE_COMET = 7
        /** 脉冲扫掠：一段亮弧绕行，同时整环做轻微脉冲 */
        const val CHARGING_STYLE_PULSE_SWEEP = 8

        // 新增呼吸灯形式（v1.3.0）
        /** 双段柔光：两段柔光在对面同步呼吸 */
        const val BREATHING_STYLE_DOUBLE_GLOW = 3
        /** 呼吸跑马：一段柔光绕行，同时环整体明暗呼吸 */
        const val BREATHING_STYLE_BREATH_RUNNER = 4

        // 新增提醒闪烁形式（v1.3.0）
        /** 双闪：一周期快速闪两下（一强一弱） */
        const val BLINK_STYLE_DOUBLE_FLASH = 3
        /** 涟漪警示：一圈警示光由内向外扩散淡出 */
        const val BLINK_STYLE_RIPPLE = 4

        // 音乐律动周期范围（秒）
        // v1.3.3：下限 2 → 1、上限 16 → 20，档位更密、方便微调（用户反馈）
        const val MUSIC_CYCLE_MIN = 1
        const val MUSIC_CYCLE_MAX = 20

        // 迪迦计时器特效的触发电量范围（百分比）
        // v1.0.0：下限由 5 放到 1，用户可以设「只剩 1~2% 才告警」
        const val TIGA_THRESHOLD_MIN = 1
        const val TIGA_THRESHOLD_MAX = 50

        // 提醒持续方式（互斥）
        /** 常驻：有未读通知就一直淡淡呼吸 */
        const val BLINK_NOTIF_MODE_ALWAYS = 0
        /** 限时：通知到达后只播报一段时间 */
        const val BLINK_NOTIF_MODE_TIMED = 1

        // 限时模式的时长范围（秒）
        const val NOTIF_DURATION_MIN = 3
        const val NOTIF_DURATION_MAX = 120

        val DEFAULT = RingConfig()
    }
}
