package com.powerring.hole.hook

import android.os.Handler
import android.os.Looper
import com.powerring.hole.core.ModuleLog
import com.powerring.hole.ring.RingState
import com.powerring.hole.ring.SystemBatteryColors
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference
import java.util.ArrayList

/**
 * 让环形电量跟随系统原生电池图标的颜色。
 *
 * 取色链路（类名/字段名见可行性分析报告 §12，仅适配系统界面 17.03.260226.r）：
 * ```
 * LightBarTransitionsController.animateIconTint  // 反色动画时钟，LINEAR 插值
 *   └─ MiuiBatteryMeterView.onDarkChanged(ArrayList, float, int)  // 每帧回调 darkIntensity
 *        └─ MiuiBatteryMeterIconView.onDarkChangeInternal()        // 系统在此算出最终颜色
 * ```
 * 本 Hook 在 `onDarkChangeInternal()` **之后**读字段——此刻系统的
 * `mLightColor / mDarkColor / mDarkIntensity / mBattery*Color / mLow …` 全部是最新值，
 * 直接复用即可，不必重算系统那套优先级。
 *
 * 所有反射与回调都包 try/catch：任何一步失败都只是让环退回内置语义色，
 * 绝不把异常抛进 SystemUI。
 */
object BatteryColorHook {

    private const val METER_VIEW =
        "com.android.systemui.statusbar.views.MiuiBatteryMeterView"
    private const val ICON_VIEW =
        "com.android.systemui.statusbar.views.MiuiBatteryMeterIconView"
    private const val LIGHT_BAR =
        "com.android.systemui.statusbar.phone.LightBarTransitionsController"

    /** 读不到系统常量时的兜底反色过渡时长 */
    private const val DEFAULT_TINT_DURATION_MS = 250L

    /**
     * 兜底轮询间隔。系统回调正常时每次 publish 都会被数据类相等性挡掉、
     * 不触发任何重绘，2 秒一次的开销可以忽略；它的作用是覆盖
     * 「图标 View 在 Hook 装上之前就已经是当前状态、之后不再回调」的情况。
     */
    private const val POLL_INTERVAL_MS = 2000L

    @Volatile
    private var installed = false

    @Volatile
    private var pollScheduled = false

    /** 状态栏电池根 View（弱引用，View 销毁后自动失效） */
    private var meterRef: WeakReference<Any>? = null

    /** 真正上色的图标 View（弱引用） */
    private var iconRef: WeakReference<Any>? = null

    /** 状态位组合，用于日志去重（darkIntensity 每帧变，不能进 key） */
    private var lastFlagKey = ""

    private var firstDumpLogged = false

    /** 「颜色尚未就绪」只报一次，避免兜底轮询刷屏 */
    @Volatile
    private var notReadyLogged = false

