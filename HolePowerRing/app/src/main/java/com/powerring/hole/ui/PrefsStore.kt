package com.powerring.hole.ui

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import com.powerring.hole.data.BatteryObserver
import com.powerring.hole.ring.CustomColors
import com.powerring.hole.ring.PercentNodes
import com.powerring.hole.ring.RingConfig
import com.powerring.hole.ring.StateColors
import java.io.File

/**
 * 模块配置页侧的偏好读写。
 *
 * Hook 侧（SystemUI 进程，system 身份）需要直接读取该 XML，
 * 因此写入后将文件与父级目录设置为其他用户可读/可遍历；
 * 同时向 SystemUI 发显式广播触发即时重绘。
 */
object PrefsStore {

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(RingConfig.PREFS_NAME, Context.MODE_PRIVATE)

    fun load(context: Context): RingConfig {
        val p = prefs(context)
        return RingConfig(
            ringEnabled = p.getBoolean(RingConfig.KEY_RING_ENABLED, true),
            hideBattery = p.getBoolean(RingConfig.KEY_HIDE_BATTERY, true),
            collapseOnImmersive = p.getBoolean(RingConfig.KEY_COLLAPSE_ON_IMMERSIVE, true),
            collapseOnIsland = p.getBoolean(RingConfig.KEY_COLLAPSE_ON_ISLAND, false),
            hideOnScreenshot = p.getBoolean(RingConfig.KEY_HIDE_ON_SCREENSHOT, true),
            restoreBatteryOnLandscape = p.getBoolean(
                RingConfig.KEY_RESTORE_BATTERY_ON_LANDSCAPE, true,
            ),
            strokeWidthDp = p.getFloat(RingConfig.KEY_STROKE_WIDTH, 2.5f),
            trackOpacityPercent = p.getInt(RingConfig.KEY_TRACK_OPACITY, 100),
            gapDp = p.getFloat(RingConfig.KEY_GAP_DP, 0.8f),
            offsetXDp = p.getFloat(RingConfig.KEY_OFFSET_X, 0f),
            offsetYDp = p.getFloat(RingConfig.KEY_OFFSET_Y, 0f),
            scale = p.getFloat(RingConfig.KEY_SCALE, 1f),
            useCustomColor = p.getBoolean(RingConfig.KEY_USE_CUSTOM_COLOR, false),
            customColor = p.getInt(RingConfig.KEY_CUSTOM_COLOR, 0xFF277AF7.toInt()),
            colorMode = resolveColorMode(p),
            stateColors = StateColors(
                normal = p.getInt(RingConfig.KEY_STATE_COLOR_NORMAL, 0),
                low = p.getInt(RingConfig.KEY_STATE_COLOR_LOW, 0),
                powerSave = p.getInt(RingConfig.KEY_STATE_COLOR_POWER_SAVE, 0),
                performance = p.getInt(RingConfig.KEY_STATE_COLOR_PERFORMANCE, 0),
                charging = p.getInt(RingConfig.KEY_STATE_COLOR_CHARGING, 0),
            ),
            levelRanges = CustomColors.decodeRanges(
                p.getString(RingConfig.KEY_LEVEL_RANGES, ""),
            ),
            breathingEnabled = p.getBoolean(RingConfig.KEY_BREATHING_ENABLED, true),
            breathingOnIdle = p.getBoolean(RingConfig.KEY_BREATHING_ON_IDLE, true),
            blinkOnNotification = p.getBoolean(RingConfig.KEY_BLINK_ON_NOTIFICATION, true),
            blinkColorAlert = p.getInt(RingConfig.KEY_BLINK_COLOR_ALERT, 0xFFFF3B30.toInt()),
            blinkColorAlt = p.getInt(RingConfig.KEY_BLINK_COLOR_ALT, 0xFFFF9F0A.toInt()),
            tapShowPercent = p.getBoolean(RingConfig.KEY_TAP_SHOW_PERCENT, true),
            tapPercentDurationMs = p.getInt(RingConfig.KEY_TAP_PERCENT_DURATION, 2600),
            burnInProtection = p.getBoolean(RingConfig.KEY_BURN_IN_PROTECTION, true),
            maxBrightnessPercent = p.getInt(RingConfig.KEY_MAX_BRIGHTNESS_PERCENT, 85),
            glowStrengthPercent = p.getInt(RingConfig.KEY_GLOW_STRENGTH, 100),
            chargingStyle = p.getInt(
                RingConfig.KEY_CHARGING_STYLE, RingConfig.CHARGING_STYLE_DOT,
            ),
            chargingColor = p.getInt(RingConfig.KEY_CHARGING_COLOR, 0),
            chargingSpeedPercent = p.getInt(RingConfig.KEY_CHARGING_SPEED, 100),
            chargingStrengthPercent = p.getInt(
                RingConfig.KEY_CHARGING_STRENGTH, RingConfig.DEFAULT.chargingStrengthPercent,
            ),
            breathingStyle = p.getInt(
                RingConfig.KEY_BREATHING_STYLE, RingConfig.BREATHING_STYLE_GLOW,
            ),
            breathingColor = p.getInt(RingConfig.KEY_BREATHING_COLOR, 0),
            breathingSpeedPercent = p.getInt(RingConfig.KEY_BREATHING_SPEED, 100),
            blinkStyle = p.getInt(RingConfig.KEY_BLINK_STYLE, RingConfig.BLINK_STYLE_ALTERNATE),
            blinkSpeedPercent = p.getInt(RingConfig.KEY_BLINK_SPEED, 100),
            blinkStrengthPercent = p.getInt(
                RingConfig.KEY_BLINK_STRENGTH, RingConfig.DEFAULT.blinkStrengthPercent,
            ),
            blinkIntroEnabled = p.getBoolean(RingConfig.KEY_BLINK_INTRO_ENABLED, true),
            blinkIntroSeconds = p.getInt(RingConfig.KEY_BLINK_INTRO_SECONDS, 2),
            blinkOnScreen = p.getBoolean(RingConfig.KEY_BLINK_ON_SCREEN, true),
            blinkNotifMode = p.getInt(
                RingConfig.KEY_BLINK_NOTIF_MODE, RingConfig.BLINK_NOTIF_MODE_ALWAYS,
            ),
            blinkNotifDurationSeconds = p.getInt(RingConfig.KEY_BLINK_NOTIF_DURATION, 12),
            hideRingOnLockScreen = p.getBoolean(
                RingConfig.KEY_HIDE_RING_ON_LOCK_SCREEN, RingConfig.DEFAULT.hideRingOnLockScreen,
            ),
            chargingAnimMode = p.getInt(
                RingConfig.KEY_CHARGING_ANIM_MODE, RingConfig.CHARGING_ANIM_SMART,
            ),
            chargingFullRingThreshold = p.getInt(RingConfig.KEY_CHARGING_FULL_THRESHOLD, 60),
            percentNodesEnabled = p.getBoolean(RingConfig.KEY_PERCENT_NODES_ENABLED, true),
            percentNodes = PercentNodes.decode(
                if (p.contains(RingConfig.KEY_PERCENT_NODES)) {
                    p.getString(RingConfig.KEY_PERCENT_NODES, "")
                } else {
                    null
                },
            ),
            musicPulseEnabled = p.getBoolean(RingConfig.KEY_MUSIC_PULSE_ENABLED, true),
            musicPulseStyle = p.getInt(
                RingConfig.KEY_MUSIC_PULSE_STYLE, RingConfig.MUSIC_STYLE_BREATH,
            ),
            musicCycleSeconds = p.getInt(RingConfig.KEY_MUSIC_CYCLE_SECONDS, 6),
            musicColorCycleEnabled = p.getBoolean(RingConfig.KEY_MUSIC_COLOR_CYCLE, true),
            musicColor = p.getInt(RingConfig.KEY_MUSIC_COLOR, 0),
            musicPulseStrengthPercent = p.getInt(RingConfig.KEY_MUSIC_PULSE_STRENGTH, 100),
            musicFlashRangePercent = p.getInt(
                RingConfig.KEY_MUSIC_FLASH_RANGE, RingConfig.DEFAULT.musicFlashRangePercent,
            ),
            musicBeatBpm = p.getInt(
                RingConfig.KEY_MUSIC_BEAT_BPM, RingConfig.DEFAULT.musicBeatBpm,
            ),
            musicAudioReactive = p.getBoolean(RingConfig.KEY_MUSIC_AUDIO_REACTIVE, false),
            musicReactSource = p.getInt(
                RingConfig.KEY_MUSIC_REACT_SOURCE, RingConfig.MUSIC_REACT_ALL,
            ),
            musicAudioSensitivityPercent = p.getInt(
                RingConfig.KEY_MUSIC_AUDIO_SENS, RingConfig.DEFAULT.musicAudioSensitivityPercent,
            ),
            hideInShade = p.getBoolean(RingConfig.KEY_HIDE_IN_SHADE, true),
            hideInControlCenter = p.getBoolean(RingConfig.KEY_HIDE_IN_CONTROL_CENTER, true),
            lowBatteryTigaEnabled = p.getBoolean(RingConfig.KEY_LOW_BATTERY_TIGA, false),
            lowBatteryTigaThreshold = p.getInt(RingConfig.KEY_LOW_BATTERY_TIGA_THRESHOLD, 20),
            lowBatteryTigaSpeedPercent = p.getInt(RingConfig.KEY_LOW_BATTERY_TIGA_SPEED, 100),
        )
    }

