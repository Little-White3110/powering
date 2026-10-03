package com.powerring.hole.data

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 「音频驱动律动」的采集服务（v1.3.0）。
 *
 * 为什么放在**模块应用进程**而不是 SystemUI：模块的 Hook 跑在 SystemUI 进程里，
 * 用的是 SystemUI 的身份，拿不到 RECORD_AUDIO；而模块 APK 本身是一个普通应用，
 * 用户在设置页授权后就能正常录音。于是这里用麦克风算出「实时音量 + 内容类型」，
 * 再经 [NotifStateBridge.publishAudioLevel] 广播给 SystemUI 的 RingState。
 *
 * 采集参数：16 kHz 单声道 PCM16，每 40ms 一块；按整块算 RMS → 归一化到 0–1；
 * 用一个 ~1 秒的滑动窗做人声/音乐的粗分类（说话停停顿顿、音乐相对连续）。
 * 发送按 [SEND_INTERVAL_MS] 节流，且每段内取**峰值**发送，保住节拍瞬态。
 *
 * 隐私与省电：仅在本服务被用户显式开启时采集（前台服务 + 常驻通知明示）；
 * 连续静音超过 [SILENCE_PAUSE_MS] 暂停录音，之后靠 isMusicActive 轮询决定是否恢复。
 * 停止开关或退出时立即释放麦克风。
 */
class AudioReactiveService : Service() {

    private var worker: HandlerThread? = null
    private var handler: Handler? = null

    @Volatile
    private var running = false

    private var record: AudioRecord? = null

    /** 最近一次发送时刻，用于节流 */
    private var lastSendMs = 0L

    /** 两次发送之间记录到的峰值音量（保住节拍瞬态） */
    private var pendingPeak = 0f

    /** 最近一次分类结果 */
    private var lastKind = KIND_UNKNOWN

    /** 连续静音计时 */
    private var silentSinceMs = 0L

    /** 是否因长时间静音而暂停录音 */
    private var paused = false

