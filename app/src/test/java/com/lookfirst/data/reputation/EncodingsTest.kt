package com.lookfirst.data.reputation

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.Random

class EncodingsTest {
    @Test
    fun strictBase64MatchesTheJdkOnValidInput() {
        val random = Random(7)
        repeat(300) { length ->
            val bytes = ByteArray(length % 90).also(random::nextBytes)
            val text = Base64.getEncoder().encodeToString(bytes)
            if (text.isNotEmpty()) assertArrayEquals(bytes, StrictBase64.decode(text))
        }
    }

    @Test
    fun strictBase64RefusesDeviations() {
        listOf("", "AAA", "AA=A", "AAAA\n", "AA A", "AAAA====", "A===", "AB==", "-_-_", "AAAA" + "A".repeat(5000))
            .forEach { assertNull("accepted: ${it.take(20)}", StrictBase64.decode(it)) }
        // Non-canonical trailing bits: "AB==" decodes to a byte with leftover bits set.
        assertNull(StrictBase64.decode("QR=="))
    }

    @Test
    fun hexRoundTripAndStrictness() {
        assertEquals("00ff10", Hex.encode(byteArrayOf(0, -1, 16)))
        assertArrayEquals(byteArrayOf(0, -1, 16), Hex.decodeLower("00ff10"))
        assertNull(Hex.decodeLower("00FF10"))
        assertNull(Hex.decodeLower("0"))
        assertFalse(Hex.isLowerHex("abc", 4))
    }

    @Test
    fun minuteStampsAreExactOrNull() {
        assertEquals(1_767_225_600_000L, UtcTime.parseMinuteStamp("2026-01-01T00:00:00Z"))
        assertEquals(1_767_225_600_000L + 60_000, UtcTime.parseMinuteStamp("2026-01-01T00:01:00Z"))
        assertEquals(0L, UtcTime.midnight(1970, 1, 1))
        assertEquals(BundleLimits.versionOrigin, UtcTime.midnight(2026, 1, 1))
        assertTrue(UtcTime.parseMinuteStamp("2028-02-29T23:59:00Z") != null)
        listOf(
            "2026-01-01T00:00:01Z", "2026-01-01T00:00:00+00:00", "2026-01-01 00:00:00Z", "2026-13-01T00:00:00Z",
            "2026-02-29T00:00:00Z", "2026-04-31T00:00:00Z", "2026-01-01T24:00:00Z", "2026-01-01T00:60:00Z",
            "2026-1-01T00:00:00Z", "", "26-01-01T00:00:00Z", "2026-01-01T00:00:00z", "٢026-01-01T00:00:00Z",
        ).forEach { assertNull("accepted: $it", UtcTime.parseMinuteStamp(it)) }
    }

    @Test
    fun versionIsMinutesSince2026() {
        assertEquals(0L, BundleBuilder.version("2026-01-01T00:00:00Z"))
        assertEquals(1_440L, BundleBuilder.version("2026-01-02T00:00:00Z"))
    }
}
