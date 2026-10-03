package com.powerring.hole.data

import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * 「消息提醒闪烁」的可靠数据源（v0.3.2 新增）。
 *
 * 这是模块**应用侧**的通知监听服务：用户在系统设置里给本应用授权
 * 「通知使用权」后，系统会用公开 API 把每条通知的增删回调到这里，
 * 我们再经 [NotifStateBridge] 广播给 SystemUI 进程。
 *
 * 相比直接反射 Hook SystemUI 的 NotificationListener，这条链路的好处：
 * - 用的是 NotificationListenerService 公开 API，不依赖 HyperOS 内部类名/签名；
 * - 本服务只统计「是否存在可清除通知」，不读取、不保存任何通知内容；
 * - 未授权时系统不会绑定本服务，SystemUI 侧的 Hook 兜底仍然生效。
 *
 * 过滤规则与 Hook 侧一致：只有「可清除」且**非**常驻（isOngoing）的通知才算
 * 需要提醒的消息——正在播放、USB 调试这类常驻通知不计入，避免充电时闪个不停。
 */
class RingNotificationListener : NotificationListenerService() {

    /** 当前仍挂在通知栏、且可提醒的通知 key 集合 */
    private val activeKeys: MutableSet<String> =
        java.util.Collections.synchronizedSet(HashSet())

    /** 上一次已发布的状态；null 表示尚未发布过（用于强制重发） */
    @Volatile
    private var lastPublished: Boolean? = null

    /** 上一次已发布的时间戳，配合 [lastPublished] 判断是否要重发 */
    @Volatile
    private var lastPublishedSince: Long = -1L

    /** 上一次发布时是否具备「权威」身份（未授权→断开时为 false） */
    @Volatile
    private var lastAuthoritative: Boolean? = null

    /** 最新一条可提醒通知的到达时刻（uptimeMillis），驱动提醒的「新鲜度」 */
    @Volatile
    private var newestAlertAtMs: Long = 0L

    /**
     * 自愈对账用的定时器（v0.7.0）。
     *
     * 只靠「posted 加、removed 减」维护集合，一旦系统漏掉一次 removed
     * （机型/版本差异、系统吞回调、服务中途重连等），集合就会永远停在非空，
     * 表现为「通知早就清了、环还在慢慢呼吸」。所以只要集合非空，
     * 就周期性拿系统**权威列表** [getActiveNotifications] 对一次账，把不存在的
     * 键删掉。放在主线程 Handler 上跑（不在通知回调里），避免同步 binder 回环。
     */
    private val mainHandler = Handler(Looper.getMainLooper())

