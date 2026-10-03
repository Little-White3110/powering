package com.powerring.hole.hook

import com.powerring.hole.core.ModuleLog
import com.powerring.hole.ring.RingState
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 「消息提醒闪烁」的兜底数据源（v0.2 新增，v0.3.2 加固）。
 *
 * ⚠️ 主通道已改为应用侧的「通知使用权」方案（见 data/RingNotificationListener）：
 * 那条链路走公开 API，跨 HyperOS 版本稳定。本 Hook 只作为**未授权时**的兜底，
 * 与主通道在 RingState 里取并集，互不覆盖。
 *
 * 加固点（原先常常一条都挂不上，导致提醒静默失效）：
 * - 不再只看 SystemUI 那一个类：沿**类继承链**逐层扫 onNotificationPosted /
 *   onNotificationRemoved 的全部重载（方法可能声明在父类上）；
 * - 额外挂接框架基类的 binder 回调包装类
 *   `NotificationListenerService$NotificationListenerWrapper`——这是系统通知
 *   真正进入应用进程的第一入口，最不容易因 SystemUI 换类名而失效；
 * - 参数类型（StatusBarNotification / IStatusBarNotification）不硬编码，
 *   统一反射调 getKey() / isClearable()，失败逐条跳过；
 * - 常驻通知（isClearable=false 或 isOngoing=true）不计入提醒，
 *   避免充电时被 ongoing 通知闪个不停；
 * - **集合以系统推送的 RankingMap 为准做「删」**（v0.7.0）：只靠
 *   posted/removed 增减，一旦漏掉一次 removed（机型/版本差异、系统吞回调）
 *   就会永久卡在「有通知」，症状是「通知早清了、环还在慢慢呼吸」。
 *   系统每次通知集合变化都会推一次 `onNotificationRankingUpdate`，
 *   里面的 orderedKeys() 就是当前全部通知的权威键集，用它剪枝即自愈。
 * - 任何异常只影响闪烁功能，圆环本体不受影响（调用方已 try/catch）。
 */
object NotificationBlinkHook {

    /** SystemUI 侧可能的通知监听实现类名（不同 HyperOS 版本命名不同） */
    private val TARGET_CLASSES = listOf(
        "com.android.systemui.statusbar.NotificationListener",
        "com.android.systemui.statusbar.notification.NotificationListener",
        "com.android.systemui.statusbar.notification.collection.NotificationListener",
    )

    /** 框架侧 binder 回调包装类（系统通知进进程的第一入口） */
    private const val WRAPPER_CLASS =
        "android.service.notification.NotificationListenerService\$NotificationListenerWrapper"

    private val activeKeys: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

    private var installed = false

    fun install(classLoader: ClassLoader) {
        if (installed) return
        installed = true

        val postedHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val key = extractKey(param.args) ?: return
                val clearable = extractClearable(param.args) ?: true
                val ongoing = extractOngoing(param.args) ?: false
                if (clearable && !ongoing) {
                    val isNew = activeKeys.add(key)
                    // 新通知到达 → 刷新「到达时刻」，让「限时提醒」重新计时
                    if (isNew) runCatching { RingState.onNotificationArrived() }
                } else {
                    activeKeys.remove(key)
                }
                refresh()
            }
        }
        val removedHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val key = extractKey(param.args) ?: return
                activeKeys.remove(key)
                refresh()
            }
        }
        // 通知集合每次变化系统都会推 RankingMap（当前全部通知的权威键集）。
        // 用它给本地集合剪枝，兜住所有漏掉/被吞的 onNotificationRemoved。
        val rankingHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val keys = extractRankingKeys(param.args) ?: return
                if (activeKeys.retainAll(keys)) refresh()
            }
        }

        var hooked = 0
        for (name in TARGET_CLASSES) {
            hooked += hookInHierarchy(name, classLoader, postedHook, removedHook, rankingHook)
        }
        hooked += hookInHierarchy(WRAPPER_CLASS, classLoader, postedHook, removedHook, rankingHook)

        if (hooked == 0) {
            ModuleLog.e("通知回调一个都没挂上，消息提醒闪烁走应用侧通道（需授予通知使用权）", null)
        } else {
            ModuleLog.i("NotificationBlinkHook 安装完成（挂接 $hooked 个通知回调，兜底通道）")
        }
    }

    /**
     * 沿继承链挂接目标类（含父类）上所有 onNotificationPosted /
     * onNotificationRemoved / onNotificationRankingUpdate 重载。
     * 类不存在或某个方法挂接失败都只跳过。
     */
    private fun hookInHierarchy(
        className: String,
        classLoader: ClassLoader,
        postedHook: XC_MethodHook,
        removedHook: XC_MethodHook,
        rankingHook: XC_MethodHook,
    ): Int {
        val start = runCatching { XposedHelpers.findClass(className, classLoader) }.getOrNull()
            ?: return 0
        var count = 0
        var cls: Class<*>? = start
        while (cls != null && cls != Any::class.java) {
            for (m in cls.declaredMethods) {
                val hook = when (m.name) {
                    "onNotificationPosted" -> postedHook
                    "onNotificationRemoved" -> removedHook
                    "onNotificationRankingUpdate" -> rankingHook
                    else -> continue
                }
                runCatching { XposedBridge.hookMethod(m, hook) }.onSuccess { count++ }
            }
            cls = cls.superclass
        }
        return count
    }

    /**
     * 从 RankingMap / NotificationRankingUpdate 参数里反射取当前全部通知的 key。
     *
     * `getOrderedKeys()` 返回的可能是 String[] 也可能是 List，两种都兼容；
     * 拿不到就返回 null（这次剪枝整体跳过，不影响其它逻辑）。
     */
    private fun extractRankingKeys(args: Array<Any?>): Set<String>? {
        for (a in args) {
            if (a == null) continue
            val raw = runCatching { XposedHelpers.callMethod(a, "getOrderedKeys") }.getOrNull()
                ?: continue
            val keys = when (raw) {
                is Array<*> -> raw.filterIsInstance<String>()
                is Iterable<*> -> raw.filterIsInstance<String>()
                else -> emptyList()
            }
            return keys.toHashSet()
        }
        return null
    }

    /** 从回调参数里反射取通知 key；找不到就放弃这一条。 */
    private fun extractKey(args: Array<Any?>): String? {
        for (a in args) {
            if (a == null) continue
            val key = runCatching { XposedHelpers.callMethod(a, "getKey") as? String }.getOrNull()
            if (key != null) return key
        }
        return null
    }

    /** 反射取 isClearable()；取不到按可清除处理（宁可多闪不可漏闪）。 */
    private fun extractClearable(args: Array<Any?>): Boolean? {
        for (a in args) {
            if (a == null) continue
            val v = runCatching { XposedHelpers.callMethod(a, "isClearable") as? Boolean }.getOrNull()
            if (v != null) return v
        }
        return null
    }

    /** 反射取 isOngoing()；取不到按非常驻处理。 */
    private fun extractOngoing(args: Array<Any?>): Boolean? {
        for (a in args) {
            if (a == null) continue
            val v = runCatching { XposedHelpers.callMethod(a, "isOngoing") as? Boolean }.getOrNull()
            if (v != null) return v
        }
        return null
    }

    private fun refresh() {
        runCatching { RingState.setNotificationsActiveFromHook(activeKeys.isNotEmpty()) }
    }
}