    fun setBoolean(context: Context, key: String, value: Boolean) {
        prefs(context).edit().putBoolean(key, value).commit()
        commit(context)
    }

    fun setFloat(context: Context, key: String, value: Float) {
        prefs(context).edit().putFloat(key, value).commit()
        commit(context)
    }

    fun setInt(context: Context, key: String, value: Int) {
        prefs(context).edit().putInt(key, value).commit()
        commit(context)
    }

    fun setString(context: Context, key: String, value: String) {
        prefs(context).edit().putString(key, value).commit()
        commit(context)
    }

    /**
     * 导入外观配置用：把外观页全部设置项一次写入并提交，
     * 只触发一次权限放开与一次 CONFIG_CHANGED 广播。
     */
    fun applyAppearance(context: Context, config: RingConfig) {
        prefs(context).edit()
            .putFloat(RingConfig.KEY_STROKE_WIDTH, config.strokeWidthDp)
            .putFloat(RingConfig.KEY_SCALE, config.scale)
            .putFloat(RingConfig.KEY_OFFSET_X, config.offsetXDp)
            .putFloat(RingConfig.KEY_OFFSET_Y, config.offsetYDp)
            .putInt(RingConfig.KEY_COLOR_MODE, config.colorMode)
            .putInt(RingConfig.KEY_CUSTOM_COLOR, config.customColor)
            .putInt(RingConfig.KEY_STATE_COLOR_NORMAL, config.stateColors.normal)
            .putInt(RingConfig.KEY_STATE_COLOR_LOW, config.stateColors.low)
            .putInt(RingConfig.KEY_STATE_COLOR_POWER_SAVE, config.stateColors.powerSave)
            .putInt(RingConfig.KEY_STATE_COLOR_PERFORMANCE, config.stateColors.performance)
            .putInt(RingConfig.KEY_STATE_COLOR_CHARGING, config.stateColors.charging)
            .putString(RingConfig.KEY_LEVEL_RANGES, CustomColors.encodeRanges(config.levelRanges))
            .commit()
        commit(context)
    }

