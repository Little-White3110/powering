package com.powerring.hole.ui

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import com.powerring.hole.core.ModuleLog
import com.powerring.hole.ring.PercentNodes
import com.powerring.hole.ring.RingConfig

/**
 * 模块配置的跨进程只读出口。
 *
 * HyperOS 上 SystemUI 以普通应用身份运行，读其他应用私有文件被 SELinux
 * 拒绝（XSharedPreferences 的 relabel 通道在本机不可用），因此配置走标准
 * Binder IPC：Hook 侧用 ContentResolver.query 取全部键值。
 *
 * 列名即 RingConfig 的 KEY_* 常量；布尔列经 MatrixCursor 会落成 INTEGER(0/1)，
 * 由 Hook 侧还原。默认值一律取自 RingConfig.DEFAULT，避免两处硬编码漂移。
 */
class ConfigProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val ctx = context ?: return MatrixCursor(emptyArray())
        val prefs = ctx.getSharedPreferences(RingConfig.PREFS_NAME, Context.MODE_PRIVATE)
        val keys = projection?.takeIf { it.isNotEmpty() } ?: ALL_KEYS
        val cursor = MatrixCursor(keys)
        try {
            cursor.addRow(keys.map { key -> valueOf(prefs, key) }.toTypedArray())
        } catch (t: Throwable) {
            ModuleLog.e("ConfigProvider 查询失败", t)
        }
        return cursor
    }

    private fun valueOf(prefs: SharedPreferences, key: String): Any? = when (key) {
        RingConfig.KEY_RING_ENABLED -> prefs.getBoolean(key, RingConfig.DEFAULT.ringEnabled)
        RingConfig.KEY_HIDE_BATTERY -> prefs.getBoolean(key, RingConfig.DEFAULT.hideBattery)
        RingConfig.KEY_COLLAPSE_ON_IMMERSIVE ->
            prefs.getBoolean(key, RingConfig.DEFAULT.collapseOnImmersive)
        RingConfig.KEY_COLLAPSE_ON_ISLAND ->
            prefs.getBoolean(key, RingConfig.DEFAULT.collapseOnIsland)
        RingConfig.KEY_HIDE_ON_SCREENSHOT ->
            prefs.getBoolean(key, RingConfig.DEFAULT.hideOnScreenshot)
        RingConfig.KEY_RESTORE_BATTERY_ON_LANDSCAPE ->
            prefs.getBoolean(key, RingConfig.DEFAULT.restoreBatteryOnLandscape)
        RingConfig.KEY_USE_CUSTOM_COLOR -> prefs.getBoolean(key, RingConfig.DEFAULT.useCustomColor)
        RingConfig.KEY_STROKE_WIDTH -> prefs.getFloat(key, RingConfig.DEFAULT.strokeWidthDp)
        RingConfig.KEY_TRACK_OPACITY -> prefs.getInt(key, RingConfig.DEFAULT.trackOpacityPercent)
        RingConfig.KEY_GAP_DP -> prefs.getFloat(key, RingConfig.DEFAULT.gapDp)
        RingConfig.KEY_OFFSET_X -> prefs.getFloat(key, RingConfig.DEFAULT.offsetXDp)
        RingConfig.KEY_OFFSET_Y -> prefs.getFloat(key, RingConfig.DEFAULT.offsetYDp)
        RingConfig.KEY_SCALE -> prefs.getFloat(key, RingConfig.DEFAULT.scale)
        RingConfig.KEY_CUSTOM_COLOR -> prefs.getInt(key, RingConfig.DEFAULT.customColor)
        RingConfig.KEY_COLOR_MODE -> PrefsStore.resolveColorMode(prefs)
        RingConfig.KEY_STATE_COLOR_NORMAL -> prefs.getInt(key, 0)
        RingConfig.KEY_STATE_COLOR_LOW -> prefs.getInt(key, 0)
        RingConfig.KEY_STATE_COLOR_POWER_SAVE -> prefs.getInt(key, 0)
        RingConfig.KEY_STATE_COLOR_PERFORMANCE -> prefs.getInt(key, 0)
        RingConfig.KEY_STATE_COLOR_CHARGING -> prefs.getInt(key, 0)
        RingConfig.KEY_LEVEL_RANGES -> prefs.getString(key, "")
        RingConfig.KEY_BREATHING_ENABLED -> prefs.getBoolean(key, RingConfig.DEFAULT.breathingEnabled)
        RingConfig.KEY_BREATHING_ON_IDLE -> prefs.getBoolean(key, RingConfig.DEFAULT.breathingOnIdle)
        RingConfig.KEY_BLINK_ON_NOTIFICATION ->
            prefs.getBoolean(key, RingConfig.DEFAULT.blinkOnNotification)
        RingConfig.KEY_BLINK_COLOR_ALERT -> prefs.getInt(key, RingConfig.DEFAULT.blinkColorAlert)
        RingConfig.KEY_BLINK_COLOR_ALT -> prefs.getInt(key, RingConfig.DEFAULT.blinkColorAlt)
        RingConfig.KEY_TAP_SHOW_PERCENT -> prefs.getBoolean(key, RingConfig.DEFAULT.tapShowPercent)
        RingConfig.KEY_TAP_PERCENT_DURATION ->
            prefs.getInt(key, RingConfig.DEFAULT.tapPercentDurationMs)
        RingConfig.KEY_BURN_IN_PROTECTION ->
            prefs.getBoolean(key, RingConfig.DEFAULT.burnInProtection)
        RingConfig.KEY_MAX_BRIGHTNESS_PERCENT ->
            prefs.getInt(key, RingConfig.DEFAULT.maxBrightnessPercent)
        RingConfig.KEY_GLOW_STRENGTH -> prefs.getInt(key, RingConfig.DEFAULT.glowStrengthPercent)
        RingConfig.KEY_CHARGING_STYLE -> prefs.getInt(key, RingConfig.DEFAULT.chargingStyle)
        RingConfig.KEY_CHARGING_COLOR -> prefs.getInt(key, RingConfig.DEFAULT.chargingColor)
        RingConfig.KEY_CHARGING_SPEED -> prefs.getInt(key, RingConfig.DEFAULT.chargingSpeedPercent)
        RingConfig.KEY_CHARGING_STRENGTH ->
            prefs.getInt(key, RingConfig.DEFAULT.chargingStrengthPercent)
        RingConfig.KEY_BREATHING_STYLE -> prefs.getInt(key, RingConfig.DEFAULT.breathingStyle)
        RingConfig.KEY_BREATHING_COLOR -> prefs.getInt(key, RingConfig.DEFAULT.breathingColor)
        RingConfig.KEY_BREATHING_SPEED -> prefs.getInt(key, RingConfig.DEFAULT.breathingSpeedPercent)
        RingConfig.KEY_BLINK_STYLE -> prefs.getInt(key, RingConfig.DEFAULT.blinkStyle)
        RingConfig.KEY_BLINK_SPEED -> prefs.getInt(key, RingConfig.DEFAULT.blinkSpeedPercent)
        RingConfig.KEY_BLINK_STRENGTH ->
            prefs.getInt(key, RingConfig.DEFAULT.blinkStrengthPercent)
        RingConfig.KEY_BLINK_INTRO_ENABLED ->
            prefs.getBoolean(key, RingConfig.DEFAULT.blinkIntroEnabled)
        RingConfig.KEY_BLINK_INTRO_SECONDS ->
            prefs.getInt(key, RingConfig.DEFAULT.blinkIntroSeconds)
        RingConfig.KEY_CHARGING_ANIM_MODE ->
            prefs.getInt(key, RingConfig.DEFAULT.chargingAnimMode)
        RingConfig.KEY_CHARGING_FULL_THRESHOLD ->
            prefs.getInt(key, RingConfig.DEFAULT.chargingFullRingThreshold)
        RingConfig.KEY_PERCENT_NODES_ENABLED ->
            prefs.getBoolean(key, RingConfig.DEFAULT.percentNodesEnabled)
        RingConfig.KEY_PERCENT_NODES ->
            prefs.getString(key, PercentNodes.encode(RingConfig.DEFAULT.percentNodes))
        RingConfig.KEY_MUSIC_PULSE_ENABLED ->
            prefs.getBoolean(key, RingConfig.DEFAULT.musicPulseEnabled)
        RingConfig.KEY_MUSIC_PULSE_STYLE -> prefs.getInt(key, RingConfig.DEFAULT.musicPulseStyle)
        RingConfig.KEY_MUSIC_CYCLE_SECONDS ->
            prefs.getInt(key, RingConfig.DEFAULT.musicCycleSeconds)
        RingConfig.KEY_MUSIC_COLOR_CYCLE ->
            prefs.getBoolean(key, RingConfig.DEFAULT.musicColorCycleEnabled)
        RingConfig.KEY_MUSIC_COLOR -> prefs.getInt(key, RingConfig.DEFAULT.musicColor)
        RingConfig.KEY_MUSIC_PULSE_STRENGTH ->
            prefs.getInt(key, RingConfig.DEFAULT.musicPulseStrengthPercent)
        RingConfig.KEY_MUSIC_FLASH_RANGE ->
            prefs.getInt(key, RingConfig.DEFAULT.musicFlashRangePercent)
        RingConfig.KEY_MUSIC_BEAT_BPM ->
            prefs.getInt(key, RingConfig.DEFAULT.musicBeatBpm)
        RingConfig.KEY_HIDE_IN_SHADE -> prefs.getBoolean(key, RingConfig.DEFAULT.hideInShade)
        RingConfig.KEY_HIDE_IN_CONTROL_CENTER ->
            prefs.getBoolean(key, RingConfig.DEFAULT.hideInControlCenter)
        RingConfig.KEY_LOW_BATTERY_TIGA ->
            prefs.getBoolean(key, RingConfig.DEFAULT.lowBatteryTigaEnabled)
        RingConfig.KEY_LOW_BATTERY_TIGA_THRESHOLD ->
            prefs.getInt(key, RingConfig.DEFAULT.lowBatteryTigaThreshold)
        RingConfig.KEY_LOW_BATTERY_TIGA_SPEED ->
            prefs.getInt(key, RingConfig.DEFAULT.lowBatteryTigaSpeedPercent)
        RingConfig.KEY_BLINK_ON_SCREEN ->
            prefs.getBoolean(key, RingConfig.DEFAULT.blinkOnScreen)
        RingConfig.KEY_BLINK_NOTIF_MODE ->
            prefs.getInt(key, RingConfig.DEFAULT.blinkNotifMode)
        RingConfig.KEY_BLINK_NOTIF_DURATION ->
            prefs.getInt(key, RingConfig.DEFAULT.blinkNotifDurationSeconds)
        RingConfig.KEY_HIDE_RING_ON_LOCK_SCREEN ->
            prefs.getBoolean(key, RingConfig.DEFAULT.hideRingOnLockScreen)
        RingConfig.KEY_MUSIC_AUDIO_REACTIVE ->
            prefs.getBoolean(key, RingConfig.DEFAULT.musicAudioReactive)
        RingConfig.KEY_MUSIC_REACT_SOURCE ->
            prefs.getInt(key, RingConfig.DEFAULT.musicReactSource)
        RingConfig.KEY_MUSIC_AUDIO_SENS ->
            prefs.getInt(key, RingConfig.DEFAULT.musicAudioSensitivityPercent)
        else -> null
    }

    // 本项目不需要写接口
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun getType(uri: Uri): String? = null

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle = Bundle.EMPTY

    companion object {
        const val AUTHORITY = "com.powerring.hole.config"
        const val PATH_CONFIG = "config"

        val ALL_KEYS = arrayOf(
            RingConfig.KEY_RING_ENABLED,
            RingConfig.KEY_HIDE_BATTERY,
            RingConfig.KEY_COLLAPSE_ON_IMMERSIVE,
            RingConfig.KEY_COLLAPSE_ON_ISLAND,
            RingConfig.KEY_HIDE_ON_SCREENSHOT,
            RingConfig.KEY_RESTORE_BATTERY_ON_LANDSCAPE,
            RingConfig.KEY_STROKE_WIDTH,
            RingConfig.KEY_TRACK_OPACITY,
            RingConfig.KEY_GAP_DP,
            RingConfig.KEY_OFFSET_X,
            RingConfig.KEY_OFFSET_Y,
            RingConfig.KEY_SCALE,
            RingConfig.KEY_USE_CUSTOM_COLOR,
            RingConfig.KEY_CUSTOM_COLOR,
            RingConfig.KEY_COLOR_MODE,
            RingConfig.KEY_STATE_COLOR_NORMAL,
            RingConfig.KEY_STATE_COLOR_LOW,
            RingConfig.KEY_STATE_COLOR_POWER_SAVE,
            RingConfig.KEY_STATE_COLOR_PERFORMANCE,
            RingConfig.KEY_STATE_COLOR_CHARGING,
            RingConfig.KEY_LEVEL_RANGES,
            RingConfig.KEY_BREATHING_ENABLED,
            RingConfig.KEY_BREATHING_ON_IDLE,
            RingConfig.KEY_BLINK_ON_NOTIFICATION,
            RingConfig.KEY_BLINK_COLOR_ALERT,
            RingConfig.KEY_BLINK_COLOR_ALT,
            RingConfig.KEY_TAP_SHOW_PERCENT,
            RingConfig.KEY_TAP_PERCENT_DURATION,
            RingConfig.KEY_BURN_IN_PROTECTION,
            RingConfig.KEY_MAX_BRIGHTNESS_PERCENT,
            RingConfig.KEY_GLOW_STRENGTH,
            RingConfig.KEY_CHARGING_STYLE,
            RingConfig.KEY_CHARGING_COLOR,
            RingConfig.KEY_CHARGING_SPEED,
            RingConfig.KEY_CHARGING_STRENGTH,
            RingConfig.KEY_BREATHING_STYLE,
            RingConfig.KEY_BREATHING_COLOR,
            RingConfig.KEY_BREATHING_SPEED,
            RingConfig.KEY_BLINK_STYLE,
            RingConfig.KEY_BLINK_SPEED,
            RingConfig.KEY_BLINK_STRENGTH,
            RingConfig.KEY_BLINK_INTRO_ENABLED,
            RingConfig.KEY_BLINK_INTRO_SECONDS,
            RingConfig.KEY_CHARGING_ANIM_MODE,
            RingConfig.KEY_CHARGING_FULL_THRESHOLD,
            RingConfig.KEY_PERCENT_NODES_ENABLED,
            RingConfig.KEY_PERCENT_NODES,
            RingConfig.KEY_MUSIC_PULSE_ENABLED,
            RingConfig.KEY_MUSIC_PULSE_STYLE,
            RingConfig.KEY_MUSIC_CYCLE_SECONDS,
            RingConfig.KEY_MUSIC_COLOR_CYCLE,
            RingConfig.KEY_MUSIC_COLOR,
            RingConfig.KEY_MUSIC_PULSE_STRENGTH,
            RingConfig.KEY_MUSIC_FLASH_RANGE,
            RingConfig.KEY_MUSIC_BEAT_BPM,
            RingConfig.KEY_HIDE_IN_SHADE,
            RingConfig.KEY_HIDE_IN_CONTROL_CENTER,
            RingConfig.KEY_LOW_BATTERY_TIGA,
            RingConfig.KEY_LOW_BATTERY_TIGA_THRESHOLD,
            RingConfig.KEY_LOW_BATTERY_TIGA_SPEED,
            RingConfig.KEY_BLINK_ON_SCREEN,
            RingConfig.KEY_BLINK_NOTIF_MODE,
            RingConfig.KEY_BLINK_NOTIF_DURATION,
            RingConfig.KEY_HIDE_RING_ON_LOCK_SCREEN,
            RingConfig.KEY_MUSIC_AUDIO_REACTIVE,
            RingConfig.KEY_MUSIC_REACT_SOURCE,
            RingConfig.KEY_MUSIC_AUDIO_SENS,
        )
    }
}
