package com.lookfirst.data.reputation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Random

class HttpDateTest {
    @Test
    fun aStrictImfFixdateRoundTrips() {
        val millis = HttpDate.parse("Sat, 17 Oct 2026 19:20:06 GMT")!!
        assertEquals(1_792_264_806_000L, millis)
        assertEquals("Sat, 17 Oct 2026 19:20:06 GMT", HttpDate.format(millis))
        val newYear = HttpDate.parse("Thu, 01 Jan 2026 00:00:00 GMT")!!
        assertEquals("Thu, 01 Jan 2026 00:00:00 GMT", HttpDate.format(newYear))
    }

    @Test
    fun everyOtherSpellingIsRefused() {
        listOf(
            "",
            "Saturday, 17-Oct-26 19:20:06 GMT",
            "Sat Oct 17 19:20:06 2026",
            "Sun, 17 Oct 2026 19:20:06 GMT",
            "Sat, 17 Oct 2026 19:20:06 UTC",
            "Sat, 17 Oct 2026 19:20:06 gmt",
            "sat, 17 Oct 2026 19:20:06 GMT",
            "Sat, 17 oct 2026 19:20:06 GMT",
            "Sat, 7 Oct 2026 19:20:06 GMT",
            "Sat, 17 Oct 2026 19:20:60 GMT",
            "Sat, 17 Oct 2026 24:20:06 GMT",
            "Sat, 31 Feb 2026 19:20:06 GMT",
            "Sat, 17 Oct 1999 19:20:06 GMT",
            "Sat, 17 Oct 2026 19:20:06 GMT ",
            " Sat, 17 Oct 2026 19:20:06 GMT",
            "Sat, 17 Oct 2026 19:2٣:06 GMT",
            "Sat, 17 Oct 2026 19:20:06 GMT; id=1",
        ).forEach { assertNull("accepted: $it", HttpDate.parse(it)) }
    }

    @Test
    fun noInputMakesItThrow() {
        val random = Random(5)
        repeat(500) {
            val text = String(CharArray(random.nextInt(40)) { (random.nextInt(95) + 32).toChar() })
            HttpDate.parse(text)
        }
    }
}
