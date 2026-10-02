package com.powerring.hole.ring

import java.util.concurrent.atomic.AtomicInteger

/**
 * 截图采集并发计数：首个 begin 需要执行隐藏，最后一个 end 需要调度恢复。
 *
 * 同一张截图可能被多条管线先后调 `ImageCaptureImpl.captureDisplay`
 * （xref 实证：全屏控制器与策略处理器各有一处调用），计数保证整段
 * 采集期间窗口持续隐藏，只恢复一次。
 */
class CaptureHideCounter {

    private val active = AtomicInteger(0)

    /** @return true 表示这是首个活跃采集，需要执行隐藏 */
    fun onBegin(): Boolean = active.incrementAndGet() == 1

    /** @return true 表示活跃采集已归零，需要调度恢复 */
    fun onEnd(): Boolean = active.decrementAndGet() == 0
}
