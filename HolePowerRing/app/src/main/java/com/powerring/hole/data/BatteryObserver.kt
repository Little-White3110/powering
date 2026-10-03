package com.powerring.hole.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.powerring.hole.core.HookPrefs
import com.powerring.hole.core.ModuleLog
import com.powerring.hole.core.MusicPlayback
import com.powerring.hole.ring.RingState

/**
 * 电池/省电/屏幕状态数据源。
 *
 * 不依赖 MIUI 混淆类，直接使用系统广播：
 * - ACTION_BATTERY_CHANGED（sticky，注册即立刻收到当前值）
 * - ACTION_POWER_SAVE_MODE_CHANGED
 * - ACTION_SCREEN_ON / ACTION_SCREEN_OFF（息屏/AOD 不画环，防烧屏且省电）
 */
object BatteryObserver {

    /** 配置页保存后发出的显式广播，通知 SystemUI 侧刷新环 */
    const val ACTION_CONFIG_CHANGED = "com.powerring.hole.CONFIG_CHANGED"

    /** 音乐播放状态轮询间隔（ms）：足够跟手，又不会造成可感知的功耗 */
    private const val MUSIC_POLL_MS = 1200L

    /** 需要连续多少次相同采样才改变音乐状态（去抖，见 [musicPollRunnable]） */
    private const val MUSIC_CONFIRM_HITS = 2

    /**
     * 解锁完成后的复核时刻（ms）。
     *
     * 锁屏→桌面的过渡里，状态栏窗口状态 / 面板展开状态都可能在
     * `ACTION_USER_PRESENT` 之后才落地，各自带一个（可能是假的）取值。
     * 这里在解锁后再按实时值复核若干次，把迟到的假值放掉；探针自身的轮询
     * 也会持续对齐，两者互为兜底。
     */
    private val UNLOCK_RECHECK_MS = longArrayOf(400L, 1_200L, 2_400L, 4_000L)

    /**
     * 解锁后的「收起沉降窗口」（ms）。
     *
     * 这段窗口内 [RingState.collapseTarget] 一律返回 0：解锁动画本身会连着发出
     * 好几个过渡态的窗口/面板回调，若照单全收，环会先被收起来、再等 1–3 秒
     * 才被探针纠回 —— 用户看到的就是「进桌面半天没环」。窗口很短（1.6s），
     * 用户在解锁后 1.6 秒内不可能真的去拉控制中心或看全屏视频，因此安全。
     */
    private const val UNLOCK_SETTLE_MS = 1_600L

    private var started = false

    @Volatile
    private var appRef: Context? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 音乐播放状态轮询：驱动「音乐律动」。
     *
     * **去抖（v0.8.0）**：某些 ROM / App 下播放状态会来回抖（一阵 true 一阵 false），
     * 直接照单全收会让环忽明忽暗，看起来就是「没放音乐也在闪」。这里要求
     * 连续 [MUSIC_CONFIRM_HITS] 次采样一致才真正改变状态：放歌时晚 ~1 秒亮起
     * （基本无感），但单次假信号再也点不亮环。
     */
    private var musicCandidate: Boolean = false
    private var musicCandidateHits = 0

    private val musicPollRunnable = object : Runnable {
        override fun run() {
            val app = appRef
            if (app == null) {
                mainHandler.postDelayed(this, MUSIC_POLL_MS)
                return
            }
            val now = isMusicPlaying(app)
            if (now == musicCandidate) {
                musicCandidateHits++
            } else {
                musicCandidate = now
                musicCandidateHits = 1
            }
            if (musicCandidateHits >= MUSIC_CONFIRM_HITS) {
                RingState.setMusicActive(musicCandidate)
            }
            mainHandler.postDelayed(this, MUSIC_POLL_MS)
        }
    }

    /**
     * 是否正在播放**音乐**（严格排除视频/游戏/提示音）。
     *
     * 判据统一收在 [MusicPlayback]：媒体用途 + 音乐内容类型，且播放器必须真的
     * 处于 STARTED —— `getActivePlaybackConfigurations()` 把「已暂停」也算 active，
     * 只看这个列表会留下「音乐停了、环还在律动」的假阳性。
     */
    private fun isMusicPlaying(context: Context): Boolean = MusicPlayback.isPlaying(context)

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        appRef = app

