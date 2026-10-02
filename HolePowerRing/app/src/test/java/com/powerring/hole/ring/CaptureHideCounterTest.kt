package com.powerring.hole.ring

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureHideCounterTest {

    @Test
    fun onlyFirstBeginRequestsHide() {
        val counter = CaptureHideCounter()
        assertTrue(counter.onBegin())
        assertFalse(counter.onBegin())
        assertFalse(counter.onBegin())
    }

    @Test
    fun onlyLastEndRequestsRestore() {
        val counter = CaptureHideCounter()
        counter.onBegin()
        counter.onBegin()
        assertFalse(counter.onEnd())
        assertTrue(counter.onEnd())
    }

    @Test
    fun cycleRepeatableAfterZero() {
        val counter = CaptureHideCounter()
        assertTrue(counter.onBegin())
        assertTrue(counter.onEnd())
        assertTrue(counter.onBegin())
        assertTrue(counter.onEnd())
    }
}
