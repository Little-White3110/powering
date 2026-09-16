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
    /** 电量变化时环弧平滑动画 */
    val levelAnim: Boolean = true,
    /** 充电时高亮光效 */
    val chargingGlow: Boolean = true,
    /** 环描边宽度（dp） */
    val strokeWidthDp: Float = 2.5f,
    /** 水平偏移（dp），正值向右 */
    val offsetXDp: Float = 0f,
    /** 垂直偏移（dp），正值向下 */
    val offsetYDp: Float = 0f,
    /** 环半径缩放，1.0 为默认，用于精确贴合挖孔 */
    val scale: Float = 1f,
    /** 启用自定义颜色（启用后进度弧固定使用 [customColor]） */
    val useCustomColor: Boolean = false,
    /** 自定义颜色（ARGB） */
    val customColor: Int = 0xFF277AF7.toInt(),
) {
    companion object {
        const val PREFS_NAME = "hole_power_ring_prefs"

        const val KEY_RING_ENABLED = "ring_enabled"
        const val KEY_HIDE_BATTERY = "hide_battery"
        const val KEY_LEVEL_ANIM = "level_anim"
        const val KEY_CHARGING_GLOW = "charging_glow"
        const val KEY_STROKE_WIDTH = "stroke_width_dp"
        const val KEY_OFFSET_X = "offset_x_dp"
        const val KEY_OFFSET_Y = "offset_y_dp"
        const val KEY_SCALE = "ring_scale"
        const val KEY_USE_CUSTOM_COLOR = "use_custom_color"
        const val KEY_CUSTOM_COLOR = "custom_color_argb"

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
