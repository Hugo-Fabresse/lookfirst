package com.lookfirst.domain.usecase

import com.lookfirst.domain.analysis.ReputationLookup
import com.lookfirst.domain.analysis.ReputationResult
import com.lookfirst.domain.decoder.decode
import com.lookfirst.domain.model.ScanPayload
import com.lookfirst.domain.model.SignalCode
import com.lookfirst.domain.model.Untrusted
import com.lookfirst.domain.open.CanonicalUrl
import com.lookfirst.domain.redirect.RedirectTrace
import com.lookfirst.domain.redirect.TraceEnd
import com.lookfirst.domain.verdict.Verdict
import com.lookfirst.domain.verdict.VerdictLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplyRedirectTraceTest {
    private val lookup = ReputationLookup { host ->
        when (host.ascii) {
            "evil.com" -> ReputationResult.Listed("test")
            else -> ReputationResult.NotListed
        }
    }
    private val analyze = AnalyzeScan(lookup)
    private val apply = ApplyRedirectTrace(analyze)

    private fun url(raw: String) = checkNotNull(CanonicalUrl.of(decode<ScanPayload.Url>(raw)))

    private fun scanned(raw: String): Verdict = analyze(raw, "payload-1")

    private fun codes(verdict: Verdict) = verdict.reasons.map { it.code }

    private fun trace(start: String, hops: List<String>, end: TraceEnd = TraceEnd.Final) =
        RedirectTrace(url(start), hops.map { url(it) }, end)

    @Test
    fun `a clean chain keeps the level and adds the final destination as information`() {
        val before = scanned("https://a.com/")
        val after = apply(before, trace("https://a.com/", listOf("https://b.com/x", "https://c.com/y")))
        assertEquals(VerdictLevel.NO_RISK_SIGNAL, after.level)
        assertEquals("payload-1", after.payloadId)
        val final = after.reasons.single { it.code == SignalCode.REDIRECT_FINAL }
        assertEquals(2, final.count)
        assertEquals("https://c.com/y", final.evidence?.value)
    }

    @Test
    fun `no redirect at all still says where the link ended`() {
        val after = apply(scanned("https://a.com/"), trace("https://a.com/", emptyList()))
        val final = after.reasons.single { it.code == SignalCode.REDIRECT_FINAL }
        assertEquals(0, final.count)
        assertEquals("https://a.com/", final.evidence?.value)
    }

    @Test
    fun `a listed final destination raises the level to dangerous`() {
        val before = scanned("https://a.com/")
        val after = apply(before, trace("https://a.com/", listOf("https://evil.com/login")))
        assertEquals(VerdictLevel.DANGEROUS, after.level)
        assertTrue(SignalCode.REPUTATION_LISTED in codes(after))
    }

    @Test
    fun `an intermediate hop is analysed like the destination`() {
        val after = apply(
            scanned("https://a.com/"),
            trace("https://a.com/", listOf("https://evil.com/", "https://c.com/")),
        )
        assertEquals(VerdictLevel.DANGEROUS, after.level)
    }

    @Test
    fun `a hop with a risky shape adds its signals`() {
        val after = apply(scanned("https://a.com/"), trace("https://a.com/", listOf("https://192.0.2.7:8443/")))
        assertTrue(SignalCode.IP_HOST in codes(after))
        assertEquals(VerdictLevel.SUSPICIOUS, after.level)
    }

    @Test
    fun `the level never goes down, whatever the trace finds`() {
        val dangerous = scanned("https://evil.com/")
        assertEquals(VerdictLevel.DANGEROUS, dangerous.level)
        val after = apply(dangerous, trace("https://evil.com/", listOf("https://b.com/")))
        assertEquals(VerdictLevel.DANGEROUS, after.level)
        assertTrue(
            dangerous.reasons.all { old ->
                after.reasons.any { it.code == old.code && it.evidence == old.evidence }
            },
        )
    }

    @Test
    fun `every earlier reason is kept`() {
        val before = scanned("https://paypal.com@a.com/")
        val after = apply(before, trace("https://a.com/", listOf("https://b.com/")))
        assertTrue(before.reasons.all { old -> after.reasons.any { it.code == old.code } })
    }

    @Test
    fun `an http hop is a risk signal and is analysed`() {
        val end = TraceEnd.InsecureHop(url("http://b.com/plain"))
        val after = apply(scanned("https://a.com/"), trace("https://a.com/", emptyList(), end))
        assertEquals(VerdictLevel.SUSPICIOUS, after.level)
        val reason = after.reasons.first { it.code == SignalCode.REDIRECT_HTTP }
        assertEquals("http://b.com/plain", reason.evidence?.value)
        assertTrue(SignalCode.PLAIN_HTTP in codes(after))
        assertFalse(SignalCode.REDIRECT_FINAL in codes(after))
    }

    @Test
    fun `a redirect to a private address is suspicious`() {
        val end = TraceEnd.BlockedAddress(url("https://router.com/admin"))
        val after = apply(scanned("https://a.com/"), trace("https://a.com/", listOf(end.target.value), end))
        assertEquals(VerdictLevel.SUSPICIOUS, after.level)
        assertTrue(SignalCode.REDIRECT_PRIVATE in codes(after))
        assertFalse(SignalCode.REDIRECT_FINAL in codes(after))
    }

    @Test
    fun `a hop on another port is a caution and is never reported as the destination`() {
        val end = TraceEnd.UnsupportedPort(url("https://b.com:8443/"))
        val after = apply(scanned("https://a.com/"), trace("https://a.com/", listOf(end.target.value), end))
        assertEquals(VerdictLevel.CAUTION, after.level)
        assertTrue(SignalCode.UNUSUAL_PORT in codes(after))
        assertFalse(SignalCode.REDIRECT_FINAL in codes(after))
    }

    @Test
    fun `a private scanned address has no hop count`() {
        val end = TraceEnd.BlockedAddress(url("https://a.com/"))
        val after = apply(scanned("https://a.com/"), trace("https://a.com/", emptyList(), end))
        assertEquals(0, after.reasons.single { it.code == SignalCode.REDIRECT_PRIVATE }.count)
    }

    @Test
    fun `a loop or too many hops is a caution`() {
        listOf(TraceEnd.Loop, TraceEnd.TooManyHops).forEach { end ->
            val after =
                apply(scanned("https://a.com/"), trace("https://a.com/", listOf("https://b.com/"), end))
            assertEquals(VerdictLevel.CAUTION, after.level)
            assertTrue(SignalCode.REDIRECT_EXCESSIVE in codes(after))
        }
    }

    @Test
    fun `a javascript location is reported as a dangerous scheme`() {
        val end = TraceEnd.BadLocation(Untrusted("javascript:alert(1)"))
        val after = apply(scanned("https://a.com/"), trace("https://a.com/", emptyList(), end))
        assertEquals(VerdictLevel.DANGEROUS, after.level)
        assertTrue(SignalCode.DANGEROUS_SCHEME in codes(after))
        assertTrue(SignalCode.URL_MALFORMED in codes(after))
    }

    @Test
    fun `a malformed location stops the chain with a signal`() {
        val end = TraceEnd.BadLocation(Untrusted("https://"))
        val after = apply(scanned("https://a.com/"), trace("https://a.com/", emptyList(), end))
        assertTrue(SignalCode.URL_MALFORMED in codes(after))
        assertTrue(after.level.ordinal <= VerdictLevel.SUSPICIOUS.ordinal)
    }

    @Test
    fun `applying the same trace twice gives the same verdict`() {
        val t = trace("https://a.com/", listOf("https://evil.com/", "https://c.com/"))
        val once = apply(scanned("https://a.com/"), t)
        val twice = apply(once, t)
        assertEquals(once, twice)
    }

    @Test
    fun `the earlier redirect reasons are replaced, not stacked`() {
        val first = apply(scanned("https://a.com/"), trace("https://a.com/", listOf("https://b.com/")))
        val second = apply(first, trace("https://a.com/", listOf("https://c.com/")))
        assertEquals(1, second.reasons.count { it.code == SignalCode.REDIRECT_FINAL })
        assertEquals(
            "https://c.com/",
            second.reasons.single {
                it.code == SignalCode.REDIRECT_FINAL
            }.evidence?.value,
        )
    }

    @Test
    fun `the info reason alone does not change the level`() {
        val before = scanned("https://a.com/")
        val after = apply(before, trace("https://a.com/", listOf("https://b.com/")))
        assertEquals(before.level, after.level)
    }
}
