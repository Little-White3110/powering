package com.powerring.hole.ring

import android.content.Context
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import com.powerring.hole.core.ModuleLog

/**
 * 方案 C 的绘制载体：注入到**状态栏窗口根 View**（StatusBarWindowView）中
 * 的自定义环形电量 View。
 *
 * 层级处理（解决被灵动岛遮挡）：
 * - 从 PhoneStatusBarView 向上遍历到窗口根，挂在根上，保证高于状态栏内所有子树
 *   （包括岛容器，岛容器通常在 PhoneStatusBarView 之外/之后添加）
 * - 高 translationZ；监听根子 View 变化，有新内容（如灵动岛）加入后重新置顶
 * - MATCH_PARENT 仅覆盖 STATUS_BAR 窗口（顶部安全区高度），下拉 shade 是独立窗口
 * - 不消费任何 insets，不拦截任何触摸事件
 */
class PowerRingView(context: Context) : View(context) {

    /**
     * 挖孔定位重试：insets 晚到是常态（attach 时系统往往还没派发 display cutout），
     * 按固定间隔重试，上限见 [RetryBudget]。重试期不绘制——resolve 返回 null 时
     * 绘制路径直接 return，无需额外改动。
     */
    private val retryBudget = RetryBudget()

    private val geometryRetry = object : Runnable {
        override fun run() {
            if (RingState.cutoutEverResolved) {
                // 已解析成功：停止重试并归还额度（下次 attach / 新一轮失败从头计）。
                // 成功停止不打日志，RingState.markCutoutResolved 已有对应日志兜底。
                retryBudget.reset()
                removeCallbacks(this)
                return
            }
            // 每次重绘都会重走一次挖孔解析（解析失败不缓存）
            postInvalidateOnAnimation()
            if (retryBudget.tryConsume()) {
                postDelayed(this, RetryBudget.INTERVAL_MS)
            } else {
                ModuleLog.i(
                    "挖孔定位重试用尽（${RetryBudget.INTERVAL_MS}ms×${RetryBudget.MAX_ATTEMPTS}），" +
                        "停止 until 窗口重建",
                )
                removeCallbacks(this)
            }
        }
    }

    init {
        isClickable = false
        isFocusable = false
        setWillNotDraw(false)

        // 记录 insets 但绝不消费，避免干扰状态栏对 cutout/statusBars 的处理
        setOnApplyWindowInsetsListener { v, insets ->
            v.postInvalidateOnAnimation()
            insets
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        RingState.attachCutoutView(this)
        requestApplyInsets()
        postInvalidateOnAnimation()
        // 窗口（重）建：额度从头计，按固定间隔触发重绘驱动解析重试
        retryBudget.reset()
        removeCallbacks(geometryRetry)
        postDelayed(geometryRetry, RetryBudget.INTERVAL_MS)
        ModuleLog.i("PowerRingView 已附加")
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(geometryRetry)
        RingState.detachCutoutView(this)
    }

    override fun onDraw(canvas: Canvas) {
        RingState.onCutoutDraw(this, canvas)
    }

    /** 任何触摸都穿透给下层状态栏图标 */
    override fun onTouchEvent(event: MotionEvent?): Boolean = false

    override fun performClick(): Boolean = false

    companion object {
        private const val INJECT_TAG = 0x7F0D0001
        private const val TAG_Z = 60f

        /**
         * 注入到承载 View。
         * 暂时挂在 PhoneStatusBarView（已验证稳定）；向窗口根挂载的方案
         * 曾导致状态栏窗口内部层级管理异常，改为在宿主内置顶 + 后续按岛
         * 状态联动处理。
         */
        fun inject(start: ViewGroup) {
            if (start.getTag(INJECT_TAG) == true) {
                bringAbove(start)
                return
            }
            val ringView = PowerRingView(start.context)
            val lp = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            start.addView(ringView, lp)
            start.setTag(INJECT_TAG, true)
            ringView.translationZ = TAG_Z
            ringView.bringToFront()
            watchChildren(start, ringView)
            ModuleLog.i("已向 ${start.javaClass.name} 注入 PowerRingView")
        }

        /** 灵动岛等新子 View 加入宿主后，重新把环置顶（加 100ms 防抖避免抖动）。 */
        private fun watchChildren(root: ViewGroup, ringView: View) {
            root.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
                private var scheduled = false

                override fun onChildViewAdded(parent: View?, child: View?) {
                    if (child === ringView) return
                    scheduleBringToFront()
                }

                override fun onChildViewRemoved(parent: View?, child: View?) {}

                private fun scheduleBringToFront() {
                    if (scheduled) return
                    scheduled = true
                    ringView.postDelayed({
                        scheduled = false
                        bringAbove(root)
                    }, 100L)
                }
            })
        }

        private fun bringAbove(root: ViewGroup) {
            for (i in 0 until root.childCount) {
                val v = root.getChildAt(i)
                if (v is PowerRingView) {
                    if (v.z < TAG_Z) v.translationZ = TAG_Z
                    v.bringToFront()
                    return
                }
            }
        }
    }
}
