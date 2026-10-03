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
        const val OFFSET_MIN = -20f
        const val OFFSET_MAX = 20f
        const val SCALE_MIN = 0.7f
        const val SCALE_MAX = 1.5f

        val DEFAULT = RingConfig()
    }
}
