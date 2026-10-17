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
        assertEquals("example.com", u.rawHost.value)
        assertEquals(8443, u.port)
        assertEquals("/a/b", u.path.value)
        assertEquals("x=1&y=2", u.query?.value)
        assertEquals("frag", u.fragment?.value)
        assertNull(u.userinfo?.value)
        assertEquals(HostKind.DOMAIN, u.hostKind)
        assertTrue(u.issues.isEmpty())
    }

    @Test
    fun schemeIsCaseInsensitive() {
        val u = url("HTTPS://Example.COM/Path")
        assertEquals("https", u.scheme)
        assertEquals("Example.COM", u.rawHost.value)
        assertEquals("/Path", u.path.value)
    }

    @Test
    fun userinfoTrickRevealsTheRealHost() {
        val u = url("https://paypal.com@evil.example/login")
        assertEquals("paypal.com", u.userinfo?.value)
        assertTrue(u.hasUserinfo)
        assertEquals("evil.example", u.rawHost.value)
    }

    @Test
    fun lastAtSignWins() {
        val u = url("https://a@b@evil.example")
        assertEquals("a@b", u.userinfo?.value)
        assertEquals("evil.example", u.rawHost.value)
    }

    @Test
    fun userinfoWithPasswordAndPort() {
        val u = url("http://user:pa:ss@host.example:81/")
        assertEquals("user:pa:ss", u.userinfo?.value)
        assertEquals("host.example", u.rawHost.value)
        assertEquals(81, u.port)
    }

    @Test
    fun backslashActsAsSlashAndIsFlagged() {
        val u = url("https://good.example\\@evil.example/x")
        assertEquals("good.example", u.rawHost.value)
        assertTrue(UrlIssue.BACKSLASH in u.issues)
    }

    @Test
    fun ipv4LiteralIsDetected() {
        val u = url("http://192.168.0.1:8080/admin")
        assertEquals(HostKind.IPV4, u.hostKind)
        assertEquals("192.168.0.1", u.rawHost.value)
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
        assertEquals("::1", u.rawHost.value)
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
        assertEquals("pаypal.com", u.rawHost.value)
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
        assertEquals("exa‮mple.com", u.rawHost.value)
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
        assertEquals("evil.example", url("https:evil.example").rawHost.value)
        assertTrue(UrlIssue.EXTRA_SLASHES in url("https:////evil.example").issues)
        assertEquals("evil.example", url("https:////evil.example").rawHost.value)
    }

    @Test
    fun percentEncodedHostAndForbiddenCharsAreFlagged() {
        assertTrue(UrlIssue.HOST_PERCENT_ENCODED in url("https://%65vil.example/").issues)
        assertTrue(UrlIssue.INVALID_HOST_CHARS in url("https://evil<>.example/").issues)
    }

    @Test
    fun emptyAndOversizedHostsAreFlagged() {
        // Browsers skip extra slashes for http(s): "path" is the host here.
        assertEquals("path", url("https:///path").rawHost.value)
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
        assertEquals("example.com", u.rawHost.value)
    }

    @Test
    fun noPathNoQueryGivesEmptyPath() {
        val u = url("https://example.com")
        assertEquals("", u.path.value)
        assertNull(u.query?.value)
        assertNull(u.fragment?.value)
    }

    @Test
    fun queryWithoutPathAndFragmentOnly() {
        assertEquals("a=1", url("https://example.com?a=1").query?.value)
        assertEquals("f", url("https://example.com#f").fragment?.value)
        assertNull(url("https://example.com#f?x").query?.value)
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
        assertEquals("https://paypal.com@evil.example", url("https://paypal.com@evil.example").raw.value)
    }

    @Test
    fun tabsAndNewlinesInsideTheUrlAreRemovedBeforeSplitting() {
        assertEquals("evil-phish.example", url("https:/\t/evil-phish.example/login").rawHost.value)
        val u = url("https://pay\npal-login.example")
        assertEquals("paypal-login.example", u.rawHost.value)
        assertTrue(UrlIssue.CONTROL_OR_SPACE in u.issues)
        assertEquals("a.example", url("\u0001 https://a.example\r\n ").rawHost.value)
    }

    @Test
    fun hostsWithNonAsciiOrPercentAreUnresolvedUntilTheNormalizerRuns() {
        listOf(
            "http://\uFF11\uFF12\uFF17.\uFF10.\uFF10.\uFF11/",
            "http://127\u30020\u30020\u30021/",
            "https://%70aypal.com",
            "https://p\u0430ypal.com",
        ).forEach { assertEquals(it, HostKind.UNRESOLVED, url(it).hostKind) }
    }

    @Test
    fun hostAndPortWithoutSchemeIsAnUrlWithMissingScheme() {
        val u = url("secure-paypal-login.example:8443/verify")
        assertTrue(UrlIssue.MISSING_SCHEME in u.issues)
        assertEquals("", u.scheme)
        assertEquals("secure-paypal-login.example", u.rawHost.value)
        assertEquals(8443, u.port)
        assertEquals("/verify", u.path.value)
        assertEquals("example.com", url("example.com:80").rawHost.value)
    }

    @Test
    fun reverseDomainDeepLinksStayAppLinks() {
        assertTrue(parseOk("com.app:/cb").payload is ScanPayload.AppLink)
        assertTrue(parseOk("com.app://callback?x=1").payload is ScanPayload.AppLink)
        assertTrue(parseOk("com.app:abc").payload is ScanPayload.AppLink)
    }
}