    /** 一秒钟窗内的 RMS 采样（25 块 × 40ms） */
    private val window = FloatArray(WINDOW_SIZE)
    private var windowFill = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!hasRecordPermission()) {
            Log.i(TAG, "无录音权限，音频驱动服务自停")
            stopSelf()
            return
        }
        startForegroundCompat()
        running = true
        val t = HandlerThread("AudioReactive").also { it.start() }
        worker = t
        handler = Handler(t.looper)
        handler?.post(captureLoop)
        Log.i(TAG, "音频驱动服务已启动")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        handler?.removeCallbacksAndMessages(null)
        releaseRecord()
        worker?.quitSafely()
        Log.i(TAG, "音频驱动服务已停止")
        super.onDestroy()
    }

    // ---- 采集循环 ----

    private val captureLoop = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                tick()
            } catch (t: Throwable) {
                Log.e(TAG, "音频采集异常，稍后重试", t)
                releaseRecord()
            }
            if (running) handler?.postDelayed(this, CHUNK_MS)
        }
    }

    private fun tick() {
        val now = android.os.SystemClock.uptimeMillis()

        // 长时间静音：暂停录音，改由 isMusicActive 轮询决定是否恢复，省电
        if (paused) {
            if (isAnyAudioActive()) {
                paused = false
                silentSinceMs = 0L
                ensureRecord()
                Log.i(TAG, "检测到音频播放，恢复录音")
            } else {
                // 暂停期间也偶尔回一个「静音」给 SystemUI，避免旧值残留
                if (now - lastSendMs >= 1000L) {
                    lastSendMs = now
                    send(0f, KIND_UNKNOWN)
                }
                return
            }
        }

        val rec = ensureRecord() ?: return
        val buf = ShortArray(CHUNK_SAMPLES)
        val n = rec.read(buf, 0, buf.size)
        if (n <= 0) return

        var sum = 0.0
        for (i in 0 until n) {
            val v = buf[i].toDouble()
            sum += v * v
        }
        val rms = sqrt(sum / n).toFloat() / 32768f
        val level = normalize(rms)

        // 峰值保留，供本次发送使用
        if (level > pendingPeak) pendingPeak = level

        // 滑动窗（分类用）
        window[windowFill % WINDOW_SIZE] = rms
        windowFill++

        // 静音计时
        if (rms < SILENCE_FLOOR) {
            if (silentSinceMs == 0L) silentSinceMs = now
        } else {
            silentSinceMs = 0L
        }
        if (silentSinceMs != 0L && now - silentSinceMs > SILENCE_PAUSE_MS) {
            paused = true
            releaseRecord()
            Log.i(TAG, "连续静音，暂停录音")
            return
        }

        if (now - lastSendMs >= SEND_INTERVAL_MS) {
            lastSendMs = now
            if (windowFill >= MIN_CLASSIFY) lastKind = classify()
            send(pendingPeak, lastKind)
            pendingPeak = 0f
        }
    }

    private fun send(level: Float, kind: Int) {
        runCatching { NotifStateBridge.publishAudioLevel(this, level, kind) }
    }

    // ---- 音量归一化与人声/音乐分类 ----

    /** RMS → 0–1：按 -60..0 dB 线性映射，再做轻微曲线提亮弱音。 */
    private fun normalize(rms: Float): Float {
        if (rms <= 1e-5f) return 0f
        val db = 20f * log10(rms)
        val t = ((db + 60f) / 60f).coerceIn(0f, 1f)
        return sqrt(t) // 提亮中低段，观感更跟手
    }

    /**
     * 粗分类：说话「停停顿顿」（静音段比例高 + 幅度起伏大），音乐通常更连续。
     * 只是启发式，不保证准确；用于「人声/音乐」律动形式的取向切换。
     */
    private fun classify(): Int {
        val n = WINDOW_SIZE
        var silent = 0
        var flux = 0f
        var prev = window[0]
        var maxV = 0f
        for (i in 0 until n) {
            val v = window[i]
            if (v < SILENCE_FLOOR) silent++
            if (i > 0) flux += abs(v - prev)
            prev = v
            if (v > maxV) maxV = v
        }
        val silenceRatio = silent.toFloat() / n
        val fluxAvg = if (n > 1) flux / (n - 1) else 0f
        return when {
            silenceRatio >= 0.30f && fluxAvg > maxV * 0.10f -> KIND_VOICE
            silenceRatio <= 0.12f -> KIND_MUSIC
            else -> KIND_UNKNOWN
        }
    }

    // ---- AudioRecord 生命周期 ----

    private fun ensureRecord(): AudioRecord? {
        record?.let { if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) return it }
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufSize = max(minBuf, CHUNK_SAMPLES * 2 * 2)
        val r = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.DEFAULT,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize,
            )
        }.getOrNull() ?: return null
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { r.release() }
            return null
        }
        return runCatching {
            r.startRecording()
            record = r
            r
        }.getOrNull()
    }

    private fun releaseRecord() {
        val r = record ?: return
        record = null
        runCatching { if (r.recordingState == AudioRecord.RECORDSTATE_RECORDING) r.stop() }
        runCatching { r.release() }
    }

    private fun isAnyAudioActive(): Boolean = runCatching {
        val am = getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
        am?.isMusicActive == true
    }.getOrDefault(false)

    private fun hasRecordPermission(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    // ---- 前台服务通知 ----

    private fun startForegroundCompat() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(CHANNEL_ID, "音频律动", NotificationManager.IMPORTANCE_MIN)
        ch.setShowBadge(false)
        ch.enableVibration(false)
        nm.createNotificationChannel(ch)
        val notif = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("环形电量 · 音频律动")
            .setContentText("正在采集音量，让圆环随声音律动")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }

    companion object {
        private const val TAG = "HolePowerRing"
        private const val CHANNEL_ID = "hole_power_ring_audio"
        private const val NOTIF_ID = 0x4850

        private const val SAMPLE_RATE = 16000
        private const val CHUNK_SAMPLES = 640          // 40ms @16kHz
        private const val CHUNK_MS = 40L
        private const val WINDOW_SIZE = 25             // ~1s
        private const val MIN_CLASSIFY = 12
        private const val SEND_INTERVAL_MS = 100L
        private const val SILENCE_PAUSE_MS = 45_000L
        private const val SILENCE_FLOOR = 0.004f

        /** 内容类型：0 未知/静音、1 音乐、2 人声（与 RingState.audioKind 对齐） */
        const val KIND_UNKNOWN = 0
        const val KIND_MUSIC = 1
        const val KIND_VOICE = 2

        fun start(ctx: Context) {
            runCatching {
                ctx.startForegroundService(Intent(ctx, AudioReactiveService::class.java))
            }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, AudioReactiveService::class.java)) }
        }
    }
}
