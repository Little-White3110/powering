package com.powerring.hole.ring

import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Test

class SecureFlagTest {

    @Test
    fun enabledAddsSecureFlag() {
        assertEquals(
            WindowManager.LayoutParams.FLAG_SECURE,
            secureFlagFor(0, hideOnScreenshot = true),
        )
    }

    @Test
    fun disabledClearsSecureFlag() {
        val withSecure = WindowManager.LayoutParams.FLAG_SECURE or 0x40
        assertEquals(0x40, secureFlagFor(withSecure, hideOnScreenshot = false))
    }

    @Test
    fun otherFlagsArePreserved() {
        val base = 0x40 or 0x10
        assertEquals(
            base or WindowManager.LayoutParams.FLAG_SECURE,
            secureFlagFor(base, hideOnScreenshot = true),
        )
    }

    @Test
    fun enabledIsIdempotent() {
        val once = secureFlagFor(0x40, hideOnScreenshot = true)
        assertEquals(once, secureFlagFor(once, hideOnScreenshot = true))
    }

    @Test
    fun defaultConfigEnablesHideOnScreenshot() {
        assertEquals(true, RingConfig.DEFAULT.hideOnScreenshot)
    }
}
