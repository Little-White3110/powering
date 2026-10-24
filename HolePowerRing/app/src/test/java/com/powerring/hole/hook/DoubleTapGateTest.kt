package com.powerring.hole.hook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DoubleTapGateTest {

    @Test fun `第一击不是双击`() {
        assertFalse(DoubleTapGate(800, 50f).onTap(1_000, 100f, 100f))
    }

    @Test fun `间隔与位移都在限内算双击`() {
        val g = DoubleTapGate(800, 50f)
        g.onTap(1_000, 100f, 100f)
        assertTrue(g.onTap(1_700, 120f, 110f))
    }

    @Test fun `间隔恰好在边界算双击`() {
        val g = DoubleTapGate(800, 50f)
        g.onTap(1_000, 100f, 100f)
        assertTrue(g.onTap(1_800, 100f, 100f))
    }

    @Test fun `超过间隔不算双击且重新计首击`() {
        val g = DoubleTapGate(800, 50f)
        g.onTap(1_000, 100f, 100f)
        assertFalse(g.onTap(1_900, 100f, 100f))
        assertTrue(g.onTap(2_500, 100f, 100f)) // 与 1900 间隔 600ms
    }

    @Test fun `位移超 slop 不算双击`() {
        val g = DoubleTapGate(800, 50f)
        g.onTap(1_000, 100f, 100f)
        assertFalse(g.onTap(1_200, 200f, 100f))
    }

    @Test fun `双击被消费后第三击不算连击`() {
        val g = DoubleTapGate(800, 50f)
        g.onTap(1_000, 100f, 100f)
        assertTrue(g.onTap(1_500, 100f, 100f))
        assertFalse(g.onTap(1_600, 100f, 100f))
    }
}