    /** 已经报过「字段不存在」的字段名，保证每种缺失只记一条日志 */
    private val missingFields = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>(),
    )

    private val handler = Handler(Looper.getMainLooper())

    fun install(classLoader: ClassLoader) {
        if (installed) return
        installed = true

        readTintDuration(classLoader)

        val iconCls = findClass(ICON_VIEW, classLoader)
        if (iconCls == null) {
            ModuleLog.e(
                "未找到 $ICON_VIEW，环形电量颜色回退到内置语义色",
                null,
            )
            return
        }
        hookIcon(iconCls)
        findClass(METER_VIEW, classLoader)?.let { hookMeter(it) }
        schedulePoll()
    }

    // ---- 安装 ----

    private fun findClass(name: String, classLoader: ClassLoader): Class<*>? =
        runCatching { XposedHelpers.findClassIfExists(name, classLoader) }.getOrNull()

    /**
     * 读取系统自己的反色过渡时长，让环的 Argb 过渡与状态栏图标同长。
     * 读不到就用 250ms 兜底，不影响功能。
     */
    private fun readTintDuration(classLoader: ClassLoader) {
        val value = runCatching {
            val cls = findClass(LIGHT_BAR, classLoader) ?: return@runCatching null
            val field = cls.getDeclaredField("DEFAULT_TINT_ANIMATION_DURATION")
            field.isAccessible = true
            field.getInt(null)
        }.getOrNull()
        val ms = value?.toLong()?.takeIf { it > 0 } ?: DEFAULT_TINT_DURATION_MS
        RingState.setTintAnimationDuration(ms)
        ModuleLog.i("电池图标颜色 Hook：反色过渡时长 = ${ms}ms")
    }

    /** 图标 View：系统真正算出颜色的位置。 */
    private fun hookIcon(cls: Class<*>) {
        try {
            XposedHelpers.findAndHookMethod(cls, "onDarkChangeInternal", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    guard("onDarkChangeInternal") {
                        iconRef = WeakReference(param.thisObject)
                        publish()
                    }
                }
            })
            ModuleLog.i("电池图标颜色 Hook 已挂载 ${cls.name}.onDarkChangeInternal")
        } catch (t: Throwable) {
            ModuleLog.e("Hook ${cls.name}.onDarkChangeInternal 失败", t)
        }
    }

    /** 电池根 View：反色动画每帧回调 + 五类状态变化回调。 */
    private fun hookMeter(cls: Class<*>) {
        try {
            XposedHelpers.findAndHookMethod(
                cls,
                "onDarkChanged",
                ArrayList::class.java,
                Float::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        guard("onDarkChanged") {
                            meterRef = WeakReference(param.thisObject)
                            publish()
                        }
                    }
                },
            )
            ModuleLog.i("电池图标颜色 Hook 已挂载 ${cls.name}.onDarkChanged")
        } catch (t: Throwable) {
            ModuleLog.e("Hook ${cls.name}.onDarkChanged 失败", t)
        }

        hookState(cls, "onPerformanceModeChanged", Boolean::class.javaPrimitiveType)
        hookState(cls, "onPowerSaveChanged", Boolean::class.javaPrimitiveType)
        hookState(
            cls, "onChargeStateChanged",
            Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
        )
        hookState(
            cls, "onBatteryLevelChanged",
            Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
        )
        hookState(
            cls, "onLightDarkTintChanged",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
        )
    }

    private val stateCallback = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            guard("state") { publish() }
        }
    }

    /**
     * 挂一个「回调里只需调 publish」的 Hook。
     * 显式拼参数数组而不是用 `*types` 展开：Java 侧签名是
     * `findAndHookMethod(Class, String, Object...)`，回调必须排在参数类型之后。
     */
    private fun hookState(cls: Class<*>, name: String, vararg types: Any?) {
        val args = arrayOfNulls<Any>(types.size + 1)
        types.copyInto(args)
        args[types.size] = stateCallback
        try {
            XposedHelpers.findAndHookMethod(cls, name, *args)
        } catch (t: Throwable) {
            ModuleLog.e("Hook $name 失败", t)
        }
    }

    // ---- 取色 ----

    /**
     * 取当前有效的图标 View。
     *
     * 弱引用被回收时（例如状态栏视图树重建、主题切换），从电池根 View 的
     * mBatteryIconView 字段重新取一次，不必干等下一次系统回调。
     */
    private fun resolveIcon(): Any? {
        iconRef?.get()?.let { return it }
        val meter = meterRef?.get() ?: return null
        val icon = runCatching {
            XposedHelpers.getObjectField(meter, "mBatteryIconView")
        }.getOrNull()
        if (icon != null) iconRef = WeakReference(icon)
        return icon
    }

    private fun publish() {
        val icon = resolveIcon() ?: return
        val colors = read(icon) ?: return

        if (!firstDumpLogged) {
            firstDumpLogged = true
            ModuleLog.i(
                "电池图标取色首次成功: light=#${hex(colors.light)} dark=#${hex(colors.dark)} " +
                    "tint=#${hex(colors.tint)} useTint=${colors.useTint} " +
                    "low=#${hex(colors.low)} powerSave=#${hex(colors.powerSave)} " +
                    "performance=#${hex(colors.performance)} charging=#${hex(colors.charging)} " +
                    "darkIntensity=${colors.darkIntensity} icon=${icon.javaClass.name}",
            )
        }
        val flagKey = "charge=${colors.chargingNow},perf=${colors.performanceNow}," +
            "save=${colors.powerSaveNow},low=${colors.lowNow}"
        if (flagKey != lastFlagKey) {
            lastFlagKey = flagKey
            ModuleLog.i("电池图标状态变化: $flagKey")
        }

        RingState.setSystemBatteryColors(colors)
    }

    /**
     * 读取系统电池图标的颜色与状态位。
     *
     * 只有 light/dark 是硬要求——它们决定普通态能不能跟随反色。
     * 其余字段逐项容错：某个字段在别的 HyperOS 版本上改名或缺失时，
     * 只丢那一个值并退回到 [RingState] 的旧值，不会把整次取色废掉。
     */
    private fun read(icon: Any): SystemBatteryColors? = try {
        val light = XposedHelpers.getIntField(icon, "mLightColor")
        val dark = XposedHelpers.getIntField(icon, "mDarkColor")
        // 这两个是系统真正用于上色的前景色；为 0 说明图标还没初始化完。
        // 只报一次，否则 2 秒兜底轮询会把日志刷满。
        if (light == 0 || dark == 0) {
            if (!notReadyLogged) {
                notReadyLogged = true
                ModuleLog.e(
                    "系统电池图标颜色未就绪(light=$light dark=$dark)，本次取色跳过", null,
                )
            }
            return null
        }
        val c = SystemBatteryColors(light = light, dark = dark)
        c.copy(
            tint = intOr(icon, "mTintColor", c.tint),
            useTint = boolOr(icon, "mUseTint", c.useTint),
            low = intOr(icon, "mBatteryLowColor", c.low),
            powerSave = intOr(icon, "mBatteryPowerSaveColor", c.powerSave),
            performance = intOr(icon, "mBatteryPerformanceModeColor", c.performance),
            charging = intOr(icon, "mBatteryChargingColor", c.charging),
            chargingNow = boolOr(icon, "mQuickCharging", c.chargingNow) ||
                boolOr(icon, "mCharging", c.chargingNow),
            performanceNow = boolOr(icon, "mPerformanceMode", c.performanceNow),
            powerSaveNow = boolOr(icon, "mPowerSave", c.powerSaveNow),
            lowNow = boolOr(icon, "mLow", c.lowNow),
            darkIntensity = floatOr(icon, "mDarkIntensity", c.darkIntensity),
        )
    } catch (t: Throwable) {
        ModuleLog.e("读取系统电池图标颜色失败", t)
        null
    }

    /** 单个 int 字段的容错读取：取不到就沿用 [fallback]，并只记一次缺失日志。 */
    private fun intOr(icon: Any, name: String, fallback: Int): Int =
        fieldOr(name, fallback) { XposedHelpers.getIntField(icon, name) }

    private fun boolOr(icon: Any, name: String, fallback: Boolean): Boolean =
        fieldOr(name, fallback) { XposedHelpers.getBooleanField(icon, name) }

    private fun floatOr(icon: Any, name: String, fallback: Float): Float =
        fieldOr(name, fallback) { XposedHelpers.getFloatField(icon, name) }

    private inline fun <T> fieldOr(name: String, fallback: T, read: () -> T): T = try {
        read()
    } catch (t: Throwable) {
        if (missingFields.add(name)) {
            ModuleLog.e("系统电池图标缺少字段 $name，本次取色该值沿用默认 $fallback", t)
        }
        fallback
    }

    // ---- 兜底轮询与工具 ----

    private fun schedulePoll() {
        if (pollScheduled) return
        pollScheduled = true
        handler.postDelayed(object : Runnable {
            override fun run() {
                pollScheduled = false
                guard("poll") { publish() }
                schedulePoll()
            }
        }, POLL_INTERVAL_MS)
    }

    /** 所有 Hook 回调的统一兜底：异常绝不允许逃逸到 SystemUI。 */
    private inline fun guard(where: String, block: () -> Unit): Boolean = try {
        block()
        true
    } catch (t: Throwable) {
        ModuleLog.e("电池图标取色($where) 异常", t)
        false
    }

    private fun hex(color: Int): String = String.format("%08X", color)
}
