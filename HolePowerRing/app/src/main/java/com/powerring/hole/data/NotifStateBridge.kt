package com.powerring.hole.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log

/**
 * 模块应用侧 ↔ SystemUI 进程的通知提醒状态桥（v0.3.2 新增）。
 *
 * 链路：本应用申请「通知使用权」后，系统把通知回调交给 [RingNotificationListener]；
 * 它把「当前是否存在可提醒通知」写入本地状态，并广播显式 Action 给
 * com.android.systemui；SystemUI 进程里的 [BatteryObserver] 收到后驱动 RingState。
 *
 * SystemUI 每次启动会主动发一条查询广播，[NotifStateResponder] 回一份当前状态，
 * 保证 SystemUI 重启（如本模块的「重启系统界面」）后状态不丢。
 *
 * 为什么不用 Hook 当主通道：HyperOS 各版本 NotificationListener 的类名/方法签名
 * 会变，实测常常一条都挂不上，功能静默失效。这条链路全部走公开 API，跨版本稳定；
 * SystemUI 侧的反射 Hook 只作为「未授权时的兜底」。
 */
object NotifStateBridge {

    private const val NAME = "hole_power_ring_notif"
    private const val KEY_ACTIVE = "notif_active"
    private const val TAG = "HolePowerRing"

    const val SYSTEMUI_PACKAGE = "com.android.systemui"
    const val MODULE_PACKAGE = "com.powerring.hole"
    const val ACTION_NOTIF_STATE_CHANGED = "com.powerring.hole.NOTIF_STATE_CHANGED"
    const val ACTION_NOTIF_QUERY = "com.powerring.hole.QUERY_NOTIF_STATE"
    const val ACTION_NOTIF_PREVIEW = "com.powerring.hole.NOTIF_PREVIEW"
    const val ACTION_MUSIC_PREVIEW = "com.powerring.hole.MUSIC_PREVIEW"
    const val EXTRA_NOTIF_ACTIVE = "active"

    /**
     * 音频驱动律动（v1.3.0）：应用侧用麦克风采集的实时音量/内容类型，广播给 SystemUI。
     *
     * 采集在**模块应用进程**（持 RECORD_AUDIO 权限）完成，因为模块 Hook 跑在 SystemUI
     * 进程里、用的是 SystemUI 的身份，拿不到录音权限；而模块 APK 自己是一个普通应用，
     * 用户授权后就能录音。结果经这条广播喂给 SystemUI 里的 RingState。
     */
    const val ACTION_AUDIO_LEVEL = "com.powerring.hole.AUDIO_LEVEL"
    const val EXTRA_AUDIO_LEVEL = "level"
    const val EXTRA_AUDIO_KIND = "kind"

    /**
     * 本通道是否为**权威**数据源（v0.7.0 新增）。
     *
     * 只有本应用确实持有「通知使用权」时，应用侧才拿得到真实的通知增删回调，
     * 这时它的状态才算权威。权威为 true 时，SystemUI 侧会**完全忽略反射 Hook
     * 兜底通道**——因为反射通道只能靠「posted 加、removed 减」，一旦漏掉一次
     * removed（换机型、换版本、系统吞回调）就会永久卡在「有通知」，
     * 表现为「通知早清了、环还在慢慢呼吸」。两通道取并集会把这种脏状态
     * 一直带下去，所以授权后必须由权威通道说了算。
     */
    const val EXTRA_NOTIF_AUTHORITATIVE = "authoritative"

    /**
     * 最新一条提醒的到达时刻（SystemClock.uptimeMillis）。
     * uptimeMillis 是全系统统一时钟，SystemUI 侧可直接与自己的 now 相减，
     * 用于「提醒随时间衰减」（新通知到达会把计时重置回新鲜期）。
     */
    const val EXTRA_NOTIF_SINCE = "since_ms"

    /** 提醒状态需要送达的渲染进程（v1.2.0 起只有 SystemUI；息屏进程已移除）。 */
    private val TARGET_PACKAGES = arrayOf(SYSTEMUI_PACKAGE)

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun save(ctx: Context, active: Boolean) {
        runCatching { prefs(ctx).edit().putBoolean(KEY_ACTIVE, active).commit() }
    }

