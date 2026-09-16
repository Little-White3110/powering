package com.powerring.hole.ui

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import com.powerring.hole.data.BatteryObserver
import com.powerring.hole.ring.RingConfig
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
            levelAnim = p.getBoolean(RingConfig.KEY_LEVEL_ANIM, true),
            chargingGlow = p.getBoolean(RingConfig.KEY_CHARGING_GLOW, true),
            strokeWidthDp = p.getFloat(RingConfig.KEY_STROKE_WIDTH, 2.5f),
            offsetXDp = p.getFloat(RingConfig.KEY_OFFSET_X, 0f),
            offsetYDp = p.getFloat(RingConfig.KEY_OFFSET_Y, 0f),
            scale = p.getFloat(RingConfig.KEY_SCALE, 1f),
            useCustomColor = p.getBoolean(RingConfig.KEY_USE_CUSTOM_COLOR, false),
            customColor = p.getInt(RingConfig.KEY_CUSTOM_COLOR, 0xFF277AF7.toInt()),
        )
    }

    fun setBoolean(context: Context, key: String, value: Boolean) {
        prefs(context).edit().putBoolean(key, value).apply()
        commit(context)
    }

    fun setFloat(context: Context, key: String, value: Float) {
        prefs(context).edit().putFloat(key, value).apply()
        commit(context)
    }

    fun setInt(context: Context, key: String, value: Int) {
        prefs(context).edit().putInt(key, value).apply()
        commit(context)
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
