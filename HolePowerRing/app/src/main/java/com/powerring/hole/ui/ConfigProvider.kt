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
        RingConfig.KEY_LEVEL_ANIM -> prefs.getBoolean(key, RingConfig.DEFAULT.levelAnim)
        RingConfig.KEY_CHARGING_GLOW -> prefs.getBoolean(key, RingConfig.DEFAULT.chargingGlow)
        RingConfig.KEY_USE_CUSTOM_COLOR -> prefs.getBoolean(key, RingConfig.DEFAULT.useCustomColor)
        RingConfig.KEY_STROKE_WIDTH -> prefs.getFloat(key, RingConfig.DEFAULT.strokeWidthDp)
        RingConfig.KEY_OFFSET_X -> prefs.getFloat(key, RingConfig.DEFAULT.offsetXDp)
        RingConfig.KEY_OFFSET_Y -> prefs.getFloat(key, RingConfig.DEFAULT.offsetYDp)
        RingConfig.KEY_SCALE -> prefs.getFloat(key, RingConfig.DEFAULT.scale)
        RingConfig.KEY_CUSTOM_COLOR -> prefs.getInt(key, RingConfig.DEFAULT.customColor)
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
            RingConfig.KEY_LEVEL_ANIM,
            RingConfig.KEY_CHARGING_GLOW,
            RingConfig.KEY_STROKE_WIDTH,
            RingConfig.KEY_OFFSET_X,
            RingConfig.KEY_OFFSET_Y,
            RingConfig.KEY_SCALE,
            RingConfig.KEY_USE_CUSTOM_COLOR,
            RingConfig.KEY_CUSTOM_COLOR,
        )
    }
}