    fun load(ctx: Context): Boolean =
        runCatching { prefs(ctx).getBoolean(KEY_ACTIVE, false) }.getOrDefault(false)

    /** 保存状态并广播给 SystemUI 进程。 */
    fun publish(ctx: Context, active: Boolean, sinceMs: Long = 0L, authoritative: Boolean = false) {
        save(ctx, active)
        Log.i(TAG, "通知提醒状态 → systemui: active=$active since=$sinceMs auth=$authoritative")
        for (target in TARGET_PACKAGES) {
            runCatching {
                ctx.sendBroadcast(
                    Intent(ACTION_NOTIF_STATE_CHANGED)
                        .putExtra(EXTRA_NOTIF_ACTIVE, active)
                        .putExtra(EXTRA_NOTIF_SINCE, sinceMs)
                        .putExtra(EXTRA_NOTIF_AUTHORITATIVE, authoritative)
                        .setPackage(target),
                )
            }
        }
    }

    /**
     * 把麦克风采集到的实时音量（0–1）与内容类型（0 未知 / 1 音乐 / 2 人声）
     * 广播给 SystemUI（v1.3.0 音频驱动律动）。
     */
    fun publishAudioLevel(ctx: Context, level: Float, kind: Int) {
        runCatching {
            ctx.sendBroadcast(
                Intent(ACTION_AUDIO_LEVEL)
                    .putExtra(EXTRA_AUDIO_LEVEL, level)
                    .putExtra(EXTRA_AUDIO_KIND, kind)
                    .setPackage(SYSTEMUI_PACKAGE),
            )
        }
    }

    /**
     * 本应用当前是否持有「通知使用权」。
     *
     * 读系统设置里的已授权监听器列表判断（与设置页显示的口径一致）。
     * 只有授权为 true 时 [RingNotificationListener] 才会收到真实通知回调，
     * 应用侧状态才可当权威用。
     */
    fun hasNotificationAccess(ctx: Context): Boolean {
        val flat = runCatching {
            android.provider.Settings.Secure.getString(
                ctx.contentResolver, "enabled_notification_listeners",
            )
        }.getOrNull() ?: return false
        return flat.split(':').any { entry ->
            entry.isNotBlank() &&
                android.content.ComponentName.unflattenFromString(entry)?.packageName == ctx.packageName
        }
    }

    /**
     * 让渲染侧临时播放一遍提醒效果（设置页「预览提醒效果」用）。
     * 只影响预览状态，不碰真实的通知标志位，因此不会误清真实提醒。
     */
    fun requestPreview(ctx: Context) {
        Log.i(TAG, "请求预览提醒效果 → systemui")
        for (target in TARGET_PACKAGES) {
            runCatching {
                ctx.sendBroadcast(Intent(ACTION_NOTIF_PREVIEW).setPackage(target))
            }
        }
    }

    /**
     * 让 SystemUI 侧临时按「音乐律动」渲染一小段时间（设置页「预览音乐律动」用）。
     * 与提醒预览同理，走独立预览通道，不影响真实的音乐检测状态。
     */
    fun requestMusicPreview(ctx: Context) {
        Log.i(TAG, "请求预览音乐律动 → systemui")
        runCatching {
            ctx.sendBroadcast(Intent(ACTION_MUSIC_PREVIEW).setPackage(SYSTEMUI_PACKAGE))
        }
    }
}

/**
 * 响应 SystemUI 启动时的状态查询（查询广播显式发到本应用包名）。
 *
 * 直接用**监听服务的实时状态**回答，而不是本地 SharedPreferences 里的历史值：
 * 服务不在线（未授权/未绑定）时历史值可能是上次遗留的 `true`，
 * 把它喂给 SystemUI 正好会复现「通知早清了、环还在呼吸」。服务不在线时
 * 一律保守回答「无提醒」并撤掉权威身份，让 SystemUI 侧反射兜底通道自己去判断。
 */
class NotifStateResponder : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != NotifStateBridge.ACTION_NOTIF_QUERY) return
        val ctx = context.applicationContext
        val live = RingNotificationListener.connected && RingNotificationListener.lastActive
        NotifStateBridge.publish(
            ctx,
            live,
            0L,
            RingNotificationListener.connected && NotifStateBridge.hasNotificationAccess(ctx),
        )
    }
}
