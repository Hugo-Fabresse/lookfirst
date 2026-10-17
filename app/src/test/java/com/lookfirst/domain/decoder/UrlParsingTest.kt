package com.lookfirst.domain.decoder

import com.lookfirst.domain.model.HiddenCharKind
import com.lookfirst.domain.model.HostKind
import com.lookfirst.domain.model.ScanPayload
import com.lookfirst.domain.model.UrlIssue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlParsingTest {
    private fun url(raw: String) = decode<ScanPayload.Url>(raw)

    @Test
    fun plainHttpsUrlIsSplit() {
        val u = url("https://example.com:8443/a/b?x=1&y=2#frag")
        assertEquals("https", u.scheme)
        assertEquals("example.com", u.host)
        assertEquals(8443, u.port)
        assertEquals("/a/b", u.path)
        assertEquals("x=1&y=2", u.query)
        assertEquals("frag", u.fragment)
        assertNull(u.userinfo)
        assertEquals(HostKind.DOMAIN, u.hostKind)
        assertTrue(u.issues.isEmpty())
    }

    @Test
    fun schemeIsCaseInsensitive() {
        val u = url("HTTPS://Example.COM/Path")
        assertEquals("https", u.scheme)
        assertEquals("Example.COM", u.host)
        assertEquals("/Path", u.path)
    }

    @Test
    fun userinfoTrickRevealsTheRealHost() {
        val u = url("https://paypal.com@evil.example/login")
        assertEquals("paypal.com", u.userinfo)
        assertTrue(u.hasUserinfo)
        assertEquals("evil.example", u.host)
    }

    @Test
    fun lastAtSignWins() {
        val u = url("https://a@b@evil.example")
        assertEquals("a@b", u.userinfo)
        assertEquals("evil.example", u.host)
    }

    @Test
    fun userinfoWithPasswordAndPort() {
        val u = url("http://user:pa:ss@host.example:81/")
        assertEquals("user:pa:ss", u.userinfo)
        assertEquals("host.example", u.host)
        assertEquals(81, u.port)
    }

    @Test
    fun backslashActsAsSlashAndIsFlagged() {
        val u = url("https://good.example\\@evil.example/x")
        assertEquals("good.example", u.host)
        assertTrue(UrlIssue.BACKSLASH in u.issues)
    }

    @Test
    fun ipv4LiteralIsDetected() {
        val u = url("http://192.168.0.1:8080/admin")
        assertEquals(HostKind.IPV4, u.hostKind)
        assertEquals("192.168.0.1", u.host)
    }

    @Test
    fun decimalHexAndOctalIpsAreObfuscated() {
        listOf(
            "http://3232235777/",
            "http://0xC0A80001/",
            "http://0300.0250.0.1/",
            "http://192.168.1/",
            "http://010.1.1.1/",
        )
            .forEach { assertEquals(it, HostKind.IPV4_OBFUSCATED, url(it).hostKind) }
    }

    @Test
    fun hostThatMerelyStartsWithDigitsIsADomain() {
        assertEquals(HostKind.DOMAIN, url("http://123.example.com/").hostKind)
    }

    @Test
    fun ipv6LiteralKeepsBracketsOutOfHost() {
        val u = url("http://[::1]:3000/x")
        assertEquals("::1", u.host)
        assertEquals(3000, u.port)
        assertEquals(HostKind.IPV6, u.hostKind)
        assertTrue(u.issues.isEmpty())
    }

    @Test
    fun brokenIpv6IsFlagged() {
        assertTrue(UrlIssue.INVALID_HOST_CHARS in url("http://[::1/x").issues)
        assertTrue(UrlIssue.INVALID_HOST_CHARS in url("http://[zz]/").issues)
        assertTrue(UrlIssue.INVALID_HOST_CHARS in url("http://[::1]x/").issues)
    }

    @Test
    fun invalidPortsAreFlaggedNotAccepted() {
        listOf("http://a.example:99999/", "http://a.example:80a/", "http://a.example:-1/").forEach {
            val u = url(it)
            assertNull(it, u.port)
            assertTrue(it, UrlIssue.INVALID_PORT in u.issues)
        }
        assertNull(url("http://a.example:/").port)
    }

    @Test
    fun cyrillicHomographHostIsKeptAsTypedAndFlaggedNonAscii() {
        val u = url("https://pаypal.com/")
        assertEquals("pаypal.com", u.host)
        assertTrue(u.nonAsciiHost)
    }

    @Test
    fun asciiHostIsNotNonAscii() {
        assertFalse(url("https://paypal.com").nonAsciiHost)
    }

    @Test
    fun bidiInsideHostIsReportedAndHostIsKept() {
        val ok = parseOk("https://exa‮mple.com/")
        val u = ok.payload as ScanPayload.Url
        assertTrue(u.nonAsciiHost)
        assertEquals("exa‮mple.com", u.host)
        assertEquals(listOf(HiddenCharKind.BIDI), ok.hiddenChars.map { it.kind })
    }

    @Test
    fun zeroWidthInsideHostIsReported() {
        val ok = parseOk("https://pay​pal.com/")
        assertEquals(listOf(HiddenCharKind.ZERO_WIDTH), ok.hiddenChars.map { it.kind })
        assertTrue((ok.payload as ScanPayload.Url).nonAsciiHost)
    }

    @Test
    fun missingOrExtraSlashesAreFlagged() {
        assertTrue(UrlIssue.MISSING_SLASHES in url("https:evil.example").issues)
        assertEquals("evil.example", url("https:evil.example").host)
        assertTrue(UrlIssue.EXTRA_SLASHES in url("https:////evil.example").issues)
        assertEquals("evil.example", url("https:////evil.example").host)
    }

    @Test
    fun percentEncodedHostAndForbiddenCharsAreFlagged() {
        assertTrue(UrlIssue.HOST_PERCENT_ENCODED in url("https://%65vil.example/").issues)
        assertTrue(UrlIssue.INVALID_HOST_CHARS in url("https://evil<>.example/").issues)
    }

    @Test
    fun emptyAndOversizedHostsAreFlagged() {
        // Browsers skip extra slashes for http(s): "path" is the host here.
        assertEquals("path", url("https:///path").host)
        assertTrue(UrlIssue.EXTRA_SLASHES in url("https:///path").issues)
        assertTrue(UrlIssue.EMPTY_HOST in url("https://").issues)
        assertTrue(UrlIssue.HOST_TOO_LONG in url("https://" + "a".repeat(300) + ".com").issues)
    }

    @Test
    fun embeddedWhitespaceAndControlAreFlagged() {
        assertTrue(UrlIssue.CONTROL_OR_SPACE in url("https://exa mple.com").issues)
        assertTrue(UrlIssue.CONTROL_OR_SPACE in url("ht\ttps://example.com").issues)
    }

    @Test
    fun leadingAndTrailingWhitespaceDoesNotHideTheScheme() {
        val u = url("  https://example.com/  ")
        assertEquals("example.com", u.host)
    }

    @Test
    fun noPathNoQueryGivesEmptyPath() {
        val u = url("https://example.com")
        assertEquals("", u.path)
        assertNull(u.query)
        assertNull(u.fragment)
    }

    @Test
    fun queryWithoutPathAndFragmentOnly() {
        assertEquals("a=1", url("https://example.com?a=1").query)
        assertEquals("f", url("https://example.com#f").fragment)
        assertNull(url("https://example.com#f?x").query)
    }

    @Test
    fun urlsWithoutSchemeAreText() {
        assertTrue(parseOk("example.com/login").payload is ScanPayload.Text)
        assertTrue(parseOk("www.paypal.com").payload is ScanPayload.Text)
    }

    @Test
    fun protocolRelativeUrlIsText() {
        assertTrue(parseOk("//evil.example/x").payload is ScanPayload.Text)
    }

    @Test
    fun rawKeepsTheFullSanitizedText() {
        assertEquals("https://paypal.com@evil.example", url("https://paypal.com@evil.example").raw)
    }
}
