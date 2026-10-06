package com.powerring.hole.ring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CutoutMathTest {

    // ---- pickCutoutRect：多挖孔里挑当前方向真正的挖孔 ----

    @Test
    fun `pickCutoutRect prefers rect closest to screen edge`() {
        val top = RectI(900, 0, 972, 144) // 贴顶
        val far = RectI(500, 2000, 572, 2144) // 离四边都远
        assertEquals(top, CutoutMath.pickCutoutRect(listOf(far, top), 1080, 2400))
    }

    @Test
    fun `pickCutoutRect tie picks larger area`() {
        // 同为贴顶（最小边距都是 0）：面积大者胜
        val wide = RectI(400, 0, 680, 144)
        val narrow = RectI(500, 0, 572, 144)
        assertEquals(wide, CutoutMath.pickCutoutRect(listOf(narrow, wide), 1080, 2400))
    }

    @Test
    fun `pickCutoutRect filters empty rects`() {
        val empty = RectI(10, 10, 10, 10)
        val real = RectI(900, 0, 972, 144)
        assertEquals(real, CutoutMath.pickCutoutRect(listOf(empty, real), 1080, 2400))
        assertNull(CutoutMath.pickCutoutRect(listOf(empty), 1080, 2400))
    }

    @Test
    fun `pickCutoutRect returns null for empty list`() {
        assertNull(CutoutMath.pickCutoutRect(emptyList(), 1080, 2400))
    }

    // ---- toViewLocal：屏幕坐标 → View 局部坐标 ----

    @Test
    fun `toViewLocal passes through when loc is zero and scale is one`() {
        val rect = RectI(900, 0, 972, 144)
        val local = CutoutMath.toViewLocal(rect, 0, 0, 1f, 1080f, 200f)!!
        assertEquals(900f, local.left, 0.001f)
        assertEquals(0f, local.top, 0.001f)
        assertEquals(972f, local.right, 0.001f)
        assertEquals(144f, local.bottom, 0.001f)
        assertFalse(local.fellBack)
    }

    @Test
    fun `toViewLocal scales edges by half`() {
        val rect = RectI(100, 20, 300, 120)
        val local = CutoutMath.toViewLocal(rect, 0, 0, 0.5f, 1000f, 1000f)!!
        assertEquals(50f, local.left, 0.001f)
        assertEquals(10f, local.top, 0.001f)
        assertEquals(150f, local.right, 0.001f)
        assertEquals(60f, local.bottom, 0.001f)
        assertFalse(local.fellBack)
    }

    @Test
    fun `toViewLocal falls back to scaled rect when offset pushes it out of view`() {
        // 状态栏窗口与屏幕原点重合的机型：locY 很大，差值换算把矩形推出视图
        // local = (900,-300,972,-156)，bottom<0 与视图无交集 → 退回 scaled
        val rect = RectI(900, 0, 972, 144)
        val local = CutoutMath.toViewLocal(rect, 0, 300, 1f, 1080f, 200f)!!
        assertEquals(0f, local.top, 0.001f)
        assertEquals(144f, local.bottom, 0.001f)
        assertTrue(local.fellBack)
    }

    @Test
    fun `toViewLocal returns null when neither local nor scaled intersects`() {
        val rect = RectI(900, 0, 972, 144)
        // 缩放后矩形整体在视图右边界之外
        assertNull(CutoutMath.toViewLocal(rect, 0, 0, 1f, 500f, 200f))
    }

    // ---- holeFromLocal：圆心取中心、半径取短边一半 ----

    @Test
    fun `holeFromLocal takes center and half of the short side`() {
        // 本机竖长安全区：72×144px，环与灵动岛小岛胶囊对齐
        val local = LocalRect(900f, 0f, 972f, 144f, fellBack = false)
        val hole = CutoutMath.holeFromLocal(local)
        assertEquals(936f, hole.cx, 0.001f)
        assertEquals(72f, hole.cy, 0.001f)
        assertEquals(36f, hole.holeRadius, 0.001f)
    }

    // ---- manualHole：手动孔径模式 ----

    @Test
    fun `manualHole centers horizontally and sits slightly below the top`() {
        val hole = CutoutMath.manualHole(1080f, 100f)
        assertEquals(540f, hole.cx, 0.001f)
        assertEquals(50f, hole.holeRadius, 0.001f)
        assertEquals(50f * 1.15f, hole.cy, 0.001f)
    }

    @Test
    fun `manualHole guards non positive diameter`() {
        val hole = CutoutMath.manualHole(1080f, 0f)
        assertEquals(540f, hole.cx, 0.001f)
        assertEquals(0f, hole.cy, 0.001f)
        assertEquals(0f, hole.holeRadius, 0.001f)
    }

    // ---- clampRingRadius：越界收敛、过小抬升、极小视图兜底 ----

    @Test
    fun `clampRingRadius keeps valid radius unchanged`() {
        // 圆心 (540,72)：limit = min(540,540,72,128) - 0.5 - 2 = 69.5，minR = 27
        val r = CutoutMath.clampRingRadius(540f, 72f, 1080f, 200f, 40f, 4f, 36f)
        assertEquals(40f, r, 0.001f)
    }

    @Test
    fun `clampRingRadius converges oversized radius to limit`() {
        val r = CutoutMath.clampRingRadius(540f, 72f, 1080f, 200f, 120f, 4f, 36f)
        assertEquals(69.5f, r, 0.001f)
    }

    @Test
    fun `clampRingRadius lifts undersized radius to minimum`() {
        val r = CutoutMath.clampRingRadius(540f, 72f, 1080f, 200f, 5f, 4f, 36f)
        assertEquals(27f, r, 0.001f)
    }

    @Test
    fun `clampRingRadius returns minimum when view is tiny`() {
        // 视图极小（limit=8.5 < minR=27）：优先保证包住挖孔
        val r = CutoutMath.clampRingRadius(10f, 10f, 20f, 20f, 8f, 2f, 36f)
        assertEquals(27f, r, 0.001f)
    }

    // ---- RetryBudget ----

    @Test
    fun `retryBudget allows max attempts then refuses`() {
        val budget = RetryBudget()
        repeat(RetryBudget.MAX_ATTEMPTS) { assertTrue(budget.tryConsume()) }
        assertFalse(budget.tryConsume())
    }

    @Test
    fun `retryBudget reset restores allowance`() {
        val budget = RetryBudget()
        repeat(RetryBudget.MAX_ATTEMPTS) { budget.tryConsume() }
        assertFalse(budget.tryConsume())
        budget.reset()
        assertTrue(budget.tryConsume())
    }
}
