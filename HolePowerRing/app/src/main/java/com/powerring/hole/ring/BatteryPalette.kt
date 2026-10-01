package com.powerring.hole.ring

/**
 * 环形电量使用的电池调色板。
 *
 * 所有颜色都来自系统状态栏原生电池图标（MiuiBatteryMeterIconView），
 * 由 [com.powerring.hole.hook.BatteryColorHook] 反射读出后写入 [RingState]。
 *
 * [ready] 为 false 表示还没读到系统颜色（Hook 没装上 / 机型类名不同 /
 * 图标 View 尚未创建），此时 [RingRenderer] 回退到 [MiuixPalette] 的固定语义色，
 * 行为与改造前完全一致。
 */
data class BatteryPalette(
    /** 是否已成功读到系统电池图标颜色 */
    val ready: Boolean = false,
    /** 普通态目标色（已按 darkIntensity 在浅色/深色前景之间插值） */
    val normal: Int = 0,
    /** 低电量色 */
    val low: Int = 0,
    /** 省电模式色 */
    val powerSave: Int = 0,
    /** 性能模式色 */
    val performance: Int = 0,
    /** 充电中色 */
    val charging: Int = 0,
    /** 当前是否处于充电态（含快充） */
    val chargingNow: Boolean = false,
    /** 当前是否处于性能模式 */
    val performanceNow: Boolean = false,
    /** 当前是否处于省电模式 */
    val powerSaveNow: Boolean = false,
    /** 当前是否为低电量 */
    val lowNow: Boolean = false,
    /** 状态栏反色强度 0..1，仅用于日志与诊断 */
    val darkIntensity: Float = 1f,
) {
    companion object {
        val EMPTY = BatteryPalette()
    }
}

/**
 * Hook 侧从系统电池图标读到的原始值。
 *
 * [light] / [dark] 是系统自己的浅色 / 深色两套前景色；
 * [darkIntensity] 是状态栏反色动画的进度（0 = 纯浅色，1 = 纯深色），
 * 它逐帧连续推进，是让环色与状态栏共用同一条时间轴的关键。
 */
data class SystemBatteryColors(
    /** 浅色前景（浅底深图标） */
    val light: Int = 0,
    /** 深色前景（深底浅图标） */
    val dark: Int = 0,
    /** tint 区域色，useTint 为真时优先于 light/dark */
    val tint: Int = 0,
    val useTint: Boolean = false,
    /** 低电量色 */
    val low: Int = 0,
    /** 省电模式色 */
    val powerSave: Int = 0,
    /** 性能模式色 */
    val performance: Int = 0,
    /** 充电中色 */
    val charging: Int = 0,
    val chargingNow: Boolean = false,
    val performanceNow: Boolean = false,
    val powerSaveNow: Boolean = false,
    val lowNow: Boolean = false,
    val darkIntensity: Float = 1f,
)
