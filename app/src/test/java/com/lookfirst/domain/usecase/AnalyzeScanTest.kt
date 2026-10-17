package com.lookfirst.domain.usecase

import com.lookfirst.domain.analysis.ReputationLookup
import com.lookfirst.domain.analysis.ReputationResult
import com.lookfirst.domain.decoder.PayloadParser
import com.lookfirst.domain.model.ParseResult
import com.lookfirst.domain.model.ScanPayload
import com.lookfirst.domain.model.Severity
import com.lookfirst.domain.model.SignalCode
import com.lookfirst.domain.open.CanonicalUrl
import com.lookfirst.domain.open.effectiveWebScheme
import com.lookfirst.domain.verdict.VerdictLevel
import com.lookfirst.ui.screens.result.OpenKind
import com.lookfirst.ui.screens.result.openKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AnalyzeScanTest {
    private fun useCase(result: ReputationResult) = AnalyzeScan(ReputationLookup { result })

    private val noLists = useCase(ReputationResult.ListsMissing)

    private fun AnalyzeScan.level(raw: String) = invoke(raw, "id").level

    @Test
    fun `listed host is dangerous`() {
        val verdict = useCase(ReputationResult.Listed("list"))("https://example.com/", "id")
        assertEquals(VerdictLevel.DANGEROUS, verdict.level)
        assertEquals(SignalCode.REPUTATION_LISTED, verdict.reasons.first().code)
    }

    @Test
    fun `not listed host has no signal`() {
        val verdict = useCase(ReputationResult.NotListed)("https://example.com/", "id")
        assertEquals(VerdictLevel.NO_RISK_SIGNAL, verdict.level)
        assertTrue(verdict.reasons.isEmpty())
    }

    @Test
    fun `missing lists is caution`() {
        val verdict = useCase(ReputationResult.ListsMissing)("https://example.com/", "id")
        assertEquals(VerdictLevel.CAUTION, verdict.level)
        assertEquals(listOf(SignalCode.LISTS_MISSING), verdict.reasons.map { it.code })
    }

    @Test
    fun `lists older than 14 days are caution, fresher ones are not`() {
        val stale = useCase(ReputationResult.ListsStale(15))("https://example.com/", "id")
        assertEquals(VerdictLevel.CAUTION, stale.level)
        assertEquals(listOf(SignalCode.LISTS_STALE), stale.reasons.map { it.code })
        val fresh = useCase(ReputationResult.ListsStale(14)).level("https://example.com/")
        assertEquals(VerdictLevel.NO_RISK_SIGNAL, fresh)
    }

    @Test
    fun `non url content is not looked up`() {
        assertEquals(VerdictLevel.CAUTION, noLists.level("hello"))
        assertTrue(noLists("WIFI:T:WPA;S:x;P:y;;", "id").reasons.none { it.code == SignalCode.LISTS_MISSING })
    }

    // Wanted behaviour: without reputation lists even a well-known brand URL is CAUTION (LISTS_MISSING).
    @Test
    fun `end to end realistic codes without lists`() {
        assertEquals(VerdictLevel.CAUTION, noLists.level("https://www.paypal.com"))
        assertEquals(VerdictLevel.DANGEROUS, noLists.level("https://paypal.com@evil.example"))
        assertEquals(VerdictLevel.CAUTION, noLists.level("WIFI:T:nopass;S:x;;"))
        assertEquals(VerdictLevel.DANGEROUS, noLists.level("javascript:alert(1)"))
        assertEquals(VerdictLevel.CAUTION, noLists.level(""))
    }

    @Test
    fun `payload id is carried`() {
        assertEquals("p", noLists("hello", "p").payloadId)
    }

    @Test
    fun `failing lookup is caution, never an exception`() {
        val verdict = AnalyzeScan(ReputationLookup { error("x") })("https://example.com/", "id")
        assertEquals(VerdictLevel.CAUTION, verdict.level)
        assertEquals(listOf(SignalCode.LISTS_UNAVAILABLE), verdict.reasons.map { it.code })
    }

    @Test
    fun `negative age is treated as stale`() {
        val verdict = useCase(ReputationResult.ListsStale(-1))("https://example.com/", "id")
        assertEquals(VerdictLevel.CAUTION, verdict.level)
        assertEquals(listOf(SignalCode.LISTS_STALE), verdict.reasons.map { it.code })
    }

    @Test
    fun `too large content end to end is caution`() {
        val verdict = noLists("a".repeat(100_000), "id")
        assertEquals(VerdictLevel.CAUTION, verdict.level)
        assertEquals(listOf(SignalCode.UNPARSABLE), verdict.reasons.map { it.code })
    }

    @Test
    fun `listed plain http host comes first among other signals`() {
        val verdict = useCase(ReputationResult.Listed("list"))("http://user@192.168.0.1/", "id")
        assertEquals(VerdictLevel.DANGEROUS, verdict.level)
        assertEquals(SignalCode.REPUTATION_LISTED, verdict.reasons.first().code)
        assertTrue(verdict.reasons.size > 1)
        assertTrue(verdict.reasons.all { it.severity != Severity.DANGEROUS || it.code == SignalCode.REPUTATION_LISTED })
    }

    @Test
    fun `non http content never reaches the lookup`() {
        val failing = AnalyzeScan(ReputationLookup { throw AssertionError("lookup must not be called") })
        listOf("ftp://example.com/file", "tel:+33123456789", "mailto:a@example.com", "hello").forEach {
            assertTrue(it, failing(it, "id").reasons.none { r -> r.code == SignalCode.LISTS_UNAVAILABLE })
        }
    }

    private val listedHost = "listed.example"

    private fun listsWithListedHost() = AnalyzeScan(
        ReputationLookup {
            if (it.ascii ==
                listedHost
            ) {
                ReputationResult.Listed("list")
            } else {
                ReputationResult.NotListed
            }
        },
    )

    @Test
    fun `scheme less host and port is checked against the lists like the https url it opens as`() {
        val withLists = listsWithListedHost()
        listOf(
            "$listedHost:443/x",
            "$listedHost:8443/x",
            "$listedHost:8443",
            "$listedHost:443?q=1",
            "https://$listedHost:443/x",
        ).forEach {
            val verdict = withLists(it, "id")
            assertEquals(it, VerdictLevel.DANGEROUS, verdict.level)
            assertEquals(it, SignalCode.REPUTATION_LISTED, verdict.reasons.first().code)
        }
        assertEquals(VerdictLevel.CAUTION, withLists.level("other.example.com:8443/x"))
    }

    @Test
    fun `host without scheme and port is text today, and would be checked if the decoder made it a url`() {
        val raw = "$listedHost/x"
        val payload = (PayloadParser.parse(raw) as ParseResult.Ok).payload
        val verdict = listsWithListedHost()(raw, "id")
        when (payload) {
            is ScanPayload.Text -> {
                assertNull(payload.openKind())
                assertEquals(VerdictLevel.CAUTION, verdict.level)
            }
            is ScanPayload.Url -> assertEquals(VerdictLevel.DANGEROUS, verdict.level)
            else -> fail("unexpected payload")
        }
    }

    @Test
    fun `upper case scheme, missing slashes and invisible lead are judged on what is opened`() {
        val withLists = listsWithListedHost()
        listOf("HTTPS://$listedHost/x", "http:$listedHost/x", "https:/$listedHost/x").forEach {
            assertEquals(it, VerdictLevel.DANGEROUS, withLists.level(it))
        }
        // A no-break space or an invisible character in front: whatever the decoder makes of it, if the Open action
        // leads to a browser the host was looked up, and if it does not nothing is opened.
        listOf("\u00a0$listedHost:443/x", "\u200b$listedHost:443/x", "\u200bhttps://$listedHost/x").forEach { raw ->
            val payload = (PayloadParser.parse(raw) as ParseResult.Ok).payload
            val level = withLists.level(raw)
            if (payload is ScanPayload.Url && CanonicalUrl.of(payload) != null) {
                assertEquals(raw, VerdictLevel.DANGEROUS, level)
            } else {
                assertNull(raw, payload.openKind())
            }
        }
    }

    @Test
    fun `obfuscated ipv4 is looked up in dotted form, like the address that is opened`() {
        val listed = AnalyzeScan(
            ReputationLookup {
                if (it.ascii ==
                    "127.0.0.1"
                ) {
                    ReputationResult.Listed("list")
                } else {
                    ReputationResult.NotListed
                }
            },
        )
        listOf("http://0x7f.1/x", "http://2130706433/x", "http://127.0.0.1/x").forEach {
            assertEquals(it, VerdictLevel.DANGEROUS, listed.level(it))
        }
    }

    @Test
    fun `effective scheme and open kind come from the canonical url`() {
        fun url(raw: String) = (PayloadParser.parse(raw) as ParseResult.Ok).payload as ScanPayload.Url
        assertEquals("https", url("$listedHost:443/x").effectiveWebScheme())
        assertEquals("http", url("http://$listedHost/x").effectiveWebScheme())
        assertEquals(OpenKind.BROWSER, url("$listedHost:443/x").openKind())
        val broken = url("https://:443/x")
        assertNull(CanonicalUrl.of(broken))
        assertNull(broken.openKind())
    }

    @Test
    fun `scheme less url without lists reports missing lists`() {
        listOf("listed.example:443/x", "listed.example:8443/x").forEach {
            val codes = noLists(it, "id").reasons.map { r -> r.code }
            assertTrue(it, SignalCode.LISTS_MISSING in codes)
        }
        val stale = useCase(ReputationResult.ListsStale(20))("listed.example:443/x", "id")
        assertTrue(SignalCode.LISTS_STALE in stale.reasons.map { it.code })
    }

    @Test
    fun `scheme less brand imitation gets the same signals as its https form`() {
        listOf("paypa1-login.example:443/verify", "secure-paypal.example:8443/", "xn--pypal-4ve.example:443/").forEach {
            val bare = noLists(it, "id").reasons.map { r -> r.code }.toSet() - SignalCode.URL_ODD_FORMAT
            val https = noLists("https://$it", "id").reasons.map { r -> r.code }.toSet() - SignalCode.URL_ODD_FORMAT
            assertEquals(it, https, bare)
        }
        assertEquals(VerdictLevel.DANGEROUS, listsWithListedHost().level("$listedHost:443/paypal"))
    }

    @Test
    fun `host looked up is the host of the canonical url that is opened`() {
        listOf(
            "listed.example:443/x",
            "LISTED.example:8443/x",
            "xn--bcher-kva.example:443/x",
            "a.b.example:80?x=1",
            "https://user@host.example/x",
            "http://host.example:8080/x",
            "https://\uff48ost.example/x",
            "http://0x7f.1/x",
            "http://2130706433/x",
        ).forEach { raw ->
            val seen = mutableListOf<String>()
            AnalyzeScan(
                ReputationLookup {
                    seen += it.ascii
                    ReputationResult.NotListed
                },
            )(raw, "id")
            val canonical = CanonicalUrl.of(
                (PayloadParser.parse(raw) as ParseResult.Ok).payload as ScanPayload.Url,
            )
            assertEquals(raw, listOf(checkNotNull(canonical).host), seen)
        }
    }
}
