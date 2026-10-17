package com.lookfirst.domain.analysis

import com.lookfirst.domain.model.LookalikeKind
import com.lookfirst.domain.model.MixedScripts
import com.lookfirst.domain.model.Severity
import com.lookfirst.domain.model.Signal
import com.lookfirst.domain.model.SignalCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The acceptance cases of LF-16 (notes of LF-15), through the whole analysis. */
class HostAcceptanceTest {
    private fun all(raw: String) = RiskAnalyzer.analyze(ok(raw))

    private fun kind(signals: List<Signal>) = signals.of(SignalCode.BRAND_LOOKALIKE).details.single()

    @Test
    fun combosquattingOfAnOfficialDomain() {
        val signals = all("https://paypal.com.secure-login.example/login")
        val signal = signals.of(SignalCode.BRAND_LOOKALIKE)
        assertEquals("paypal.com", signal.evidence?.value)
        assertEquals(LookalikeKind.COMBINED, kind(signals))
        assertEquals(Severity.SUSPICIOUS, signal.severity)
        assertTrue(SignalCode.ANALYSIS_INCOMPLETE !in signals.codes())
    }

    @Test
    fun fullwidthShortenerIsSeenAsBitLy() {
        val signals = all("https://ｂｉｔ.ｌｙ/x")
        assertEquals("bit.ly", signals.of(SignalCode.SHORTENER).evidence?.value)
        assertEquals(setOf(SignalCode.SHORTENER), signals.codes())
    }

    @Test
    fun ideographicTrailingDotShortener() {
        assertEquals(setOf(SignalCode.SHORTENER), all("https://bit.ly。/x").codes())
    }

    @Test
    fun fullwidthIpAddress() {
        val signals = all("https://１２７.０.０.１/")
        assertEquals("127.0.0.1", signals.of(SignalCode.IP_HOST).evidence?.value)
        assertEquals(setOf(SignalCode.IP_HOST), signals.codes())
    }

    @Test
    fun percentEncodedOfficialHostIsEncodedButNotALookalike() {
        val codes = all("https://%70aypal.com").codes()
        assertEquals(setOf(SignalCode.URL_HOST_ENCODED), codes)
    }

    @Test
    fun cyrillicHomographMixesScriptsAndImitatesPaypal() {
        val signals = all("https://раураl.com")
        assertTrue(SignalCode.MIXED_SCRIPT in signals.codes())
        assertEquals(LookalikeKind.HOMOGLYPH, kind(signals))
        assertEquals("paypal.com", signals.of(SignalCode.BRAND_LOOKALIKE).evidence?.value)
        assertTrue(SignalCode.ANALYSIS_INCOMPLETE !in signals.codes())
        val scripts = signals.of(SignalCode.MIXED_SCRIPT)
        assertEquals(listOf(MixedScripts(listOf("Cyrillic", "Latin"))), scripts.details)
        assertEquals(2, scripts.count)
    }

    @Test
    fun punycodeSpellingOfTheSameHomograph() {
        val signals = all("https://xn--pypal-4ve.com")
        assertEquals(setOf(SignalCode.MIXED_SCRIPT, SignalCode.BRAND_LOOKALIKE), signals.codes())
        assertEquals(LookalikeKind.HOMOGLYPH, kind(signals))
    }

    @Test
    fun officialHostsGetNoSignal() {
        assertTrue(all("https://www.paypal.com").isEmpty())
        assertTrue(all("https://impots.gouv.fr").isEmpty())
        assertTrue(all("https://www.impots.gouv.fr/accueil").isEmpty())
        assertTrue(all("https://PAYPAL.COM./login").isEmpty())
    }

    @Test
    fun hyphenatedFrenchPublicServiceOnAnotherDomain() {
        val signals = all("https://impots-gouv.fr.example")
        assertEquals("impots.gouv.fr", signals.of(SignalCode.BRAND_LOOKALIKE).evidence?.value)
        assertEquals(LookalikeKind.COMBINED, kind(signals))
    }
}
