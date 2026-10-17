package com.lookfirst.domain.analysis

import com.lookfirst.domain.host.HostNormalizer
import com.lookfirst.domain.host.HostResult
import com.lookfirst.domain.host.PublicSuffixList
import com.lookfirst.domain.model.LookalikeKind
import com.lookfirst.domain.model.Severity
import com.lookfirst.domain.model.Signal
import com.lookfirst.domain.model.SignalCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LookalikeAnalyzerTest {
    private fun lookalike(raw: String) = LookalikeAnalyzer.on(raw)

    private fun all(raw: String) = RiskAnalyzer.analyze(ok(raw))

    private fun kind(signals: List<Signal>) = signals.of(SignalCode.BRAND_LOOKALIKE).details.single()

    // Further look-alike shapes.

    @Test
    fun digitsAndSequencesLookLikeLetters() {
        assertEquals(LookalikeKind.HOMOGLYPH, kind(lookalike("https://paypa1.com")))
        assertEquals(LookalikeKind.HOMOGLYPH, kind(lookalike("https://arnazon.com")))
        assertEquals(LookalikeKind.HOMOGLYPH, kind(lookalike("https://micros0ft.com")))
    }

    @Test
    fun smallTyposOfDistinctiveNames() {
        assertEquals(LookalikeKind.TYPO, kind(lookalike("https://paypall.com")))
        assertEquals(LookalikeKind.TYPO, kind(lookalike("https://amazom.fr")))
        assertEquals(LookalikeKind.TYPO, kind(lookalike("https://goggle.com")))
        assertEquals(LookalikeKind.TYPO, kind(lookalike("https://pay-pal.com")))
        assertEquals(LookalikeKind.TYPO, kind(lookalike("https://payapl.com")))
        assertEquals(LookalikeKind.TYPO, kind(lookalike("https://societe-generale.fr")))
        assertEquals(LookalikeKind.TYPO, kind(lookalike("https://impots-gouv.fr")))
    }

    @Test
    fun brandNextToALureWordIsCombined() {
        assertEquals(LookalikeKind.COMBINED, kind(lookalike("https://secure-paypal.com")))
        assertEquals(LookalikeKind.COMBINED, kind(lookalike("https://apple-support.com")))
        assertEquals(LookalikeKind.COMBINED, kind(lookalike("https://netflix.account-verify.example")))
        assertEquals(LookalikeKind.COMBINED, kind(lookalike("https://login.paypal.evil.example/")))
        assertEquals(LookalikeKind.COMBINED, kind(lookalike("https://paypal.evil.example/")))
        assertEquals(LookalikeKind.COMBINED, kind(lookalike("https://ameli-remboursement.example")))
    }

    @Test
    fun brandNameOnAPlatformAnyoneCanUseIsReported() {
        assertEquals(LookalikeKind.COMBINED, kind(lookalike("https://paypal.blogspot.com")))
        assertEquals(LookalikeKind.COMBINED, kind(lookalike("https://netflix.github.io")))
    }

    @Test
    fun legitimateLookingHostsAreNotReported() {
        listOf(
            "https://github.com/paypal/repo",
            "https://www.wikipedia.org/",
            "https://www.lemonde.fr/",
            "https://example.com/",
            "https://docs.google.com/document",
            "https://accounts.google.com/",
            "https://www.google.de/",
            "https://www.amazon.co.uk/",
            "https://aws.amazon.com/",
            "https://store.steampowered.com/",
            "https://apple.stackexchange.com/",
            "https://apple-pie.example/",
            "https://paypal-donations.example/",
            "https://stackoverflow.com/",
            "https://www.booking.com/",
            "https://mon-orange-fr.example/",
            "https://bücher.example/",
            "https://münchen.example/",
            "https://日本語.example/",
            "https://東京tokyo.example/",
            "https://example.co.uk/",
            "https://user.github.io/",
            "https://[2001:db8::1]/",
        ).forEach { assertTrue(it, SignalCode.BRAND_LOOKALIKE !in lookalike(it).codes()) }
    }

    @Test
    fun mixedScriptRules() {
        val greek = lookalike("https://αpple.example")
        assertTrue(SignalCode.MIXED_SCRIPT in greek.codes())
        assertEquals(Severity.SUSPICIOUS, greek.of(SignalCode.MIXED_SCRIPT).severity)
        assertEquals("αpple", greek.of(SignalCode.MIXED_SCRIPT).evidence?.value)
        assertTrue(SignalCode.MIXED_SCRIPT !in lookalike("https://ひらabc.example").codes()) // Latin + Hiragana
        assertTrue(SignalCode.MIXED_SCRIPT !in lookalike("https://東京トabc.example").codes())
        assertTrue(SignalCode.MIXED_SCRIPT !in lookalike("https://مثال.example").codes()) // Arabic
        assertTrue(SignalCode.MIXED_SCRIPT in lookalike("https://ひ한.example").codes()) // Hiragana + Hangul
    }

    @Test
    fun purelyCyrillicLabelThatSpellsABrandIsStillCaught() {
        val signals = lookalike("https://аррӏе.example") // аррӏе
        assertTrue(SignalCode.MIXED_SCRIPT !in signals.codes())
        assertEquals(LookalikeKind.HOMOGLYPH, kind(signals))
        assertEquals("apple.com", signals.of(SignalCode.BRAND_LOOKALIKE).evidence?.value)
    }

    @Test
    fun hostThatCannotBeNormalizedIsIncompleteAndMalformed() {
        val signals = all("https://bit.ly＠evil.example/x")
        assertTrue(SignalCode.ANALYSIS_INCOMPLETE in signals.codes())
        assertTrue(SignalCode.URL_MALFORMED in signals.codes())
        assertTrue(SignalCode.SHORTENER !in signals.codes())
        assertTrue(SignalCode.BRAND_LOOKALIKE !in signals.codes())
        assertEquals(setOf(SignalCode.ANALYSIS_INCOMPLETE, SignalCode.URL_MALFORMED), signals.codes())
    }

    @Test
    fun otherPayloadsAreIgnored() {
        assertTrue(lookalike("WIFI:T:nopass;S:x;;").isEmpty())
        assertTrue(lookalike("tel:+33123456789").isEmpty())
    }

    @Test
    fun signalsNeverPrintTheHost() {
        val text = lookalike("https://paypa1.com").joinToString { it.toString() }
        assertTrue("paypa1" !in text)
    }

    @Test
    fun brandListIsConsistent() {
        val suffixes = PublicSuffixList.instance!!
        assertTrue(BrandList.brands.size >= 200)
        for (brand in BrandList.brands) {
            assertTrue(brand.keywords.isNotEmpty() && brand.domains.isNotEmpty())
            brand.keywords.forEach { assertTrue(it.text, it.text.matches(Regex("[a-z0-9-]+"))) }
            brand.domains.forEach { domain ->
                val ascii = (HostNormalizer.normalize(domain) as HostResult.Normalized).host.ascii
                assertEquals(
                    "official domain must be a registrable domain: $domain",
                    ascii,
                    suffixes.registrableDomain(ascii),
                )
                assertEquals(domain, ascii)
            }
        }
        assertNull(BrandList.brands.groupBy { it.primaryDomain }.values.firstOrNull { it.size > 1 })
    }

    @Test
    fun pathologicalHostsAreFast() {
        val label = "a".repeat(60)
        val started = System.nanoTime()
        val hosts =
            listOf(
                "https://" + (0 until 4).joinToString(".") { label } + ".com/",
                "https://" + "paypal-".repeat(30) + "login.com/",
                "https://" + "xn--" + "a-".repeat(20) + ".com/",
                "https://" + "a.".repeat(120) + "com/",
                "https://" + "а".repeat(60) + ".com/",
            )
        hosts.forEach { all(it) }
        assertTrue("took ${(System.nanoTime() - started) / 1_000_000} ms", System.nanoTime() - started < 3_000_000_000L)
    }
}
