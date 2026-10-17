package com.lookfirst.ui.screens.result

import android.content.res.Configuration
import android.content.res.Resources
import com.lookfirst.R
import com.lookfirst.domain.model.LookalikeKind
import com.lookfirst.domain.model.MixedScripts
import com.lookfirst.domain.model.OtpParam
import com.lookfirst.domain.model.PaymentProblem
import com.lookfirst.domain.model.Signal
import com.lookfirst.domain.model.SignalCode
import com.lookfirst.domain.model.Untrusted
import com.lookfirst.domain.verdict.VerdictLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.Locale

/** Every reason code has a short label and a sentence in French and English, with its details translated. */
@RunWith(RobolectricTestRunner::class)
class ReasonTextsTest {
    private companion object {
        val PLACEHOLDER = Regex("%\\d")
    }

    private fun resources(language: String): Resources {
        val app = RuntimeEnvironment.getApplication()
        val configuration = Configuration(app.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(language))
        }
        return app.createConfigurationContext(configuration).resources
    }

    private val french get() = resources("fr")
    private val english get() = resources("en")

    /** A signal with every kind of data the UI may read, to exercise every placeholder. */
    private fun sample(code: SignalCode, kind: LookalikeKind? = null) = Signal(
        code = code,
        evidence = Untrusted("example.org"),
        count = 3,
        details = listOfNotNull<com.lookfirst.domain.model.SignalDetail>(kind) +
            PaymentProblem.entries + OtpParam.entries + MixedScripts(listOf("Latin", "Cyrillic")),
    )

    @Test
    fun `a private target that is the scanned address does not say redirect`() {
        val direct = Signal(SignalCode.REDIRECT_PRIVATE, evidence = Untrusted("10.0.0.1"), count = 0)
        val viaHop = direct.copy(count = 2)
        val en = ReasonTexts(english)
        assertTrue(en.sentence(direct).text.startsWith("This link points to"))
        assertTrue(en.sentence(viaHop).text.startsWith("This link redirects to"))
        assertTrue(ReasonTexts(french).sentence(direct).text.startsWith("Ce lien pointe vers"))
    }

    @Test
    fun everySignalCodeHasItsTexts() {
        assertEquals(emptyList<SignalCode>(), codesWithoutTexts())
    }

    @Test
    fun everyCodeHasAShortLabelAndASentenceInFrenchAndEnglish() {
        for (code in SignalCode.entries) {
            val short = checkNotNull(code.reasonRes()).shortLabel
            val fr = french.getString(short)
            val en = english.getString(short)
            assertTrue("$code short fr", fr.isNotBlank())
            assertTrue("$code short en", en.isNotBlank())
            assertNotEquals("$code short is not translated", en, fr)
            val frSentence = ReasonTexts(french).sentence(sample(code)).text
            val enSentence = ReasonTexts(english).sentence(sample(code)).text
            assertTrue("$code sentence fr", frSentence.isNotBlank())
            assertTrue("$code sentence en", enSentence.isNotBlank())
            assertNotEquals("$code sentence is not translated", enSentence, frSentence)
            assertFalse("$code placeholder left in fr", PLACEHOLDER.containsMatchIn(frSentence))
            assertFalse("$code placeholder left in en", PLACEHOLDER.containsMatchIn(enSentence))
        }
    }

    @Test
    fun noTextEverSaysSafe() {
        val forbidden = listOf("safe", "sûr", "sure", "secure", "sécurisé")
        for (language in listOf("fr", "en")) {
            val texts = ReasonTexts(resources(language))
            for (code in SignalCode.entries) {
                val all = (texts.shortLabel(sample(code)) + " " + texts.sentence(sample(code)).text).lowercase()
                for (word in forbidden) assertFalse("$code ($language) says $word", all.contains("$word "))
            }
        }
    }

    @Test
    fun everyBrandLookalikeKindHasItsOwnSentenceInBothLanguages() {
        for (kind in LookalikeKind.entries) {
            val fr = ReasonTexts(french).sentence(sample(SignalCode.BRAND_LOOKALIKE, kind)).text
            val en = ReasonTexts(english).sentence(sample(SignalCode.BRAND_LOOKALIKE, kind)).text
            assertTrue(fr.contains("example.org"))
            assertTrue(en.contains("example.org"))
            assertNotEquals(fr, en)
        }
        val sentences = LookalikeKind.entries.map {
            ReasonTexts(english).sentence(sample(SignalCode.BRAND_LOOKALIKE, it)).text
        }
        assertEquals(LookalikeKind.entries.size, sentences.toSet().size)
    }

    @Test
    fun typedDetailsAreTranslatedNotPrintedAsEnumNames() {
        val payment = ReasonTexts(french).sentence(
            Signal(SignalCode.PAYMENT_MISMATCH, details = listOf(PaymentProblem.IBAN, PaymentProblem.NAME)),
        ).text
        assertTrue(payment.contains("IBAN, bénéficiaire manquant"))
        val otp = ReasonTexts(english).sentence(
            Signal(SignalCode.OTPAUTH_ODD_PARAMS, details = listOf(OtpParam.DIGITS, OtpParam.PERIOD)),
        ).text
        assertTrue(otp.contains("number of digits, period"))
        val scripts = ReasonTexts(french).sentence(
            Signal(
                SignalCode.MIXED_SCRIPT,
                evidence = Untrusted("pаypal"),
                details = listOf(MixedScripts(listOf("Latin", "Cyrillic"))),
            ),
        ).text
        assertTrue(scripts.contains("latin, cyrillique"))
    }

    @Test
    fun pluralSentencesFollowTheCount() {
        val one = ReasonTexts(english).sentence(Signal(SignalCode.LISTS_STALE, count = 1)).text
        val many = ReasonTexts(english).sentence(Signal(SignalCode.LISTS_STALE, count = 20)).text
        assertTrue(one.contains("1 day old"))
        assertTrue(many.contains("20 days old"))
    }

    @Test
    fun evidenceIsCutAndItsInvisibleCharactersBecomeTokens() {
        val hostile = "a".repeat(500) + "‮"
        val sentence = ReasonTexts(english).sentence(Signal(SignalCode.IP_HOST, evidence = Untrusted(hostile))).text
        assertTrue(sentence.length < 300)
        assertFalse(sentence.contains('‮'))
        val token = ReasonTexts(english).sentence(
            Signal(SignalCode.IP_HOST, evidence = Untrusted("1.2‮.3.4")),
        ).text
        assertTrue(token.contains("<U+202E>"))
        assertFalse(token.contains('‮'))
    }

    @Test
    fun aMissingEvidenceIsSaidAsUnknownNotLeftBlank() {
        val sentence = ReasonTexts(english).sentence(Signal(SignalCode.OTPAUTH_ENROL)).text
        assertTrue(sentence.contains("unknown"))
    }

    @Test
    fun everyLevelHasALabelInBothLanguages() {
        for (level in VerdictLevel.entries) {
            assertTrue(french.getString(level.label()).isNotBlank())
            assertNotEquals(english.getString(level.label()), french.getString(level.label()))
        }
        assertEquals("Aucun signal de risque détecté", french.getString(R.string.level_no_risk_signal))
    }
}
