package com.powerring.hole.ring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomColorsTest {

    @Test
    fun encodeRangesUsesDashColonHexForm() {
        val encoded = CustomColors.encodeRanges(
            listOf(ColorRange(1, 20, 0xFFFFD700.toInt()), ColorRange(21, 80, 0xFF277AF7.toInt())),
        )
        assertEquals("1-20:FFFFD700,21-80:FF277AF7", encoded)
    }

    @Test
    fun decodeRoundTripsEveryColor() {
        val ranges = listOf(
            ColorRange(0, 0, 0xFF000000.toInt()),
            ColorRange(1, 20, 0x80FFD700.toInt()),
            ColorRange(21, 100, 0xFF277AF7.toInt()),
        )
        assertEquals(ranges, CustomColors.decodeRanges(CustomColors.encodeRanges(ranges)))
    }

    @Test
    fun decodeNullAndEmptyGiveEmptyList() {
        assertTrue(CustomColors.decodeRanges(null).isEmpty())
        assertTrue(CustomColors.decodeRanges("").isEmpty())
    }

    @Test
    fun decodeDropsMalformedSegmentsButKeepsGoodOnes() {
        val raw = "1-20:FFFFD700,broken,,30-x:FF000000,21-40:ZZZZZZZZ,5-1:FF112233,51-101:FF112233"
        assertEquals(listOf(ColorRange(1, 20, 0xFFFFD700.toInt())), CustomColors.decodeRanges(raw))
    }

    @Test
    fun decodeStopsAtMaxRanges() {
        val raw = (1..20).joinToString(",") { "$it-$it:FF0000AA" }
        assertEquals(CustomColors.MAX_RANGES, CustomColors.decodeRanges(raw).size)
    }

    @Test
    fun colorForLevelReturnsFirstMatchInListOrder() {
        val ranges = listOf(ColorRange(1, 50, 0xFFFFD700.toInt()), ColorRange(10, 20, 0xFF277AF7.toInt()))
        assertEquals(0xFFFFD700.toInt(), CustomColors.colorForLevel(ranges, 15))
        assertEquals(0, CustomColors.colorForLevel(ranges, 0))
        assertEquals(0, CustomColors.colorForLevel(ranges, 51))
        assertEquals(0, CustomColors.colorForLevel(emptyList(), 50))
    }
}