    private val reconcileRunnable = object : Runnable {
        override fun run() {
            if (activeKeys.isEmpty()) return
            reconcileFromSystem()
            mainHandler.postDelayed(this, RECONCILE_MS)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        // 连接时（含重启后首次连接）用系统当前通知列表重建一次状态
        activeKeys.clear()
        runCatching {
            activeNotifications?.forEach { sbn ->
                if (isAlerting(sbn)) {
                    activeKeys.add(sbn.key)
                    newestAlertAtMs = android.os.SystemClock.uptimeMillis()
                }
            }
        }.onFailure { Log.w(TAG, "读取当前通知列表失败", it) }
        lastPublished = null
        connected = true
        publish()
        scheduleReconcile()
        Log.i(TAG, "通知监听已连接，可提醒通知 ${activeKeys.size} 条")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        activeKeys.clear()
        mainHandler.removeCallbacks(reconcileRunnable)
        // 断开时连「权威」身份一起撤掉：此时状态不再可信，应让 SystemUI 侧
        // 的反射 Hook 兜底通道重新参与判断。
        lastPublished = null
        lastPublishedSince = -1L
        connected = false
        publish(authoritative = false)
    }

    /**
     * 每次通知集合变化（含增删与排序）系统都会推一次 RankingMap，
     * 里面的 orderedKeys() 就是**当前全部通知的权威键集**。
     *
     * 拿它给本地集合做一次「删」：只保留仍然存在的键。这是 push 通道，
     * 不产生额外的同步 binder 调用，能兜住所有漏掉的 onNotificationRemoved。
     */
    override fun onNotificationRankingUpdate(rankingMap: RankingMap?) {
        super.onNotificationRankingUpdate(rankingMap)
        val keys = runCatching { rankingMap?.orderedKeys }.getOrNull() ?: return
        if (activeKeys.retainAll(keys.toHashSet())) publish()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        if (isAlerting(n)) {
            activeKeys.add(n.key)
            // 新通知到达 → 提醒回到「新鲜期」（闪得快、颜色鲜艳）
            newestAlertAtMs = android.os.SystemClock.uptimeMillis()
        } else {
            activeKeys.remove(n.key)
        }
        publish()
        scheduleReconcile()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        activeKeys.remove(n.key)
        publish()
    }

    /** 集合非空时开启/续上一次对账定时器。 */
    private fun scheduleReconcile() {
        if (activeKeys.isEmpty()) return
        mainHandler.removeCallbacks(reconcileRunnable)
        mainHandler.postDelayed(reconcileRunnable, RECONCILE_MS)
    }

    /**
     * 用系统权威列表校正本地集合：**只删不加**。
     *
     * 「加」仍由 onNotificationPosted 负责——那里才拿得到到达时刻，能驱动
     * 「新消息快速提示」的新鲜期。这里只负责把已经不存在（或已变成常驻/
     * 不可清除）的键清掉。
     */
    private fun reconcileFromSystem() {
        val list = runCatching { activeNotifications }.getOrNull() ?: return
        val truth = HashSet<String>()
        for (sbn in list) if (isAlerting(sbn)) truth.add(sbn.key)
        if (activeKeys.retainAll(truth)) {
            Log.i(TAG, "通知状态对账：剩余可提醒通知 ${activeKeys.size} 条")
            publish()
        }
    }

    /** 只有「可清除」且非常驻的通知才需要提醒。 */
    private fun isAlerting(sbn: StatusBarNotification): Boolean =
        sbn.isClearable && !sbn.isOngoing

    /**
     * 发布当前状态。
     *
     * [authoritative] = true 表示本服务确实持有「通知使用权」、状态来自系统
     * 真实回调；SystemUI 侧收到后会以本通道为准并忽略反射兜底通道。
     */
    private fun publish(authoritative: Boolean = true) {
        val active = activeKeys.isNotEmpty()
        val since = if (active) newestAlertAtMs else 0L
        // 时间戳也要参与去重：第二条通知到达时布尔值没变，但新鲜期必须重置
        if (authoritative == lastAuthoritative &&
            active == lastPublished && since <= lastPublishedSince
        ) {
            return
        }
        lastPublished = active
        lastPublishedSince = since
        lastAuthoritative = authoritative
        lastActive = active
        lastCount = activeKeys.size
        NotifStateBridge.publish(applicationContext, active, since, authoritative)
    }

    companion object {
        private const val TAG = "HolePowerRing"

        /** 自愈对账间隔：够快能立刻纠正脏状态，又不会造成可感知开销 */
        const val RECONCILE_MS = 4000L

        /** 供设置页「通知提醒状态」行读取的最近一次状态（同进程内） */
        @Volatile
        var lastActive: Boolean = false

        /** 最近一次统计到的「可提醒通知」条数（供设置页显示） */
        @Volatile
        var lastCount: Int = 0

        /**
         * 本监听服务当前是否已被系统绑定（= 已授权且在线）。
         *
         * [NotifStateResponder] 回答 SystemUI 的状态查询时用它判断本地状态是否可信：
         * 服务不在线时只能保守地回答「无提醒」——否则会把上次遗留的 active=true
         * 一直喂给 SystemUI，正好复现「通知早清了环还在呼吸」。
         */
        @Volatile
        var connected: Boolean = false
    }
}
