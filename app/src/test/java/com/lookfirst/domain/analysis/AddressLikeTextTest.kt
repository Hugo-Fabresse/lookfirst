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
        ((PayloadParser.parse(raw) as? ParseResult.Ok)?.payload as? ScanPayload.Text)?.addressLikeUrl

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
            "example..com/x",
            ".com",
            "com",
            "example.com.",
            "\u6771\u4eac\u3002\u65e5\u672c",
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
        // .zip and .mov are real TLDs and zip sites are a real lure: analysed, quietly when nothing is listed.
        listOf("rapport.zip", "film.mov", "readme.md").forEach {
            assertNotNull(it, addressOf(it))
            val verdict = withLists()(it, "id")
            assertEquals(it, VerdictLevel.CAUTION, verdict.level)
            assertEquals(it, listOf(SignalCode.UNPARSABLE), verdict.reasons.map { r -> r.code })
            val none = withLists(result = ReputationResult.ListsMissing)(it, "id")
            assertEquals(it, listOf(SignalCode.UNPARSABLE), none.reasons.map { r -> r.code })
            val stale = withLists(result = ReputationResult.ListsStale(30))(it, "id")
            assertEquals(it, listOf(SignalCode.UNPARSABLE), stale.reasons.map { r -> r.code })
        }
        assertEquals(VerdictLevel.DANGEROUS, withLists(setOf("invoice-paypal.zip"))("invoice-paypal.zip", "id").level)
        // With a path the lists status is reported again.
        val withPath = withLists(result = ReputationResult.ListsMissing)("readme.md/x", "id")
        assertTrue(withPath.reasons.any { it.code == SignalCode.LISTS_MISSING })
    }

    @Test
    fun `spaces and line breaks around the address are ignored`() {
        listOf("listed.com/x\n", "listed.com/x ", "\tlisted.com/x\r\n").forEach {
            val verdict = withLists(setOf("listed.com"))(it, "id")
            assertEquals(it, VerdictLevel.DANGEROUS, verdict.level)
            assertNull(it, payload(it).openKind())
        }
        assertNull(addressOf("listed.com /x"))
    }

    @Test
    fun `host shape is judged on the normalized host`() {
        val lookup = withLists(setOf("listed.com"))
        listOf(
            "listed\u3002com/x",
            "listed\uFF0Ecom/x",
            "listed\uFF61com/x",
            "listed.com./x",
            "listed.com.:443",
        ).forEach {
            assertEquals(it, VerdictLevel.DANGEROUS, lookup(it, "id").level)
        }
        assertTrue(SignalCode.BRAND_LOOKALIKE in codesOf("paypal\u3002com/x").plus(codesOf("paypa1\u3002com/x")))
        // Mathematical bold letters map to plain ones under UTS #46.
        val bold = "\uD835\uDC29\uD835\uDC1A\uD835\uDC32\uD835\uDC29\uD835\uDC1A\uD835\uDC25-login.com/x"
        assertNotNull(addressOf(bold))
        assertTrue(SignalCode.BRAND_LOOKALIKE in codesOf(bold))
        assertNull(addressOf("example.com."))
        assertNull(addressOf("\u6771\u4eac\u3002\u65e5\u672c"))
        assertNotNull(addressOf("\u6771\u4eac\u3002com/x"))
        assertNull(addressOf("\u6771\u4eac\u3002com"))
        assertNull(addressOf("listed%2Ecom/x"))
        assertNull(addressOf("lis%74ed.com/x"))
    }

    @Test
    fun `reserved and private-use suffixes count when a port or path follows`() {
        // `host:port` is already a url for the decoder; `host/path` is the text case.
        listOf("nas.local:5000/", "router.lan/admin", "printer.internal:631", "box.home.arpa/x").forEach {
            assertTrue(it, SignalCode.LOCAL_HOST in RiskAnalyzer.analyze(ok(it)).codes())
        }
        assertNotNull(addressOf("router.lan/admin"))
        assertNotNull(addressOf("box.home.arpa/x"))
        listOf("nas.local", "router.lan", "listed.example", "router/admin", "a.invalidtld/x").forEach {
            assertNull(it, addressOf(it))
        }
    }

    @Test
    fun `obfuscated ipv4 and userinfo are left alone, residual`() {
        listOf("0x7f.1/x", "2130706433/x", "127.1:80/x", "user@listed.com/x").forEach {
            assertNull(it, addressOf(it))
        }
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
