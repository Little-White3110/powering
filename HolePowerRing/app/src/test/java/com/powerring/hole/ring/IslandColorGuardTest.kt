package com.powerring.hole.ring

import org.junit.Assert.assertEquals
import org.junit.Test

class IslandColorGuardTest {

    @Test
    fun opaqueBlackBecomesWhiteWhenIslandShowing() {
        assertEquals(
            IslandColorGuard.WHITE,
            IslandColorGuard.ensureVisible(0xFF000000.toInt(), islandShowing = true, collapseOnIsland = false),
        )
    }

    @Test
    fun darkGrayBecomesWhiteWhenIslandShowing() {
        // 反色动画的深色中间值（≈#262626）同样不可见，应改白
        assertEquals(
            IslandColorGuard.WHITE,
            IslandColorGuard.ensureVisible(0xFF262626.toInt(), islandShowing = true, collapseOnIsland = false),
        )
    }

    @Test
    fun channelBoundaryIsInclusive() {
        // 三通道均恰好 64：近黑，改白
        assertEquals(
            IslandColorGuard.WHITE,
            IslandColorGuard.ensureVisible(0xFF404040.toInt(), islandShowing = true, collapseOnIsland = false),
        )
        // 任一通道 65：不再算近黑，保持原色
        val justAbove = 0xFF414040.toInt()
        assertEquals(
            justAbove,
            IslandColorGuard.ensureVisible(justAbove, islandShowing = true, collapseOnIsland = false),
        )
    }

    @Test
    fun preservesOriginalAlpha() {
        // 半透明黑 → 半透明白，不借机变成不透明
        assertEquals(
            0x80FFFFFF.toInt(),
            IslandColorGuard.ensureVisible(0x80000000.toInt(), islandShowing = true, collapseOnIsland = false),
        )
    }

    @Test
    fun lightAndSaturatedColorsUntouched() {
        val cases = intArrayOf(
            IslandColorGuard.WHITE,           // 已经是白
            0xFFFFFFFF.toInt(),                // 同上
            0xFF277AF7.toInt(),                // 充电蓝（default customColor）
            0xFFE53935.toInt(),                // 低电红
            0xFFFFD700.toInt(),                // 金黄
            0xFF808080.toInt(),                // 中灰
        )
        for (c in cases) {
            assertEquals(c, IslandColorGuard.ensureVisible(c, islandShowing = true, collapseOnIsland = false))
        }
    }

    @Test
    fun disabledWhenIslandNotShowing() {
        assertEquals(
            0xFF000000.toInt(),
            IslandColorGuard.ensureVisible(0xFF000000.toInt(), islandShowing = false, collapseOnIsland = false),
        )
    }

    @Test
    fun disabledWhenCollapseOnIslandEnabled() {
        // 「有岛时隐藏圆环」开着时环本就收起，守卫不介入
        assertEquals(
            0xFF000000.toInt(),
            IslandColorGuard.ensureVisible(0xFF000000.toInt(), islandShowing = true, collapseOnIsland = true),
        )
    }
}
