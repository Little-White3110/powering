package com.powerring.hole.hook

/**
 * 双击判定的纯逻辑状态机（无 Android/Xposed 依赖，JVM 可测）。
 *
 * 输入一次合格的「点按」（时长/位移过滤由调用方完成），输出是否与上一次
 * 构成双击：间隔 ≤ [intervalMs] 且位置偏移 ≤ [slopPx]。双击一旦成立即被
 * 消费，第三击需要重新从「首击」计起，避免三击连发回顶。
 */
class DoubleTapGate(val intervalMs: Long, val slopPx: Float) {

    private var lastTapAt = -1L
    private var lastX = 0f
    private var lastY = 0f

    /** @return true = 本次点按与上一次构成双击（并已消费） */
    fun onTap(atMs: Long, x: Float, y: Float): Boolean {
        val last = lastTapAt
        val isDouble = last > 0 &&
            atMs - last in 0..intervalMs &&
            kotlin.math.hypot(x - lastX, y - lastY) <= slopPx
        if (isDouble) {
            lastTapAt = -1L
        } else {
            lastTapAt = atMs
            lastX = x
            lastY = y
        }
        return isDouble
    }
}
