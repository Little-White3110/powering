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
) {
    companion object {
        const val PREFS_NAME = "hole_power_ring_prefs"

        const val KEY_RING_ENABLED = "ring_enabled"
        const val KEY_HIDE_BATTERY = "hide_battery"
        const val KEY_LEVEL_ANIM = "level_anim"
        const val KEY_CHARGING_GLOW = "charging_glow"
        const val KEY_STROKE_WIDTH = "stroke_width_dp"

        val DEFAULT = RingConfig()
    }
}
