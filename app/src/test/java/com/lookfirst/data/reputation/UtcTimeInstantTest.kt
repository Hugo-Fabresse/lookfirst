package com.lookfirst.data.reputation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UtcTimeInstantTest {
    private val may2024 = 1_714_566_645_000L

    @Test
    fun `z, offsets and fractions all name the same instant`() {
        assertEquals(may2024, UtcTime.parseInstant("2024-05-01T12:30:45Z"))
        assertEquals(may2024, UtcTime.parseInstant("2024-05-01t12:30:45z"))
        assertEquals(may2024, UtcTime.parseInstant("2024-05-01T12:30:45.123Z"))
        assertEquals(may2024, UtcTime.parseInstant("2024-05-01T12:30:45.123456789Z"))
        assertEquals(may2024, UtcTime.parseInstant("2024-05-01T14:30:45+02:00"))
        assertEquals(may2024, UtcTime.parseInstant("2024-05-01T07:00:45-05:30"))
        assertEquals(may2024, UtcTime.parseInstant("2024-05-01T12:30:45+00:00"))
        assertEquals(may2024, UtcTime.parseInstant("2024-05-01T12:30:45.5+00:00"))
    }

    @Test
    fun `calendar edges`() {
        assertEquals(0L, UtcTime.parseInstant("1970-01-01T00:00:00Z"))
        assertEquals(473_385_600_000L, UtcTime.parseInstant("1985-01-01T00:00:00Z"))
        assertEquals(951_782_400_000L, UtcTime.parseInstant("2000-02-29T00:00:00Z"))
        assertEquals(1_792_238_400_000L, UtcTime.parseInstant("2026-10-17T12:00:00Z"))
        // An offset can move the date across a day.
        assertEquals(UtcTime.parseInstant("2024-05-01T00:00:00Z"), UtcTime.parseInstant("2024-05-01T02:00:00+02:00"))
        assertEquals(UtcTime.parseInstant("2024-04-30T23:00:00Z"), UtcTime.parseInstant("2024-05-01T00:00:00+01:00"))
    }

    @Test
    fun `anything that is not an rfc 3339 date-time is refused`() {
        listOf(
            "",
            "2024-05-01",
            "2024-05-01T12:30:45",
            "2024-05-01 12:30:45Z",
            "2024-5-1T12:30:45Z",
            "2024-05-01T12:30Z",
            "2024-05-01T12:30:45.Z",
            "2024-05-01T12:30:45.1234567890Z",
            "2024-05-01T12:30:45+0200",
            "2024-05-01T12:30:45+02",
            "2024-05-01T12:30:45+24:00",
            "2024-05-01T12:30:45+02:60",
            "2024-05-01T12:30:45*02:00",
            "2024-05-01T12:30:45ZZ",
            "2024-05-01T12:30:45Z ",
            " 2024-05-01T12:30:45Z",
            "2024-13-01T12:30:45Z",
            "2024-00-01T12:30:45Z",
            "2024-02-30T12:30:45Z",
            "2023-02-29T12:30:45Z",
            "2024-04-31T12:30:45Z",
            "2024-05-01T24:00:00Z",
            "2024-05-01T12:60:00Z",
            "2024-05-01T12:30:61Z",
            "1969-12-31T23:59:59Z",
            "3000-01-01T00:00:00Z",
            "２０２４-05-01T12:30:45Z",
            "2024-05-01T12:30:45Z\u0000",
            "x".repeat(10_000),
            "9".repeat(10_000),
        ).forEach { assertNull("accepted: $it", UtcTime.parseInstant(it)) }
    }
}
