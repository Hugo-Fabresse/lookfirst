package com.lookfirst.domain.open

import com.lookfirst.domain.decoder.decode
import com.lookfirst.domain.model.ScanPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CanonicalUrlTest {
    private fun canonical(raw: String): CanonicalUrl? = CanonicalUrl.of(decode<ScanPayload.Url>(raw))

    private fun value(raw: String): String = checkNotNull(canonical(raw)) { "no canonical form" }.value

    @Test
    fun aBackslashEndsTheAuthorityLikeInABrowser() {
        val url = checkNotNull(canonical("https://evil.tld\\@paypal.com"))
        assertEquals("evil.tld", url.host)
        assertEquals("https://evil.tld/@paypal.com", url.value)
    }

    @Test
    fun theUserinfoIsDroppedAndTheRealHostKept() {
        val url = checkNotNull(canonical("https://paypal.com@evil.example"))
        assertEquals("evil.example", url.host)
        assertEquals("https://evil.example/", url.value)
        assertFalse(url.value.contains("paypal"))
    }

    @Test
    fun anInternationalHostBecomesPunycode() {
        val url = checkNotNull(canonical("https://bücher.example/é"))
        assertEquals("xn--bcher-kva.example", url.host)
        assertEquals("https://xn--bcher-kva.example/%C3%A9", url.value)
    }

    @Test
    fun fullwidthAndUpperCaseHostsAreNormalized() {
        assertEquals("example.com", checkNotNull(canonical("https://EXAMPLE.com./")).host)
        assertEquals("example.com", checkNotNull(canonical("https://ｅxample.com/")).host)
    }

    @Test
    fun theAnalysedPortIsKept() {
        assertEquals(8443, canonical("https://example.com:8443/a")?.port)
        assertEquals("http://example.com:80/", value("http://example.com:80"))
        assertNull(canonical("https://example.com/")?.port)
    }

    @Test
    fun anUnusablePortOrHostHasNoCanonicalForm() {
        assertNull(canonical("https://example.com:99999/"))
        assertNull(canonical("https://example.com:12ab/"))
        assertNull(canonical("https://?x=1"))
        assertNull(canonical("https://exa mple.com/"))
        assertNull(canonical("https://exa<mple.com/"))
    }

    @Test
    fun pathQueryAndFragmentArePercentEncoded() {
        assertEquals(
            "https://example.com/a%20b?x=%C3%BC&y=%25zz&z=%41#f%20g%22",
            value("https://example.com/a b?x=ü&y=%zz&z=%41#f g\""),
        )
        assertEquals("https://example.com/a/b", value("https://example.com\\a\\b"))
    }

    @Test
    fun controlCharactersAndAngleBracketsNeverSurvive() {
        val text = value("https://example.com/<script>\u0001?q=<a>")
        assertEquals("https://example.com/%3Cscript%3E%01?q=%3Ca%3E", text)
    }

    @Test
    fun aValidEscapeStaysAndALoneSurrogateBecomesAReplacement() {
        assertEquals("https://example.com/%2f", value("https://example.com/%2f"))
        assertEquals("https://example.com/%EF%BF%BD", value("https://example.com/\ud800"))
    }

    @Test
    fun aMissingSchemeIsOpenedAsHttps() {
        assertEquals("https://example.com:8080/x", value("example.com:8080/x"))
    }

    @Test
    fun anIpv6LiteralKeepsItsBrackets() {
        val url = checkNotNull(canonical("https://[2001:DB8::1]:8080/"))
        assertEquals("[2001:db8::1]", url.host)
        assertEquals("https://[2001:db8::1]:8080/", url.value)
    }

    @Test
    fun theQueryKeepsItsQuestionMarksButAPathDoesNot() {
        assertEquals("https://example.com/a%3Fb?c?d", value("https://example.com/a%3Fb?c?d"))
        assertNotNull(canonical("https://example.com/?"))
    }

    @Test
    fun theAddressNeverShowsInToString() {
        assertEquals("CanonicalUrl(len=21)", canonical("https://example.com/a")?.toString())
    }
}
