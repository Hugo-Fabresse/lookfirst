package com.lookfirst.domain.analysis

import com.lookfirst.domain.model.Severity
import com.lookfirst.domain.model.SignalCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlStructureAnalyzerTest {
    private fun codes(raw: String) = UrlStructureAnalyzer.on(raw).codes()

    @Test
    fun plainHttpsUrlHasNoSignal() {
        assertTrue(UrlStructureAnalyzer.on("https://www.example.com/menu?table=12").isEmpty())
        assertTrue(UrlStructureAnalyzer.on("https://example.com:443/a").isEmpty())
        assertTrue(UrlStructureAnalyzer.on("HTTPS://Example.COM:80/a").isEmpty())
    }

    @Test
    fun userinfoShowsTheRealHost() {
        val signals = UrlStructureAnalyzer.on("https://paypal.com@evil-pay.example/login")
        assertEquals(setOf(SignalCode.USERINFO_AT), signals.codes())
        assertEquals("evil-pay.example", signals.of(SignalCode.USERINFO_AT).evidence?.value)
        assertEquals(Severity.DANGEROUS, signals.of(SignalCode.USERINFO_AT).severity)
    }

    @Test
    fun userinfoWithoutDotOrNumberIsSuspiciousOnly() {
        val plain = UrlStructureAnalyzer.on("https://support@evil.example/login")
        assertEquals(Severity.SUSPICIOUS, plain.of(SignalCode.USERINFO_AT).severity)
        val ip = UrlStructureAnalyzer.on("https://2130706433@evil.example/")
        assertEquals(Severity.DANGEROUS, ip.of(SignalCode.USERINFO_AT).severity)
        val dotted = UrlStructureAnalyzer.on("https://user:paypal.com@evil.example/")
        assertEquals(Severity.DANGEROUS, dotted.of(SignalCode.USERINFO_AT).severity)
    }

    @Test
    fun userinfoWithPasswordAndLastAtWins() {
        assertEquals(setOf(SignalCode.USERINFO_AT), codes("https://bank.com:pw@x@evil.example/"))
    }

    @Test
    fun numericHostsAreIpHost() {
        assertTrue(SignalCode.IP_HOST in codes("https://203.0.113.9/pay"))
        assertTrue(SignalCode.IP_HOST in codes("https://[2001:db8::1]/pay"))
        assertTrue(SignalCode.IP_HOST in codes("https://2130706433/"))
        assertTrue(SignalCode.IP_HOST in codes("https://0x7f.1/"))
        assertTrue(SignalCode.IP_HOST in codes("https://0177.0.0.1/"))
    }

    @Test
    fun fullwidthDigitsAreAnIpAddressOnceNormalized() {
        val signals = UrlStructureAnalyzer.on("https://１２７.０.０.１/")
        assertEquals("127.0.0.1", signals.of(SignalCode.IP_HOST).evidence?.value)
        assertEquals(setOf(SignalCode.IP_HOST), signals.codes())
    }

    @Test
    fun normalizedHostsAreNoLongerIncomplete() {
        assertTrue(UrlStructureAnalyzer.on("https://café.example/menu").isEmpty())
        assertEquals(setOf(SignalCode.URL_HOST_ENCODED), codes("https://%70aypal.com/login"))
        assertTrue(SignalCode.ANALYSIS_INCOMPLETE !in codes("https://cafe.example/menu"))
    }

    @Test
    fun hostThatFailsNormalizationStaysIncompleteAndMalformed() {
        val signals = UrlStructureAnalyzer.on("https://a%C3.example/menu")
        assertEquals(Severity.CAUTION, signals.of(SignalCode.ANALYSIS_INCOMPLETE).severity)
        assertEquals(Severity.SUSPICIOUS, signals.of(SignalCode.URL_MALFORMED).severity)
        assertEquals(1, signals.count { it.code == SignalCode.URL_MALFORMED })
        assertTrue(SignalCode.ANALYSIS_INCOMPLETE in codes("https://xn--a-.example/"))
        assertEquals(1, UrlStructureAnalyzer.on("https://exa mple.com/").count { it.code == SignalCode.URL_MALFORMED })
    }

    @Test
    fun shortenerAndUserinfoUseTheNormalizedHost() {
        assertEquals(setOf(SignalCode.SHORTENER), codes("https://ｂｉｔ.ｌｙ/x"))
        assertEquals(setOf(SignalCode.SHORTENER), codes("https://bit.ly\u3002/x"))
        val signals = UrlStructureAnalyzer.on("https://paypal.com@ｅvil.example/")
        assertEquals("evil.example", signals.of(SignalCode.USERINFO_AT).evidence?.value)
    }

    @Test
    fun domainIsNotAnIp() {
        assertTrue(SignalCode.IP_HOST !in codes("https://203.example.com/"))
    }

    @Test
    fun unusualPortIsCautionWithPortAsEvidence() {
        val signals = UrlStructureAnalyzer.on("https://parking-pay.example:8443/zone/12")
        assertEquals(setOf(SignalCode.UNUSUAL_PORT), signals.codes())
        assertEquals("8443", signals.of(SignalCode.UNUSUAL_PORT).evidence?.value)
        assertEquals(Severity.CAUTION, signals.of(SignalCode.UNUSUAL_PORT).severity)
    }

    @Test
    fun plainHttpIsFlaggedButHttpsIsNot() {
        assertEquals(setOf(SignalCode.PLAIN_HTTP), codes("http://example.com/menu"))
        assertTrue(SignalCode.PLAIN_HTTP !in codes("https://example.com/menu"))
    }

    @Test
    fun longUrlThresholdIsExclusive() {
        val prefix = "https://example.com/"
        assertTrue(SignalCode.LONG_URL !in codes(prefix + "a".repeat(LONG_URL_THRESHOLD - prefix.length)))
        assertTrue(SignalCode.LONG_URL in codes(prefix + "a".repeat(LONG_URL_THRESHOLD - prefix.length + 1)))
    }

    @Test
    fun shortenersAreMatchedOnTheirDomainOnly() {
        assertEquals(setOf(SignalCode.SHORTENER), codes("https://bit.ly/3xYz9"))
        assertEquals(setOf(SignalCode.SHORTENER), codes("https://BIT.LY./3xYz9"))
        assertEquals(setOf(SignalCode.SHORTENER), codes("https://t.co/AbCdEf"))
        assertEquals(setOf(SignalCode.SHORTENER), codes("https://go.bit.ly/x"))
        assertTrue(codes("https://notbit.ly/x").isEmpty())
        assertTrue(codes("https://bit.ly.evil.example/x").isEmpty())
        assertTrue(codes("https://example.com/bit.ly").isEmpty())
    }

    @Test
    fun decoderOddFormatsAreCaution() {
        assertEquals(setOf(SignalCode.URL_ODD_FORMAT), codes("example.com:443/pay"))
        assertEquals(setOf(SignalCode.URL_ODD_FORMAT), codes("https:\\\\example.com\\pay"))
        assertEquals(setOf(SignalCode.URL_ODD_FORMAT), codes("https:example.com/pay"))
        assertEquals(setOf(SignalCode.URL_ODD_FORMAT), codes("https:////example.com/pay"))
        val signals = UrlStructureAnalyzer.on("example.com:443/pay")
        assertEquals(Severity.CAUTION, signals.of(SignalCode.URL_ODD_FORMAT).severity)
    }

    @Test
    fun percentEncodedHostIsSuspicious() {
        val signals = UrlStructureAnalyzer.on("https://%70aypal.com/login")
        assertEquals(Severity.SUSPICIOUS, signals.of(SignalCode.URL_HOST_ENCODED).severity)
    }

    @Test
    fun malformedUrlsAreSuspicious() {
        assertTrue(SignalCode.URL_MALFORMED in codes("https://example.com:99999/"))
        assertTrue(SignalCode.URL_MALFORMED in codes("https://?x=1"))
        assertTrue(SignalCode.URL_MALFORMED in codes("https://exa mple.com/"))
        assertTrue(SignalCode.URL_MALFORMED in codes("https://a<b.example/"))
        assertTrue(SignalCode.URL_MALFORMED in codes("https://[1:2:3]/"))
        assertTrue(SignalCode.URL_MALFORMED in codes("https://[1::2::3]/"))
        assertFalse(SignalCode.URL_MALFORMED in codes("https://[2001:db8::1]/"))
    }

    @Test
    fun combinedQuishingUrl() {
        val signals = UrlStructureAnalyzer.on("http://bank.com@203.0.113.9:8080/login")
        assertEquals(
            setOf(SignalCode.USERINFO_AT, SignalCode.IP_HOST, SignalCode.UNUSUAL_PORT, SignalCode.PLAIN_HTTP),
            signals.codes(),
        )
    }

    @Test
    fun otherPayloadsAreIgnored() {
        assertTrue(UrlStructureAnalyzer.on("WIFI:T:nopass;S:x;;").isEmpty())
        assertTrue(UrlStructureAnalyzer.on("hello world").isEmpty())
    }
}
