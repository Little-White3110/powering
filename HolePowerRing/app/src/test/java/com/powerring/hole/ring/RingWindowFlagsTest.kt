package com.powerring.hole.ring

import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 环窗口 flags 的守卫用例。
 *
 * `FLAG_SECURE` 在本机挡不住特权截图（可行性分析报告 §17.2），只会让 dumpsys 里
 * 多一个干扰位，所以钉在「恒不含 SECURE」。它**不是**点按劫持拦截的成因（§19 实测
 * 去掉后拦截照旧）——那件事由 `applyTrustedOverlayFlag` 负责，判据是
 * `dumpsys window windows` 出现 `pfl=TRUSTED_OVERLAY`，需要真机核对，无法用 JVM 单测覆盖。
 */
class RingWindowFlagsTest {

    @Test
    fun neverCarriesSecureFlag() {
        assertFalse(
            "环窗口不该带 FLAG_SECURE：本机特权截图无视它（报告 §17.2），留着只增干扰",
            RingWindowController.RING_WINDOW_FLAGS and
                WindowManager.LayoutParams.FLAG_SECURE != 0,
        )
    }

    @Test
    fun staysNonTouchableAndUnfocused() {
        val flags = RingWindowController.RING_WINDOW_FLAGS
        assertTrue(
            flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0,
        )
        assertTrue(
            flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0,
        )
    }

    @Test
    fun defaultConfigEnablesHideOnScreenshot() {
        assertEquals(true, RingConfig.DEFAULT.hideOnScreenshot)
    }
}
