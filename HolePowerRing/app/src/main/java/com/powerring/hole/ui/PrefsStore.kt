package com.powerring.hole.ui

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import com.powerring.hole.data.BatteryObserver
import com.powerring.hole.ring.CustomColors
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
