package com.powerring.hole.core

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import com.powerring.hole.ring.CustomColors
import com.powerring.hole.ring.PercentNodes
import com.powerring.hole.ring.RingConfig
import com.powerring.hole.ring.RingState
import com.powerring.hole.ring.StateColors

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

    /** 是否已成功读到过一次配置（决定解锁后是否需要补读） */
    @Volatile
    private var loadedOnce = false

    /** 启动期重试进度，成功即归零 */
    private var retryIndex = 0

    /**
     * 启动期读取失败的退避表（毫秒）。
     *
     * 覆盖两种情形：provider 冷启动偏慢；以及冷启动后要等用户解锁
     * （配置在 CE 存储里，解锁前查不到）。累计约 3.5 分钟，之后放弃重试
     * 并沿用当前缓存——等待解锁这件事另有 ACTION_USER_PRESENT 补读兜底。
     */
    private val RETRY_DELAYS_MS = longArrayOf(1_000L, 3_000L, 8_000L, 20_000L, 60_000L, 120_000L)

    private val workerThread: HandlerThread by lazy {
        HandlerThread("HookPrefs").also { it.start() }
    }

    private val handler: Handler by lazy { Handler(workerThread.looper) }

    /** 返回当前缓存（provider 尚未加载到时为 DEFAULT）。 */
    fun get(): RingConfig = cached

    /**
     * 是否还没成功读到过配置。
     *
     * 给外部补读用：冷启动时 SystemUI 先于用户解锁起来，而配置存在本模块的
     * 凭据加密（CE）存储里，那一刻 provider 查不到；解锁（ACTION_USER_PRESENT）
     * 后必须补读一次。
     */
    fun needsRetry(): Boolean = !loadedOnce

    /** 触发一次后台刷新（广播/初始化用）。 */
    fun invalidate() {
        refresh()
    }

    /** 在专用线程后台查询 provider；调度失败也不抛出。 */
    fun refresh() {
        try {
            handler.post { loadWithRetry() }
        } catch (t: Throwable) {
            ModuleLog.e("配置刷新调度失败", t)
        }
    }

    /**
     * 读取配置；失败按退避表重试有限次。
     *
     * 为什么必须重试：启动期 provider 可能还没起来（冷启动），或用户尚未解锁
     * （配置在 CE 存储里）。原先失败即"保留旧缓存"——而首次的旧缓存就是 DEFAULT——
     * 于是表现为「重启后读不到调整过的设置，必须在设置页开关一次才恢复」：
     * 开关会发 CONFIG_CHANGED 广播，才触发第二次读取。
     */
    private fun loadWithRetry() {
        val ok = try {
            load()
        } catch (t: Throwable) {
            ModuleLog.e("配置刷新失败，保留旧缓存", t)
            false
        }
        if (ok) {
            retryIndex = 0
            return
        }
        if (retryIndex >= RETRY_DELAYS_MS.size) {
            ModuleLog.e("配置读取连续失败 ${retryIndex} 次，放弃重试（沿用当前缓存）", null)
            return
        }
        val delay = RETRY_DELAYS_MS[retryIndex]
        retryIndex++
        ModuleLog.i("配置读取失败，${delay}ms 后重试（第 ${retryIndex} 次）")
        handler.postDelayed({ loadWithRetry() }, delay)
    }

    /** @return 是否成功读到配置 */
    private fun load(): Boolean {
        val ctx = hostContext ?: return false
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
            return false
        } finally {
            try {
                cursor?.close()
            } catch (t: Throwable) {
                ModuleLog.e("关闭配置游标失败", t)
            }
        }
        if (values.isEmpty()) {
            ModuleLog.e("配置 provider 返回空，保留旧缓存")
            return false
        }
        val d = RingConfig.DEFAULT
        cached = RingConfig(
            ringEnabled = toBool(values[RingConfig.KEY_RING_ENABLED], d.ringEnabled),
            hideBattery = toBool(values[RingConfig.KEY_HIDE_BATTERY], d.hideBattery),
            collapseOnImmersive = toBool(
                values[RingConfig.KEY_COLLAPSE_ON_IMMERSIVE], d.collapseOnImmersive,
            ),
            collapseOnIsland = toBool(values[RingConfig.KEY_COLLAPSE_ON_ISLAND], d.collapseOnIsland),
            collapseOnShade = toBool(values[RingConfig.KEY_COLLAPSE_ON_SHADE], d.collapseOnShade),
            hideOnScreenshot = toBool(
                values[RingConfig.KEY_HIDE_ON_SCREENSHOT], d.hideOnScreenshot,
            ),
            restoreBatteryOnLandscape = toBool(
                values[RingConfig.KEY_RESTORE_BATTERY_ON_LANDSCAPE], d.restoreBatteryOnLandscape,
            ),
            strokeWidthDp = toFloat(values[RingConfig.KEY_STROKE_WIDTH], d.strokeWidthDp),
            trackOpacityPercent = toInt(
                values[RingConfig.KEY_TRACK_OPACITY], d.trackOpacityPercent,
            ),
            gapDp = toFloat(values[RingConfig.KEY_GAP_DP], d.gapDp),
            offsetXDp = toFloat(values[RingConfig.KEY_OFFSET_X], d.offsetXDp),
            offsetYDp = toFloat(values[RingConfig.KEY_OFFSET_Y], d.offsetYDp),
            scale = toFloat(values[RingConfig.KEY_SCALE], d.scale),
            useCustomColor = toBool(values[RingConfig.KEY_USE_CUSTOM_COLOR], d.useCustomColor),
            customColor = toInt(values[RingConfig.KEY_CUSTOM_COLOR], d.customColor),
            colorMode = toInt(values[RingConfig.KEY_COLOR_MODE], d.colorMode),
            stateColors = StateColors(
                normal = toInt(values[RingConfig.KEY_STATE_COLOR_NORMAL], 0),
                low = toInt(values[RingConfig.KEY_STATE_COLOR_LOW], 0),
                powerSave = toInt(values[RingConfig.KEY_STATE_COLOR_POWER_SAVE], 0),
                performance = toInt(values[RingConfig.KEY_STATE_COLOR_PERFORMANCE], 0),
                charging = toInt(values[RingConfig.KEY_STATE_COLOR_CHARGING], 0),
            ),
            // 区间表是一条字符串；解析函数内部已对坏数据逐段降级，不会抛
            levelRanges = CustomColors.decodeRanges(
                values[RingConfig.KEY_LEVEL_RANGES] as? String,
            ),
            breathingEnabled = toBool(values[RingConfig.KEY_BREATHING_ENABLED], d.breathingEnabled),
            breathingOnIdle = toBool(values[RingConfig.KEY_BREATHING_ON_IDLE], d.breathingOnIdle),
            blinkOnNotification = toBool(
                values[RingConfig.KEY_BLINK_ON_NOTIFICATION], d.blinkOnNotification,
            ),
            blinkColorAlert = toInt(values[RingConfig.KEY_BLINK_COLOR_ALERT], d.blinkColorAlert),
            blinkColorAlt = toInt(values[RingConfig.KEY_BLINK_COLOR_ALT], d.blinkColorAlt),
            tapShowPercent = toBool(values[RingConfig.KEY_TAP_SHOW_PERCENT], d.tapShowPercent),
            tapPercentDurationMs = toInt(
                values[RingConfig.KEY_TAP_PERCENT_DURATION], d.tapPercentDurationMs,
            ),
            burnInProtection = toBool(values[RingConfig.KEY_BURN_IN_PROTECTION], d.burnInProtection),
            maxBrightnessPercent = toInt(
                values[RingConfig.KEY_MAX_BRIGHTNESS_PERCENT], d.maxBrightnessPercent,
            ),
            glowStrengthPercent = toInt(
                values[RingConfig.KEY_GLOW_STRENGTH], d.glowStrengthPercent,
            ),
            chargingStyle = toInt(values[RingConfig.KEY_CHARGING_STYLE], d.chargingStyle),
            chargingColor = toInt(values[RingConfig.KEY_CHARGING_COLOR], d.chargingColor),
            chargingSpeedPercent = toInt(
                values[RingConfig.KEY_CHARGING_SPEED], d.chargingSpeedPercent,
            ),
            chargingStrengthPercent = toInt(
                values[RingConfig.KEY_CHARGING_STRENGTH], d.chargingStrengthPercent,
            ),
            chargingAnimMode = toInt(
                values[RingConfig.KEY_CHARGING_ANIM_MODE], d.chargingAnimMode,
            ),
            chargingFullRingThreshold = toInt(
                values[RingConfig.KEY_CHARGING_FULL_THRESHOLD], d.chargingFullRingThreshold,
            ),
            breathingStyle = toInt(values[RingConfig.KEY_BREATHING_STYLE], d.breathingStyle),
            breathingColor = toInt(values[RingConfig.KEY_BREATHING_COLOR], d.breathingColor),
            breathingSpeedPercent = toInt(
                values[RingConfig.KEY_BREATHING_SPEED], d.breathingSpeedPercent,
            ),
            blinkStyle = toInt(values[RingConfig.KEY_BLINK_STYLE], d.blinkStyle),
            blinkSpeedPercent = toInt(values[RingConfig.KEY_BLINK_SPEED], d.blinkSpeedPercent),
            blinkStrengthPercent = toInt(
                values[RingConfig.KEY_BLINK_STRENGTH], d.blinkStrengthPercent,
            ),
            blinkIntroEnabled = toBool(
                values[RingConfig.KEY_BLINK_INTRO_ENABLED], d.blinkIntroEnabled,
            ),
            blinkIntroSeconds = toInt(
                values[RingConfig.KEY_BLINK_INTRO_SECONDS], d.blinkIntroSeconds,
            ),
            blinkOnScreen = toBool(values[RingConfig.KEY_BLINK_ON_SCREEN], d.blinkOnScreen),
            blinkNotifMode = toInt(
                values[RingConfig.KEY_BLINK_NOTIF_MODE], d.blinkNotifMode,
            ),
            blinkNotifDurationSeconds = toInt(
                values[RingConfig.KEY_BLINK_NOTIF_DURATION], d.blinkNotifDurationSeconds,
            ),
            hideRingOnLockScreen = toBool(
                values[RingConfig.KEY_HIDE_RING_ON_LOCK_SCREEN], d.hideRingOnLockScreen,
            ),
            percentNodesEnabled = toBool(
                values[RingConfig.KEY_PERCENT_NODES_ENABLED], d.percentNodesEnabled,
            ),
            // 节点表是一条字符串；解析函数内部对坏数据逐段降级，不会抛
            percentNodes = PercentNodes.decode(
                values[RingConfig.KEY_PERCENT_NODES] as? String,
            ),
            musicPulseEnabled = toBool(
                values[RingConfig.KEY_MUSIC_PULSE_ENABLED], d.musicPulseEnabled,
            ),
            musicPulseStyle = toInt(values[RingConfig.KEY_MUSIC_PULSE_STYLE], d.musicPulseStyle),
            musicCycleSeconds = toInt(
                values[RingConfig.KEY_MUSIC_CYCLE_SECONDS], d.musicCycleSeconds,
            ),
            musicColorCycleEnabled = toBool(
                values[RingConfig.KEY_MUSIC_COLOR_CYCLE], d.musicColorCycleEnabled,
            ),
            musicColor = toInt(values[RingConfig.KEY_MUSIC_COLOR], d.musicColor),
            musicPulseStrengthPercent = toInt(
                values[RingConfig.KEY_MUSIC_PULSE_STRENGTH], d.musicPulseStrengthPercent,
            ),
            musicFlashRangePercent = toInt(
                values[RingConfig.KEY_MUSIC_FLASH_RANGE], d.musicFlashRangePercent,
            ),
            musicBeatBpm = toInt(values[RingConfig.KEY_MUSIC_BEAT_BPM], d.musicBeatBpm),
            musicAudioReactive = toBool(
                values[RingConfig.KEY_MUSIC_AUDIO_REACTIVE], d.musicAudioReactive,
            ),
            musicReactSource = toInt(
                values[RingConfig.KEY_MUSIC_REACT_SOURCE], d.musicReactSource,
            ),
            musicAudioSensitivityPercent = toInt(
                values[RingConfig.KEY_MUSIC_AUDIO_SENS], d.musicAudioSensitivityPercent,
            ),
            hideInShade = toBool(values[RingConfig.KEY_HIDE_IN_SHADE], d.hideInShade),
            hideInControlCenter = toBool(
                values[RingConfig.KEY_HIDE_IN_CONTROL_CENTER], d.hideInControlCenter,
            ),
            lowBatteryTigaEnabled = toBool(
                values[RingConfig.KEY_LOW_BATTERY_TIGA], d.lowBatteryTigaEnabled,
            ),
            lowBatteryTigaThreshold = toInt(
                values[RingConfig.KEY_LOW_BATTERY_TIGA_THRESHOLD], d.lowBatteryTigaThreshold,
            ),
            lowBatteryTigaSpeedPercent = toInt(
                values[RingConfig.KEY_LOW_BATTERY_TIGA_SPEED], d.lowBatteryTigaSpeedPercent,
            ),
        )
        ModuleLog.i("配置已重新加载: $cached")
        loadedOnce = true
        RingState.invalidateAll()
        return true
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
