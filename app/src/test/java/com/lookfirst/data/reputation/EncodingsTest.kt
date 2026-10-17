package com.lookfirst.data.reputation

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class EncodingsTest {
    @Test
    fun hexRoundTripAndStrictness() {
        assertEquals("00ff10", Hex.encode(byteArrayOf(0, -1, 16)))
        assertArrayEquals(byteArrayOf(0, -1, 16), Hex.decodeLower("00ff10"))
        assertNull(Hex.decodeLower("00FF10"))
        assertNull(Hex.decodeLower("0"))
        assertFalse(Hex.isLowerHex("abc", 4))
    }
}
