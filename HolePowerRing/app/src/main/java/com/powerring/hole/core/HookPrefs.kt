package com.powerring.hole.core

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import com.powerring.hole.ring.RingConfig
import com.powerring.hole.ring.RingState

/**
 * SystemUI 进程内的模块配置缓存。
 *
 * 配置经 ContentProvider（com.powerring.hole.config）跨进程获取：
 * HyperOS 上 SystemUI 是普通应用，读其他应用私有文件被 SELinux 拒绝
 * （XSharedPreferences 的 relabel 通道在本机不可用）。
 *
 * 线程模型：get() 只读 volatile 缓存（绘制线程安全，绝不做同步 Binder）；
 * 刷新在专用 HandlerThread 上执行。启动时与收到 CONFIG_CHANGED 广播时各刷一次，
 * 失败保留旧缓存并回退 DEFAULT（首次），绝不抛异常。
 */
object HookPrefs {

    private val CONFIG_URI: Uri = Uri.parse("content://$AUTHORITY/$PATH_CONFIG")

    /** 由 RingState.attachContext 注入的宿主 Context（查询 provider 用）。 */
    @Volatile
    var hostContext: Context? = null

    @Volatile
    private var cached: RingConfig = RingConfig.DEFAULT

    private val workerThread: HandlerThread by lazy {
        HandlerThread("HookPrefs").also { it.start() }
    }

    private val handler: Handler by lazy { Handler(workerThread.looper) }

    /** 返回当前缓存（provider 尚未加载到时为 DEFAULT）。 */
    fun get(): RingConfig = cached

    /** 触发一次后台刷新（广播/初始化用）。 */
    fun invalidate() {
        refresh()
    }

    /** 在专用线程后台查询 provider；调度失败也不抛出。 */
    fun refresh() {
        try {
            handler.post {
                try {
                    load()
                } catch (t: Throwable) {
                    ModuleLog.e("配置刷新失败，保留旧缓存", t)
                }
            }
        } catch (t: Throwable) {
            ModuleLog.e("配置刷新调度失败", t)
        }
    }

    private fun load() {
        val ctx = hostContext ?: return
        val values = HashMap<String, Any>()
        var cursor: Cursor? = null
        try {
            cursor = ctx.contentResolver.query(CONFIG_URI, null, null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                for (i in 0 until cursor.columnCount) {
                    val v = readColumn(cursor, i) ?: continue
                    values[cursor.getColumnName(i)] = v
                }
            }
        } catch (t: Throwable) {
            ModuleLog.e("查询配置 provider 失败，保留旧缓存", t)
            return
        } finally {
            try {
                cursor?.close()
            } catch (t: Throwable) {
                ModuleLog.e("关闭配置游标失败", t)
            }
        }
        if (values.isEmpty()) {
            ModuleLog.e("配置 provider 返回空，保留旧缓存")
            return
        }
        val d = RingConfig.DEFAULT
        cached = RingConfig(
            ringEnabled = toBool(values[RingConfig.KEY_RING_ENABLED], d.ringEnabled),
            hideBattery = toBool(values[RingConfig.KEY_HIDE_BATTERY], d.hideBattery),
            levelAnim = toBool(values[RingConfig.KEY_LEVEL_ANIM], d.levelAnim),
            chargingGlow = toBool(values[RingConfig.KEY_CHARGING_GLOW], d.chargingGlow),
            collapseOnImmersive = toBool(
                values[RingConfig.KEY_COLLAPSE_ON_IMMERSIVE], d.collapseOnImmersive,
            ),
            collapseOnIsland = toBool(values[RingConfig.KEY_COLLAPSE_ON_ISLAND], d.collapseOnIsland),
            strokeWidthDp = toFloat(values[RingConfig.KEY_STROKE_WIDTH], d.strokeWidthDp),
            offsetXDp = toFloat(values[RingConfig.KEY_OFFSET_X], d.offsetXDp),
            offsetYDp = toFloat(values[RingConfig.KEY_OFFSET_Y], d.offsetYDp),
            scale = toFloat(values[RingConfig.KEY_SCALE], d.scale),
            useCustomColor = toBool(values[RingConfig.KEY_USE_CUSTOM_COLOR], d.useCustomColor),
            customColor = toInt(values[RingConfig.KEY_CUSTOM_COLOR], d.customColor),
        )
        ModuleLog.i("配置已重新加载: $cached")
        RingState.invalidateAll()
    }

    private fun readColumn(cursor: Cursor, index: Int): Any? {
        if (cursor.isNull(index)) return null
        return when (cursor.getType(index)) {
            Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
            Cursor.FIELD_TYPE_FLOAT -> cursor.getFloat(index)
            else -> cursor.getString(index)
        }
    }

    // provider 的布尔列经 Binder 游标窗口落成整型 0/1；String 分支作降级兼容。
    private fun toBool(v: Any?, d: Boolean): Boolean = when (v) {
        is Number -> v.toInt() != 0
        is String -> v.toBooleanStrictOrNull() ?: (v != "0")
        else -> d
    }

    private fun toFloat(v: Any?, d: Float): Float = (v as? Number)?.toFloat() ?: d

    private fun toInt(v: Any?, d: Int): Int = (v as? Number)?.toInt() ?: d

    private const val AUTHORITY = "com.powerring.hole.config"
    private const val PATH_CONFIG = "config"
}
