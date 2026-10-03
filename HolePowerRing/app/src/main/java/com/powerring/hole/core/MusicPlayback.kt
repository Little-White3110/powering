package com.powerring.hole.core

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration

/**
 * 「是否真的在放音乐」判定 —— SystemUI 侧（环的律动）与设置页（音乐检测状态行）
 * 共用同一口径，避免两处判断不一致。
 *
 * 只认「用途 = 媒体 且 内容类型 = 音乐」：视频（CONTENT_TYPE_MOVIE）和游戏
 * （USAGE_GAME）都会被排除，这是系统层面唯一能区分「音乐」的标记。
 *
 * 关键坑：`AudioManager.getActivePlaybackConfigurations()` 里 AOSP 对
 * “active” 的定义是播放器处于 STARTED **或 PAUSED**。暂停后 AudioTrack 不释放，
 * 配置项会一直留在列表里 —— 只看这个列表就会出现「音乐明明停了，环还在律动」。
 * 而公开 stub 里的 [AudioPlaybackConfiguration] 只有 getAudioAttributes /
 * getAudioDeviceInfo，没有 isActive() / getPlayerState()，所以这里用反射读真实
 * 播放状态，只认 STARTED。
 *
 * 反射拿不到时（宿主 app 被 hidden-api 限制、或 ROM 阉割）退回 `isMusicActive()`：
 * 它反映的是「音乐流此刻是否真的在出声」，暂停同样不算，比只信配置列表安全。
 */
object MusicPlayback {

    /** PLAYER_STATE_STARTED 的兜底常量值（AOSP: IDLE=0, STOPPED=1, STARTED=2, PAUSED=3）。 */
    private const val PLAYER_STATE_STARTED_FALLBACK = 2

    private val playerStateMethod: java.lang.reflect.Method? = runCatching {
        AudioPlaybackConfiguration::class.java.getMethod("getPlayerState")
    }.getOrNull()

    private val startedState: Int = runCatching {
        AudioPlaybackConfiguration::class.java.getField("PLAYER_STATE_STARTED").getInt(null)
    }.getOrDefault(PLAYER_STATE_STARTED_FALLBACK)

    /**
     * 判定依据，供设置页「音乐检测」状态行显示（纯诊断，不参与逻辑）。
     *
     * 排查「没放音乐环却在闪」时先看这一行：显示「精确」说明读到了播放器的
     * 真实状态（问题在别处，例如某个媒体 App 真的把播放器置于 STARTED）；
     * 显示「音频流」说明反射不可用、退化成了 `isMusicActive()`，此时最容易
     * 被 ROM 的假阳性坑到。
     */
    enum class Source { NONE, PLAYER_STATE, AUDIO_STREAM }

    @Volatile
    var lastSource: Source = Source.NONE
        private set

    fun isPlaying(context: Context): Boolean {
        val am = runCatching {
            context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        }.getOrNull()
        if (am == null) {
            lastSource = Source.NONE
            return false
        }

        val configs = runCatching { am.activePlaybackConfigurations }.getOrNull()
        if (configs == null) {
            lastSource = Source.AUDIO_STREAM
            return runCatching { am.isMusicActive }.getOrDefault(false)
        }

        val musicConfigs = configs.filter { cfg ->
            runCatching {
                val aa = cfg.audioAttributes
                aa.usage == AudioAttributes.USAGE_MEDIA &&
                    aa.contentType == AudioAttributes.CONTENT_TYPE_MUSIC
            }.getOrDefault(false)
        }
        if (musicConfigs.isEmpty()) {
            lastSource = Source.PLAYER_STATE
            return false
        }

        // 精确判据：必须真的有播放器处于 STARTED（正在出声）
        val states = musicConfigs.mapNotNull { playerStateOf(it) }
        if (states.isNotEmpty()) {
            lastSource = Source.PLAYER_STATE
            return states.any { it == startedState }
        }

        lastSource = Source.AUDIO_STREAM
        return runCatching { am.isMusicActive }.getOrDefault(false)
    }

    private fun playerStateOf(cfg: AudioPlaybackConfiguration): Int? =
        playerStateMethod?.let { m ->
            runCatching { (m.invoke(cfg) as? Number)?.toInt() }.getOrNull()
        }
}
