package com.lookfirst.domain.analysis

import com.lookfirst.domain.decoder.PayloadParser
import com.lookfirst.domain.model.ParseResult
import com.lookfirst.domain.model.ScanPayload
import com.lookfirst.domain.model.SignalCode
import com.lookfirst.domain.usecase.AnalyzeScan
import com.lookfirst.domain.verdict.VerdictLevel
import com.lookfirst.ui.screens.result.openKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** LF-42: a one-word text shaped like an address is analysed (hosts, lists) but never offered to Open. */
class AddressLikeTextTest {
    private val seen = mutableListOf<String>()

    private fun withLists(listed: Set<String> = emptySet(), result: ReputationResult? = null) = AnalyzeScan(
        ReputationLookup {
            seen += it.ascii
            result ?: if (it.ascii in listed) ReputationResult.Listed("list") else ReputationResult.NotListed
        },
    )

    private fun payload(raw: String) = ok(raw).payload

    private fun addressOf(raw: String) =
        ((PayloadParser.parse(raw) as? ParseResult.Ok)?.payload as? ScanPayload.Text)?.addressLikeUrl()

    private fun codesOf(raw: String) = AddressLikeTextAnalyzer.on(raw).codes()

    @Test
    fun `listed host written without scheme is dangerous and still not openable`() {
        listOf("listed.com/x", "listed.com/login?a=1#b").forEach {
            val verdict = withLists(setOf("listed.com"))(it, "id")
            assertEquals(it, VerdictLevel.DANGEROUS, verdict.level)
            assertEquals(it, SignalCode.REPUTATION_LISTED, verdict.reasons.first().code)
            assertTrue(it, payload(it) is ScanPayload.Text)
            assertNull(it, payload(it).openKind())
        }
        val idn = withLists(setOf("xn--domaine-list-meb.com"))("domaine-listé.com/login", "id")
        assertEquals(VerdictLevel.DANGEROUS, idn.level)
        assertNull(payload("domaine-listé.com/login").openKind())
    }

    @Test
    fun `same lookup key as a url, port and idn included`() {
        val lookup = withLists(setOf("xn--bcher-kva.de", "1.2.3.4"))
        assertEquals(VerdictLevel.DANGEROUS, lookup("bücher.de:443/x", "id").level)
        assertEquals(VerdictLevel.DANGEROUS, lookup("1.2.3.4:80/x", "id").level)
        assertEquals(VerdictLevel.DANGEROUS, lookup("https://bücher.de/x", "id").level)
        assertEquals(VerdictLevel.DANGEROUS, lookup("BÜCHER.de/x", "id").level)
    }

    @Test
    fun `brand look-alike without scheme gets the brand signal`() {
        assertTrue(SignalCode.BRAND_LOOKALIKE in codesOf("paypa1-login.com/x"))
        val verdict = withLists()("paypa1-login.com/x", "id")
        assertTrue(verdict.reasons.any { it.code == SignalCode.BRAND_LOOKALIKE })
        assertNull(payload("paypa1-login.com/x").openKind())
    }

    @Test
    fun `idn is looked up in punycode and mixed alphabets are seen`() {
        assertTrue(SignalCode.MIXED_SCRIPT in codesOf("pаypal.com/x")) // cyrillic a
        seen.clear()
        withLists()("listé.fr/x", "id")
        assertEquals(listOf("xn--list-epa.fr"), seen)
    }

    @Test
    fun `ipv4 with port or path is an ip host`() {
        listOf("1.2.3.4:80/x", "1.2.3.4/x", "10.0.0.1:8080").forEach {
            assertTrue(it, SignalCode.IP_HOST in codesOf(it))
        }
        assertTrue(SignalCode.UNUSUAL_PORT !in codesOf("1.2.3.4:81/x"))
    }

    @Test
    fun `local and shortener hosts`() {
        assertTrue(SignalCode.LOCAL_HOST in codesOf("fritz.box/login"))
        assertTrue(SignalCode.SHORTENER in codesOf("bit.ly/abc"))
    }

    @Test
    fun `without lists the address is reported as unchecked`() {
        val none = withLists(result = ReputationResult.ListsMissing)
        listOf("domaine-listé.com/login", "1.2.3.4:80/x", "listed.com/x").forEach {
            val verdict = none(it, "id")
            assertTrue(it, verdict.reasons.any { r -> r.code == SignalCode.LISTS_MISSING })
            assertTrue(it, verdict.level != VerdictLevel.NO_RISK_SIGNAL)
        }
    }

    @Test
    fun `ordinary text gets no new signal and no lookup`() {
        val failing = AnalyzeScan(ReputationLookup { throw AssertionError("lookup must not be called") })
        ordinary.forEach {
            assertNull(it, addressOf(it))
            assertEquals(it, emptyList<Any>(), AddressLikeTextAnalyzer.on(it))
            // Some of these are real urls for the decoder (already looked up before LF-42): only text must not be.
            if (payload(it) is ScanPayload.Text) {
                assertTrue(it, failing(it, "id").reasons.none { r -> r.code == SignalCode.LISTS_UNAVAILABLE })
            }
        }
    }

    private val ordinary =
        listOf(
            "Hello world",
            "Visit us at the shop. Thanks.com",
            "3.14",
            "v1.2.3",
            "1.2.3.4",
            "1.2.3",
            "rapport.pdf",
            "photo.jpg",
            "archive.tar.gz",
            "Mr.Smith",
            "e.g.",
            "bob@example.com",
            "bob@example.com/x",
            "FR7630006000011234567890189",
            "ABC-123-XYZ",
            "A1B2C3",
            "example.invalidtld/x",
            "listed.example/x",
            "example..com/x",
            ".com",
            "com",
            "example.com.",
            "github.io",
            "co.uk",
            "256.1.1.1:80",
            "exa mple.com",
            "example.com\\x",
            "example.com:99999/x",
            "ex_ample.com/x",
            "[::1]:80/x",
            "例え",
        )

    @Test
    fun `file names that are also tlds are analysed, documented choice`() {
        // .zip and .mov are real TLDs and zip sites are a real lure: analysed. Nothing new when not listed.
        listOf("rapport.zip", "film.mov", "readme.md").forEach {
            assertNotNull(it, addressOf(it))
            val verdict = withLists()(it, "id")
            assertEquals(it, VerdictLevel.CAUTION, verdict.level)
            assertEquals(it, listOf(SignalCode.UNPARSABLE), verdict.reasons.map { r -> r.code })
        }
        assertEquals(VerdictLevel.DANGEROUS, withLists(setOf("invoice-paypal.zip"))("invoice-paypal.zip", "id").level)
    }

    @Test
    fun `huge or crafted text is not an address and costs nothing`() {
        val start = System.nanoTime()
        listOf("a.".repeat(50_000) + "com/x", "a".repeat(100_000) + ".com", "x.com/" + "a".repeat(3_000)).forEach {
            assertNull(it.take(20), addressOf(it))
        }
        assertTrue((System.nanoTime() - start) / 1_000_000 < 2_000)
    }
}
