package com.powerring.hole.ring

import org.junit.Assert.assertEquals
import org.junit.Test

class RingCollapseTest {

    @Test
    fun `all paths off returns zero`() {
        assertEquals(
            0f,
            RingCollapseLogic.target(
                statusBarCollapsed = false, collapseOnImmersive = false,
                islandShowing = false, collapseOnIsland = false,
                shadeFraction = 0f, collapseOnShade = false,
            ),
            0.0001f,
        )
    }

    @Test
    fun `immersive path collapses fully when enabled`() {
        assertEquals(
            1f,
            RingCollapseLogic.target(
                statusBarCollapsed = true, collapseOnImmersive = true,
                islandShowing = false, collapseOnIsland = false,
                shadeFraction = 0f, collapseOnShade = true,
            ),
            0.0001f,
        )
    }

    @Test
    fun `island path collapses fully when enabled`() {
        assertEquals(
            1f,
            RingCollapseLogic.target(
                statusBarCollapsed = false, collapseOnImmersive = true,
                islandShowing = true, collapseOnIsland = true,
                shadeFraction = 0.2f, collapseOnShade = true,
            ),
            0.0001f,
        )
    }

    @Test
    fun `shade fraction is followed when enabled`() {
        assertEquals(
            0.6f,
            RingCollapseLogic.target(
                statusBarCollapsed = false, collapseOnImmersive = true,
                islandShowing = false, collapseOnIsland = true,
                shadeFraction = 0.6f, collapseOnShade = true,
            ),
            0.0001f,
        )
    }

    @Test
    fun `shade switch off ignores fraction`() {
        assertEquals(
            0f,
            RingCollapseLogic.target(
                statusBarCollapsed = false, collapseOnImmersive = true,
                islandShowing = false, collapseOnIsland = true,
                shadeFraction = 0.9f, collapseOnShade = false,
            ),
            0.0001f,
        )
    }

    @Test
    fun `shade takes the strongest path`() {
        // 沉浸已收起（1f），面板只拖到 0.3f：应保持 1f，不能把环弹回来
        assertEquals(
            1f,
            RingCollapseLogic.target(
                statusBarCollapsed = true, collapseOnImmersive = true,
                islandShowing = false, collapseOnIsland = false,
                shadeFraction = 0.3f, collapseOnShade = true,
            ),
            0.0001f,
        )
    }

    @Test
    fun `fraction out of range is clamped`() {
        assertEquals(
            1f,
            RingCollapseLogic.target(
                statusBarCollapsed = false, collapseOnImmersive = false,
                islandShowing = false, collapseOnIsland = false,
                shadeFraction = 1.7f, collapseOnShade = true,
            ),
            0.0001f,
        )
        assertEquals(
            0f,
            RingCollapseLogic.target(
                statusBarCollapsed = false, collapseOnImmersive = false,
                islandShowing = false, collapseOnIsland = false,
                shadeFraction = -0.5f, collapseOnShade = true,
            ),
            0.0001f,
        )
    }

    @Test
    fun `tiny change is ignored but a real move is accepted`() {
        assertEquals(false, RingCollapseLogic.shouldEmit(0.50f, 0.51f))
        assertEquals(true, RingCollapseLogic.shouldEmit(0.50f, 0.53f))
        assertEquals(true, RingCollapseLogic.shouldEmit(0.50f, 0.00f))
    }
}
