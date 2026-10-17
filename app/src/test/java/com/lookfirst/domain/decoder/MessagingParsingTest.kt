package com.lookfirst.domain.decoder

import com.lookfirst.domain.model.ScanPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessagingParsingTest {
    private fun unknown(raw: String) = (parseOk(raw).payload as? ScanPayload.Unknown)?.declaredType

    @Test
    fun smsWithBody() {
        val s = decode<ScanPayload.Sms>("sms:+33612345678?body=Hello%20world")
        assertEquals("+33612345678", s.number)
        assertEquals("Hello world", s.body)
    }

    @Test
    fun smstoColonForm() {
        val s = decode<ScanPayload.Sms>("SMSTO:+33612345678:Bonjour: ca va ?")
        assertEquals("+33612345678", s.number)
        assertEquals("Bonjour: ca va ?", s.body)
    }

    @Test
    fun smsAmpersandFormAndNoBody() {
        assertEquals("hi", decode<ScanPayload.Sms>("sms:123&body=hi").body)
        val s = decode<ScanPayload.Sms>("SMS:36000")
        assertEquals("36000", s.number)
        assertNull(s.body)
    }

    @Test
    fun smsPremiumShortCodeKeepsThePrefilledBody() {
        val s = decode<ScanPayload.Sms>("sms:81234?body=STOP%20SUBSCRIBE%20OK")
        assertEquals("81234", s.number)
        assertEquals("STOP SUBSCRIBE OK", s.body)
    }

    @Test
    fun smsWithoutNumberIsUnknown() {
        assertEquals("SMS", unknown("sms:"))
        assertEquals("SMS", unknown("smsto::text"))
        assertEquals("SMS", unknown("sms:?body=x"))
    }

    @Test
    fun telNumber() {
        assertEquals("+33123456789", decode<ScanPayload.Tel>("tel:+33123456789").number)
        assertEquals("+33 1 23", decode<ScanPayload.Tel>("TEL:+33%201%2023").number)
    }

    @Test
    fun telUssdCodesAreKeptVerbatim() {
        assertEquals("*21*+33123456789#", decode<ScanPayload.Tel>("tel:*21*+33123456789#").number)
        assertEquals("*#06#", decode<ScanPayload.Tel>("tel:*%2306%23").number)
    }

    @Test
    fun emptyTelIsUnknown() {
        assertEquals("TEL", unknown("tel:"))
    }

    @Test
    fun mailtoWithFields() {
        val m =
            decode<ScanPayload.Mail>(
                "mailto:a@x.example,b@x.example?subject=Hi%20there&body=Line&cc=c@x.example&bcc=d@x.example",
            )
        assertEquals(listOf("a@x.example", "b@x.example"), m.to)
        assertEquals(listOf("c@x.example"), m.cc)
        assertEquals(listOf("d@x.example"), m.bcc)
        assertEquals("Hi there", m.subject)
        assertEquals("Line", m.body)
    }

    @Test
    fun mailtoToParameterIsAddedToRecipients() {
        val m = decode<ScanPayload.Mail>("mailto:a@x.example?to=evil@x.example")
        assertEquals(listOf("a@x.example", "evil@x.example"), m.to)
    }

    @Test
    fun mailtoWithOnlyAQueryAndEmptyMailtoAreHandled() {
        assertEquals("Hi", decode<ScanPayload.Mail>("mailto:?subject=Hi").subject)
        assertEquals("MAILTO", unknown("mailto:"))
    }

    @Test
    fun mailtoWithTooManyParamsIsRejected() {
        assertEquals("MAILTO", unknown("mailto:a@x.example?" + "k=v&".repeat(200)))
    }

    @Test
    fun matmsgForm() {
        val m = decode<ScanPayload.Mail>("MATMSG:TO:a@x.example;SUB:Hello\\; world;BODY:Body: text;;")
        assertEquals(listOf("a@x.example"), m.to)
        assertEquals("Hello; world", m.subject)
        assertEquals("Body: text", m.body)
    }

    @Test
    fun brokenMatmsgIsUnknown() {
        assertEquals("MATMSG", unknown("MATMSG:garbage"))
        assertEquals("MAILTO", unknown("MATMSG:;;"))
    }

    @Test
    fun geoWithQuery() {
        val g = decode<ScanPayload.Geo>("geo:48.8584,2.2945?q=Tour%20Eiffel")
        assertEquals(48.8584, g.lat, 0.0)
        assertEquals(2.2945, g.lon, 0.0)
        assertEquals("Tour Eiffel", g.query)
    }

    @Test
    fun geoWithAltitudeAndUncertainty() {
        val g = decode<ScanPayload.Geo>("GEO:-33.86,151.21,25;u=35")
        assertEquals(-33.86, g.lat, 0.0)
        assertEquals(151.21, g.lon, 0.0)
        assertNull(g.query)
    }

    @Test
    fun geoRejectsOutOfRangeAndNonNumericCoordinates() {
        listOf(
            "geo:91,0",
            "geo:0,181",
            "geo:NaN,0",
            "geo:Infinity,0",
            "geo:1e3,0",
            "geo:0x10,0",
            "geo:abc",
            "geo:1,2,3,4",
            "geo:",
        ).forEach { assertEquals(it, "GEO", unknown(it)) }
    }
}