    /**
     * 有效配色模式。`color_mode` 是本版本新增的 key，老用户 XML 里只有
     * `use_custom_color`；未显式设置过模式时按旧值推导，这样模块升级后
     * **即使从不打开设置页**也不会把「固定单色」静默重置成「跟随系统」。
     */
    fun resolveColorMode(prefs: SharedPreferences): Int = when {
        prefs.contains(RingConfig.KEY_COLOR_MODE) ->
            prefs.getInt(RingConfig.KEY_COLOR_MODE, RingConfig.DEFAULT.colorMode)
        prefs.getBoolean(RingConfig.KEY_USE_CUSTOM_COLOR, false) -> RingConfig.MODE_FIXED_COLOR
        else -> RingConfig.MODE_FOLLOW_SYSTEM
    }

    private fun commit(context: Context) {
        makeWorldReadable(context)
        notifyHookSide(context)
    }

    /** 通知 SystemUI 进程内的模块立即重新读取配置并重绘。 */
    private fun notifyHookSide(context: Context) {
        runCatching {
            val intent = Intent(BatteryObserver.ACTION_CONFIG_CHANGED).apply {
                setPackage("com.android.systemui")
            }
            context.sendBroadcast(intent)
        }
    }

    /**
     * 经典 XSharedPreferences 方案：放开 prefs 文件及目录权限。
     * LSPosed 环境下 system 身份据此跨进程读取。
     */
    private fun makeWorldReadable(context: Context) {
        runCatching {
            val base = File(context.applicationInfo.dataDir)
            val sharedPrefs = File(base, "shared_prefs")
            val prefsFile = File(sharedPrefs, "${RingConfig.PREFS_NAME}.xml")
            base.setExecutable(true, false)
            sharedPrefs.setExecutable(true, false)
            prefsFile.setReadable(true, false)
        }
    }
}
