package com.lookfirst.data.network

import com.lookfirst.data.reputation.UtcTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RdapResponseTest {
    private val now = UtcTime.parseInstant("2026-10-17T12:00:00Z")!!

    private fun read(json: String, domain: String = "example.com", at: Long = now): Long? =
        RdapResponse.registrationOf(json.toByteArray(), domain, at)

    private fun instant(text: String) = UtcTime.parseInstant(text)

    private fun events(vararg items: String) =
        """{"objectClassName":"domain","ldhName":"EXAMPLE.com","events":[${items.joinToString(",")}]}"""

    private fun event(action: String, date: String) = """{"eventAction":"$action","eventDate":"$date"}"""

    @Test
    fun `the registration event gives the date`() {
        val json =
            events(event("last changed", "2026-09-01T00:00:00Z"), event("registration", "2026-10-10T08:30:00.000Z"))
        assertEquals(instant("2026-10-10T08:30:00Z"), read(json))
    }

    @Test
    fun `an offset is applied`() {
        assertEquals(instant("2020-01-01T00:00:00Z"), read(events(event("registration", "2020-01-01T02:00:00+02:00"))))
    }

    @Test
    fun `with several registration events the latest counts`() {
        val json = events(
            event("registration", "2010-01-01T00:00:00Z"),
            event("registration", "2026-10-01T00:00:00Z"),
            event("registration", "not a date"),
        )
        assertEquals(instant("2026-10-01T00:00:00Z"), read(json))
    }

    @Test
    fun `no registration event, or none with a usable date, gives nothing`() {
        assertNull(read(events()))
        assertNull(
            read(events(event("expiration", "2030-01-01T00:00:00Z"), event("last changed", "2026-01-01T00:00:00Z"))),
        )
        assertNull(read(events(event("registration", "yesterday"))))
        assertNull(read(events(event("registration", ""))))
        assertNull(read("""{"objectClassName":"domain","ldhName":"example.com"}"""))
        assertNull(read("""{"objectClassName":"domain","ldhName":"example.com","events":"registration"}"""))
        assertNull(read("""{"objectClassName":"domain","ldhName":"example.com","events":[1,"x",null,[]]}"""))
        assertNull(
            read("""{"objectClassName":"domain","ldhName":"example.com","events":[{"eventAction":"registration"}]}"""),
        )
        assertNull(
            read(
                """{"objectClassName":"domain","ldhName":"example.com","events":""" +
                    """[{"eventAction":1,"eventDate":"2020-01-01T00:00:00Z"}]}""",
            ),
        )
        assertNull(
            read(
                """{"objectClassName":"domain","ldhName":"example.com","events":""" +
                    """[{"eventAction":"Registration","eventDate":"2020-01-01T00:00:00Z"}]}""",
            ),
        )
    }

    @Test
    fun `an implausible date is not used`() {
        assertNull(read(events(event("registration", "1969-12-31T23:59:59Z"))))
        assertNull(read(events(event("registration", "1984-12-31T23:59:59Z"))))
        assertNull(read(events(event("registration", "2026-10-19T12:00:00Z"))))
        assertNull(read(events(event("registration", "2999-01-01T00:00:00Z"))))
        // A clock a little behind the registry's is fine.
        assertEquals(instant("2026-10-18T06:00:00Z"), read(events(event("registration", "2026-10-18T06:00:00Z"))))
        assertEquals(instant("1985-01-01T00:00:00Z"), read(events(event("registration", "1985-01-01T00:00:00Z"))))
    }

    @Test
    fun `an answer about another object or another domain is refused`() {
        val date = event("registration", "2020-01-01T00:00:00Z")
        assertNull(read("""{"objectClassName":"nameserver","events":[$date]}"""))
        assertNull(read("""{"objectClassName":"domain","ldhName":"other.com","events":[$date]}"""))
        assertNull(read("""{"objectClassName":"domain","ldhName":"example.com.evil.net","events":[$date]}"""))
        assertNull(read("""{"objectClassName":"domain","ldhName":5,"events":[$date]}"""))
        assertNull(read("""{"objectClassName":"domain","ldhName":"Example.COM.","events":[$date]}"""))
        assertEquals(
            instant("2020-01-01T00:00:00Z"),
            read("""{"objectClassName":"domain","ldhName":"Example.COM","events":[$date]}"""),
        )
    }

    @Test
    fun `both the object class and the name are required`() {
        val date = event("registration", "2020-01-01T00:00:00Z")
        assertNull(read("""{"events":[$date]}"""))
        assertNull(read("""{"objectClassName":"domain","events":[$date]}"""))
        assertNull(read("""{"ldhName":"example.com","events":[$date]}"""))
        assertNull(read("""{"objectClassName":"Domain","ldhName":"example.com","events":[$date]}"""))
        assertNull(read("""{"objectClassName":["domain"],"ldhName":"example.com","events":[$date]}"""))
        assertNull(read("""{"objectClassName":"domain","ldhName":null,"events":[$date]}"""))
        assertNull(read("""{"objectClassName":"domain","ldhName":"","events":[$date]}"""))
    }

    @Test
    fun `things that are not json, or not an object, are refused`() {
        listOf("", "null", "[]", "\"x\"", "{", "{\"events\":[}", "<html>", "\u0000", "{\"a\":1,\"a\":2}").forEach {
            assertNull("accepted: $it", read(it))
        }
        assertNull(RdapResponse.registrationOf(byteArrayOf(0xC3.toByte(), 0x28), "example.com", now))
        assertNull(RdapResponse.registrationOf(ByteArray(0), "example.com", now))
    }

    @Test
    fun `fractions in fields nobody reads do not matter, other noise is ignored`() {
        val json =
            """{"rdapConformance":["rdap_level_0"],"objectClassName":"domain","ldhName":"example.com","x":1.5e3,""" +
                """"notices":[{"title":"t","description":["${"d".repeat(5_000)}"]}],""" +
                """"events":[{"eventAction":"registration","eventDate":"2020-01-01T00:00:00Z","links":[]}]}"""
        assertEquals(instant("2020-01-01T00:00:00Z"), read(json))
    }

    @Test
    fun `a deeply nested or huge answer is refused quickly`() {
        val deep = """{"objectClassName":"domain","x":${"[".repeat(500)}${"]".repeat(500)},"events":[]}"""
        val wide = """{"objectClassName":"domain","x":[${"1,".repeat(50_000)}1],"events":[]}"""
        val long = """{"objectClassName":"domain","x":"${"a".repeat(100_000)}"}"""
        val started = System.nanoTime()
        listOf(deep, wide, long, "[".repeat(200_000), "{\"a\":".repeat(100_000)).forEach { assertNull(read(it)) }
        assertTrue("took ${(System.nanoTime() - started) / 1_000_000} ms", System.nanoTime() - started < 5_000_000_000L)
    }

    @Test
    fun `events beyond the cap are not looked at`() {
        val filler = (1..300).joinToString(",") { event("last changed", "2020-01-01T00:00:00Z") }
        val json = """{"objectClassName":"domain","ldhName":"example.com","events":[$filler,${event(
            "registration",
            "2020-01-01T00:00:00Z",
        )}]}"""
        assertNull(read(json))
    }
}
