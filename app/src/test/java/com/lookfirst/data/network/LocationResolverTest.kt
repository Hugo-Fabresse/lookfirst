package com.lookfirst.data.network

import com.lookfirst.domain.decoder.decode
import com.lookfirst.domain.model.ScanPayload
import com.lookfirst.domain.open.CanonicalUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationResolverTest {
    private val base = checkNotNull(CanonicalUrl.of(decode<ScanPayload.Url>("https://a.example/dir/page?x=1#frag")))

    private fun secure(location: String): String =
        (LocationResolver.resolve(base, location) as NextHop.Secure).url.value

    private fun unusable(location: String) {
        val next = LocationResolver.resolve(base, location)
        assertTrue("$location -> $next", next is NextHop.Unusable)
    }

    @Test
    fun `an absolute https location is followed in canonical form`() {
        assertEquals("https://b.example/p?q=1", secure("https://B.EXAMPLE/p?q=1"))
        assertEquals("https://xn--bcher-kva.example/", secure("https://bücher.example/"))
    }

    @Test
    fun `an http location is reported as insecure and not followed`() {
        val next = LocationResolver.resolve(base, "http://b.example/x")
        assertEquals("http://b.example/x", (next as NextHop.Insecure).url.value)
    }

    @Test
    fun `relative references are resolved against the current address`() {
        assertEquals("https://a.example/other", secure("/other"))
        assertEquals("https://a.example/dir/sibling", secure("sibling"))
        assertEquals("https://a.example/dir/sub/x", secure("sub/x"))
        assertEquals("https://a.example/up", secure("../up"))
        assertEquals("https://a.example/dir/", secure("./"))
        assertEquals("https://a.example/dir/page?y=2", secure("?y=2"))
        assertEquals("https://a.example/dir/page?x=1#f2", secure("#f2"))
        assertEquals("https://c.example/z", secure("//c.example/z"))
    }

    @Test
    fun `dot segments cannot climb above the root`() {
        assertEquals("https://a.example/etc", secure("../../../../etc"))
        assertEquals("https://a.example/", secure("/../.."))
    }

    @Test
    fun `a relative location keeps the port of the current address`() {
        val ported = checkNotNull(CanonicalUrl.of(decode<ScanPayload.Url>("https://a.example:8443/x")))
        assertEquals("https://a.example:8443/y", (LocationResolver.resolve(ported, "/y") as NextHop.Secure).url.value)
    }

    @Test
    fun `a backslash after the scheme-relative start counts as a slash like in a browser`() {
        assertEquals("https://evil.example/", secure("/\\evil.example"))
        assertEquals("https://evil.example/", secure("\\\\evil.example"))
    }

    @Test
    fun `userinfo is dropped so the real host is the one followed`() {
        assertEquals("https://evil.example/", secure("https://paypal.com@evil.example/"))
    }

    @Test
    fun `other schemes and hostile forms are unusable`() {
        listOf(
            "javascript:alert(1)", "data:text/html,<script>1</script>", "intent://x#Intent;scheme=a;end",
            "file:///etc/passwd",
            "content://x/y", "ftp://a.example/", "mailto:a@b.example", "tel:123", "myapp://deep", "host.example:8080/x",
            "https://", "https://a b.example/", "https://exa\u0000mple.com/", "https://a.example:99999/",
            "https://[::1/", "", "   ",
        ).forEach { unusable(it) }
    }

    @Test
    fun `control characters make a location unusable`() {
        unusable("/ok\r\nSet-Cookie: a=b")
        unusable("/ok\u0007")
        unusable("/ok\u007F")
    }

    @Test
    fun `a location over the limit is unusable and a huge one is cheap`() {
        unusable("/" + "a".repeat(MAX_LOCATION_CHARS))
        val started = System.nanoTime()
        unusable("/" + "a/../".repeat(200_000))
        unusable("https://" + "a.".repeat(200_000))
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000)
    }

    @Test
    fun `percent-encoded and unicode paths are made canonical`() {
        assertEquals("https://a.example/%C3%A9", secure("/é"))
        assertEquals("https://a.example/a%20b", secure("/a b"))
    }
}
