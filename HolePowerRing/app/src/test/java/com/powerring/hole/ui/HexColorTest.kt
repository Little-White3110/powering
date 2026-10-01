package com.powerring.hole.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HexColorTest {

    @Test
    fun parseSixDigitAddsOpaqueAlpha() {
        assertEquals(0xFF277AF7.toInt(), HexColor.parse("277AF7"))
        assertEquals(0xFF277AF7.toInt(), HexColor.parse("#277af7"))
    }

    @Test
    fun parseThreeDigitExpandsPerChannel() {
        assertEquals(0xFFFFFFFF.toInt(), HexColor.parse("FFF"))
        assertEquals(0xFFFF77DD.toInt(), HexColor.parse("#f7d"))
    }

    @Test
    fun parseEightDigitKeepsUserAlpha() {
        assertEquals(0x80FFD700.toInt(), HexColor.parse("80FFD700"))
    }

    @Test
    fun parseRejectsWrongLengthOrIllegalChars() {
        assertNull(HexColor.parse(""))
        assertNull(HexColor.parse("277A"))
        assertNull(HexColor.parse("277AF"))
        assertNull(HexColor.parse("277AF7G"))
        assertNull(HexColor.parse("#"))
    }

    @Test
    fun formatHidesOpaqueAlphaAndKeepsTransparency() {
        assertEquals("277AF7", HexColor.format(0xFF277AF7.toInt()))
        assertEquals("80FFD700", HexColor.format(0x80FFD700.toInt()))
        assertEquals("000000", HexColor.format(0xFF000000.toInt()))
    }

    @Test
    fun normalizeInputStripsNonHexAndCapsLength() {
        assertEquals("277AF7", HexColor.normalizeInput("27 7a-f7"))
        assertEquals("#FFD700", HexColor.normalizeInput("#FFD700"))
        // `#` + 8 位必须完整保留：截到 8 个字符会让带 # 的 AARRGGBB 打不完
        assertEquals("#FFD70000", HexColor.normalizeInput("#FFD70000"))
        assertEquals("#FFD70000", HexColor.normalizeInput("#FFD70000AA"))
        assertEquals("FF", HexColor.normalizeInput("zzFF"))
    }

    @Test
    fun parseAcceptsHashPrefixedEightDigit() {
        assertEquals(0xFFD70000.toInt(), HexColor.parse("#FFD70000"))
        assertEquals(0xFF277AF7.toInt(), HexColor.parse("#277AF7"))
    }

    @Test
    fun formatAndParseRoundTrip() {
        for (argb in listOf(0xFF277AF7.toInt(), 0x80FFD700.toInt(), 0xFF000000.toInt())) {
            assertEquals(argb, HexColor.parse(HexColor.format(argb)))
        }
    }
}