        // 先读 sticky 立即初始化电量
        app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { intent ->
            handleBattery(app, intent)
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_BATTERY_CHANGED -> handleBattery(ctx, intent)
                    PowerManager.ACTION_POWER_SAVE_MODE_CHANGED ->
                        RingState.updateBattery(
                            RingState.level, RingState.charging,
                            ctx.powerManager().isPowerSaveMode,
                        )
                    Intent.ACTION_SCREEN_ON -> {
                        RingState.setScreenOn(true)
                        // v1.3.3：亮屏瞬间同步一次锁屏状态——「锁屏时隐藏圆环」勾选时，
                        // 锁屏上要立刻收起环（不能被下面的收起复位又放出来）
                        RingState.syncLockScreenState()
                        // 屏幕刚点亮：此刻不可能真有面板展开 / 沉浸收起，
                        // 强制复位三条收起输入，救回被假信号永久收缩的环
                        RingState.resetCollapseInputs("屏幕点亮")
                        // 再让各探针按系统实时值对齐一次（探针自身的轮询之外，
                        // 这里主动踢一脚，避免刚点亮的那一秒里画面仍是收缩态）
                        RingState.requestReconcile()
                        // 冷启动后若还没读到过配置，屏幕点亮（说明用户在用机）就补读一次
                        if (HookPrefs.needsRetry()) {
                            ModuleLog.i("屏幕点亮，补读一次配置")
                            HookPrefs.invalidate()
                        }
                    }
                    Intent.ACTION_SCREEN_OFF -> {
                        RingState.setScreenOn(false)
                    }
                    Intent.ACTION_CONFIGURATION_CHANGED -> {
                        // 屏幕旋转后系统不会再调 setIsHideBattery，必须自己重评估
                        RingState.notifyOrientationChanged()
                    }
                    Intent.ACTION_USER_PRESENT -> {
                        // 解锁完成：锁屏期间的面板/灵动岛信号全部作废。
                        // 先开一段沉降窗口——解锁动画里的过渡态假信号一律不作数，
                        // 保证「解锁进桌面 → 环立刻就在」而不是等探针慢慢纠回来。
                        RingState.beginSettleWindow(UNLOCK_SETTLE_MS)
                        RingState.requestReconcile()
                        // 窗口结束前后再复核几次，把迟到的假值也放掉
                        for (delay in UNLOCK_RECHECK_MS) {
                            mainHandler.postDelayed({ RingState.requestReconcile() }, delay)
                        }
                        // 冷启动时 SystemUI 先于解锁起来，此刻配置（凭据加密存储）
                        // 还读不到，模块会退回默认值；解锁后补读一次，
                        // 避免"重启后必须手动开关一次设置才生效"。
                        if (HookPrefs.needsRetry()) {
                            ModuleLog.i("用户已解锁，补读一次配置")
                            HookPrefs.invalidate()
                        }
                    }
                    ACTION_CONFIG_CHANGED -> {
                        HookPrefs.invalidate()
                        RingState.invalidateAll()
                    }
                    // 应用侧「通知使用权」通道推来的提醒状态（主通道）
                    NotifStateBridge.ACTION_NOTIF_STATE_CHANGED ->
                        RingState.setNotificationsActiveFromApp(
                            intent.getBooleanExtra(NotifStateBridge.EXTRA_NOTIF_ACTIVE, false),
                            intent.getLongExtra(NotifStateBridge.EXTRA_NOTIF_SINCE, 0L),
                            intent.getBooleanExtra(NotifStateBridge.EXTRA_NOTIF_AUTHORITATIVE, false),
                        )
                    // 设置页「预览提醒效果」：临时播一遍闪烁，用于当场确认效果
                    NotifStateBridge.ACTION_NOTIF_PREVIEW ->
                        RingState.previewNotifications()
                    // 设置页「预览音乐律动」：不依赖真的在放歌也能看效果
                    NotifStateBridge.ACTION_MUSIC_PREVIEW ->
                        RingState.previewMusic()
                    // 音频驱动律动：应用侧麦克风采集的实时音量/内容类型
                    NotifStateBridge.ACTION_AUDIO_LEVEL ->
                        RingState.setAudioLevel(
                            intent.getFloatExtra(NotifStateBridge.EXTRA_AUDIO_LEVEL, 0f),
                            intent.getIntExtra(NotifStateBridge.EXTRA_AUDIO_KIND, 0),
                        )
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_CONFIGURATION_CHANGED)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(ACTION_CONFIG_CHANGED)
            addAction(NotifStateBridge.ACTION_NOTIF_STATE_CHANGED)
            addAction(NotifStateBridge.ACTION_NOTIF_PREVIEW)
            addAction(NotifStateBridge.ACTION_MUSIC_PREVIEW)
            addAction(NotifStateBridge.ACTION_AUDIO_LEVEL)
        }
        // 接收来自本模块配置页（另一应用进程/包）的显式广播，需要 EXPORTED
        app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        RingState.setScreenOn(app.powerManager().isInteractive)

        // 主动向模块应用侧查一次当前提醒状态：SystemUI 重启后状态不丢
        // （应用侧 RingNotificationListener 常驻被系统绑定，能立即应答）
        runCatching {
            app.sendBroadcast(
                Intent(NotifStateBridge.ACTION_NOTIF_QUERY)
                    .setPackage(NotifStateBridge.MODULE_PACKAGE),
            )
        }

        ModuleLog.i("电池状态监听已注册")

        // 音乐律动的数据源：开始轮询音乐播放状态
        mainHandler.post(musicPollRunnable)
    }

    private fun handleBattery(context: Context, intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)

        val percent = when {
            level >= 0 && scale > 0 -> (level * 100 / scale)
            level in 0..100 -> level // 部分设备直接给百分比
            else -> RingState.level
        }
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL || plugged != 0
        val powerSave = context.powerManager().isPowerSaveMode

        RingState.updateBattery(percent, charging, powerSave)
    }

    private fun Context.powerManager(): PowerManager =
        getSystemService(Context.POWER_SERVICE) as PowerManager
}
