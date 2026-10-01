package com.powerring.hole.ring

import android.animation.ValueAnimator
import android.content.Context
import android.view.animation.DecelerateInterpolator
import android.graphics.Canvas
import android.view.View
import com.powerring.hole.core.HookPrefs
import com.powerring.hole.core.ModuleLog
import java.util.Collections
import java.util.WeakHashMap

/**
 * 环形电量的全局状态（SystemUI 进程单例）。
 *
 * 负责：配置缓存、电池状态、挖孔 View 注册、电量弧动画调度。
 * 绘制本身委托给 [RingRenderer]。
 */
object RingState {

    /** 挖孔装饰 View（弱引用，View 销毁后自动移除） */
    private val cutoutViews: MutableSet<View> =
        Collections.newSetFromMap(WeakHashMap())

    @Volatile
    var appContext: Context? = null
        private set

    // ---- 电池状态 ----
    @Volatile var level: Int = 50
        private set
    @Volatile var charging: Boolean = false
        private set
    @Volatile var powerSave: Boolean = false
        private set
    @Volatile var screenOn: Boolean = true
        private set

    /** 挖孔填充色（= 状态栏图标色），决定使用浅色还是深色令牌 */
    @Volatile
    var tintColor: Int = 0xFFFFFFFF.toInt()
        private set
    @Volatile
    private var tintInitialized = false

    /** 是否曾经成功解析到挖孔几何——决定能否安全隐藏原电池图标 */
    @Volatile
    var cutoutEverResolved: Boolean = false
        private set

    /** 当前动画显示到的电量百分比（0..100） */
    @Volatile
    var animatedLevel: Float = 50f
        private set

    private var levelAnimator: ValueAnimator? = null
    private val levelInterpolator = DecelerateInterpolator(1.5f)

    val config: RingConfig
        get() = HookPrefs.get()

    /** 上一帧使用的配置签名，变化时补发一次重绘让静态画面也能即时响应调节 */
    @Volatile
    private var lastConfigSig: String = ""

    private fun RingConfig.signature() =
        "$ringEnabled|$strokeWidthDp|$offsetXDp|$offsetYDp|$scale|$useCustomColor|$customColor|$chargingGlow|$levelAnim"

    // ---- 生命周期 ----

    fun attachContext(context: Context) {
        appContext = context.applicationContext
        HookPrefs.hostContext = appContext
    }

    fun attachCutoutView(view: View) {
        cutoutViews.add(view)
    }

    fun detachCutoutView(view: View) {
        cutoutViews.remove(view)
    }

    // ---- 状态更新 ----

    fun setTintColor(color: Int) {
        if (!tintInitialized || tintColor != color) {
            tintColor = color
            tintInitialized = true
            invalidateAll()
        }
    }

    fun updateBattery(level: Int, charging: Boolean, powerSave: Boolean) {
        val changed = this.level != level || this.charging != charging || this.powerSave != powerSave
        this.level = level.coerceIn(0, 100)
        this.charging = charging
        this.powerSave = powerSave
        if (changed) {
            ModuleLog.i("电池状态: level=${this.level} charging=$charging powerSave=$powerSave")
            startLevelAnimation(this.level.toFloat())
        }
    }

    fun setScreenOn(on: Boolean) {
        if (screenOn != on) {
            screenOn = on
            ModuleLog.i("屏幕状态: screenOn=$on")
            invalidateAll()
        }
    }

    /** 挖孔几何首次解析成功的回调（由隐藏 Hook 注册，用于时序补偿）。 */
    @Volatile
    var onCutoutResolved: (() -> Unit)? = null

    fun markCutoutResolved() {
        if (!cutoutEverResolved) {
            cutoutEverResolved = true
            ModuleLog.i("已确认挖孔几何可用，允许隐藏原电池图标")
            try {
                onCutoutResolved?.invoke()
            } catch (t: Throwable) {
                ModuleLog.e("挖孔就绪回调异常", t)
            }
        }
    }

    /** 是否强制隐藏状态栏原电池图标：环开启 + 隐藏选项开启 + 挖孔确实存在。 */
    fun shouldForceHideBattery(): Boolean {
        val c = config
        return c.ringEnabled && c.hideBattery && cutoutEverResolved
    }

    // ---- 绘制入口（由 Hook 在 DisplayCutoutBaseView.onDraw 后调用） ----

    fun onCutoutDraw(view: View, canvas: Canvas) {
        val c = config
        attachCutoutView(view)
        // 配置发生变化时补发一帧，使滑杆/颜色调节在静止状态下也即时可见
        val sig = c.signature()
        if (sig != lastConfigSig) {
            lastConfigSig = sig
            invalidateAll()
        }
        if (!c.ringEnabled || !screenOn) return
        try {
            RingRenderer.draw(view, canvas, c, this)
        } catch (t: Throwable) {
            // 绘制异常绝不能影响系统挖孔本身的渲染
            ModuleLog.e("环形电量绘制异常", t)
        }
    }

    // ---- 动画与刷新 ----

    private fun startLevelAnimation(target: Float) {
        val c = config
        val start = animatedLevel
        val animator = levelAnimator
        if (animator != null && animator.isRunning) animator.cancel()
        if (!c.levelAnim || kotlin.math.abs(target - start) < 0.5f) {
            animatedLevel = target
            invalidateAll()
            return
        }
        levelAnimator = ValueAnimator.ofFloat(start, target).apply {
            duration = 360L
            interpolator = levelInterpolator
            addUpdateListener {
                animatedLevel = it.animatedValue as Float
                invalidateAll()
            }
        }.also { it.start() }
    }

    /** 请求所有挖孔 View 重绘（postInvalidate 可在任意线程调用）。 */
    fun invalidateAll() {
        val snapshot = synchronized(cutoutViews) { cutoutViews.toList() }
        for (v in snapshot) {
            if (!v.isAttachedToWindow) {
                cutoutViews.remove(v)
                continue
            }
            v.postInvalidateOnAnimation()
        }
    }
}
