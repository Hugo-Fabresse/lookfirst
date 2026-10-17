package com.lookfirst.domain.analysis

import com.lookfirst.domain.decoder.PayloadParser
import com.lookfirst.domain.model.ParseResult
import com.lookfirst.domain.model.Severity
import com.lookfirst.domain.model.SignalCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagingRiskAnalyzerTest {
    private fun codes(raw: String, region: String? = null) = MessagingAnalyzer.on(raw, region).codes()

    @Test
    fun ussdCodesAreSuspicious() {
        val signals = MessagingAnalyzer.on("tel:*21*+33699999999#")
        assertEquals(Severity.SUSPICIOUS, signals.of(SignalCode.USSD_CODE).severity)
        assertEquals(setOf(SignalCode.USSD_CODE), codes("tel:*%2306%23"))
        assertEquals(setOf(SignalCode.USSD_CODE), codes("tel:%2321%23"))
        assertTrue(SignalCode.USSD_CODE in codes("sms:*100#"))
    }

    @Test
    fun ussdHiddenAfterASemicolonIsStillFound() {
        assertEquals(setOf(SignalCode.USSD_CODE), codes("tel:;*#06#"))
        assertEquals(setOf(SignalCode.USSD_CODE), codes("tel:+33612345678;*21*+33699999999#"))
        assertEquals(setOf(SignalCode.USSD_CODE), codes("tel:+33612345678;ext=%23"))
    }

    @Test
    fun nonAsciiDigitsAreFlaggedAndStillClassified() {
        val signals = MessagingAnalyzer.on("tel:+\uFF13\uFF13\uFF18\uFF19\uFF19123456")
        assertEquals(setOf(SignalCode.NON_ASCII_DIGITS, SignalCode.PREMIUM_NUMBER), signals.codes())
        assertEquals(Severity.SUSPICIOUS, signals.of(SignalCode.NON_ASCII_DIGITS).severity)
        assertEquals(setOf(SignalCode.NON_ASCII_DIGITS), codes("tel:+\u0663\u0663612345678"))
        assertTrue(SignalCode.NON_ASCII_DIGITS in codes("sms:+\uFF13\uFF13612345678"))
    }

    @Test
    fun shortTelNumbersAreCautionExceptEmergency() {
        assertEquals(setOf(SignalCode.SHORT_NUMBER), codes("tel:36789"))
        assertEquals(setOf(SignalCode.SHORT_NUMBER), codes("tel:3010"))
        assertEquals(setOf(SignalCode.SHORT_NUMBER), codes("tel:123"))
        listOf("tel:112", "tel:114", "tel:115", "tel:119", "tel:116000", "tel:116123", "tel:15", "tel:17", "tel:18")
            .forEach { assertTrue(it, codes(it).isEmpty()) }
        assertTrue(codes("tel:1234567").isEmpty())
    }

    @Test
    fun frenchRegionRecognisesNationalPremiumRanges() {
        listOf("tel:0899123456", "tel:0821123456", "tel:0811123456", "tel:118218")
            .forEach { assertEquals(it, setOf(SignalCode.PREMIUM_NUMBER), codes(it, "FR")) }
        assertTrue(codes("tel:0800123456", "FR").isEmpty())
        assertTrue(codes("tel:0123456789", "FR").isEmpty())
        assertTrue(codes("tel:0912345678", "FR").isEmpty())
        assertEquals(setOf(SignalCode.SHORT_NUMBER), codes("tel:3010", "FR"))
        assertEquals(setOf(SignalCode.PREMIUM_NUMBER), codes("tel:0899123456", "fr"))
    }

    @Test
    fun withoutRegionAZeroEightNineNumberIsOnlyAGuess() {
        val signals = MessagingAnalyzer.on("tel:0899123456")
        assertEquals(Severity.CAUTION, signals.of(SignalCode.POSSIBLE_PREMIUM_NUMBER).severity)
        assertTrue(codes("tel:0899123456", "DE").isEmpty())
        assertTrue(codes("tel:089123456").isEmpty())
    }

    @Test
    fun mailWithBccOrBodyIsPrefilled() {
        assertEquals(Severity.CAUTION, MessagingAnalyzer.on("mailto:a@example.com?bcc=x@example.org").single().severity)
        assertEquals(setOf(SignalCode.MAIL_PREFILLED), codes("mailto:a@example.com?body=Send%20your%20PIN"))
        assertTrue(codes("mailto:a@example.com?subject=Hello").isEmpty())
        assertTrue(codes("mailto:a@example.com").isEmpty())
    }

    @Test
    fun ordinaryNumbersHaveNoSignal() {
        assertTrue(codes("tel:+33 1 23 45 67 89").isEmpty())
        assertTrue(codes("tel:0123456789").isEmpty())
        assertTrue(codes("tel:112").isEmpty())
        assertTrue(codes("tel:+1-415-555-0100;ext=22").isEmpty())
        assertTrue(codes("sms:+33612345678").isEmpty())
    }

    @Test
    fun premiumInternationalRangesAreSuspicious() {
        listOf("tel:+33899123456", "tel:0033 899 12 34 56", "tel:+4490911234567", "tel:+19005551234", "tel:+882345678")
            .forEach { assertEquals(it, setOf(SignalCode.PREMIUM_NUMBER), codes(it)) }
    }

    @Test
    fun nationalNumbersAreNeverGuessedAsPremium() {
        assertTrue(codes("tel:0912345678").isEmpty())
        assertTrue(codes("tel:0123456789").isEmpty())
    }

    @Test
    fun smsShortCodeWithPrefilledBody() {
        val signals = MessagingAnalyzer.on("sms:36789?body=STOP%20ABO")
        assertEquals(setOf(SignalCode.SMS_SHORT_CODE, SignalCode.SMS_PREFILLED_BODY), signals.codes())
        assertEquals(Severity.CAUTION, signals.of(SignalCode.SMS_SHORT_CODE).severity)
        assertEquals("STOP ABO", signals.of(SignalCode.SMS_PREFILLED_BODY).evidence?.value)
    }

    @Test
    fun smsBodyAloneOrNumberAloneNeedsOnlyTheMatchingSignal() {
        assertEquals(setOf(SignalCode.SMS_PREFILLED_BODY), codes("sms:+33612345678?body=Hello"))
        assertEquals(setOf(SignalCode.SMS_SHORT_CODE), codes("sms:12345"))
        assertTrue(codes("sms:1234567").isEmpty())
        assertTrue(codes("sms:12").isEmpty())
        assertTrue(codes("sms:+33612345678?body=").isEmpty())
    }

    @Test
    fun smsShortCodeRuleHasItsOwnCodeApartFromTel() {
        assertEquals(setOf(SignalCode.SMS_SHORT_CODE), codes("sms:36789"))
        assertEquals(setOf(SignalCode.SHORT_NUMBER), codes("tel:36789"))
    }

    @Test
    fun riskAnalyzerKeepsAStableOrder() {
        val raw = "http://paypal.com@203.0.113.9:8080/\u200Blogin"
        val first = RiskAnalyzer.analyze(PayloadParser.parse(raw)).map { it.code }
        assertEquals(
            listOf(
                SignalCode.HIDDEN_CHARS,
                SignalCode.USERINFO_AT,
                SignalCode.IP_HOST,
                SignalCode.UNUSUAL_PORT,
                SignalCode.PLAIN_HTTP,
            ),
            first,
        )
        assertEquals(first, RiskAnalyzer.analyze(PayloadParser.parse(raw)).map { it.code })
    }

    @Test
    fun textAndUnknownAreUnparsableAndNeverClean() {
        val text = RiskAnalyzer.analyze(PayloadParser.parse("Call 0899 123 456 now"))
        assertEquals(setOf(SignalCode.UNPARSABLE), text.codes())
        assertEquals(setOf(SignalCode.UNPARSABLE), RiskAnalyzer.analyze(PayloadParser.parse("WIFI:T:WPA;;")).codes())
        assertEquals(setOf(SignalCode.UNPARSABLE), RiskAnalyzer.analyze(ParseResult.Empty).codes())
        assertEquals(setOf(SignalCode.UNPARSABLE), RiskAnalyzer.analyze(ParseResult.TooLarge(9999, 4096)).codes())
    }

    @Test
    fun cleanHttpsUrlHasNoSignalAtAll() {
        assertTrue(RiskAnalyzer.analyze(PayloadParser.parse("https://www.example.com/menu")).isEmpty())
    }

    @Test
    fun fakeParkingQrCombinesSignals() {
        val raw = "http://parking-pay.example:8080/?zone=12&p=" + "x".repeat(300)
        assertEquals(
            setOf(SignalCode.UNUSUAL_PORT, SignalCode.PLAIN_HTTP, SignalCode.LONG_URL),
            RiskAnalyzer.analyze(PayloadParser.parse(raw)).codes(),
        )
    }

    @Test
    fun hostileInputsAreFastAndNeverThrow() {
        val inputs =
            listOf(
                "a".repeat(100_000),
                "https://" + "a.".repeat(2000) + "com",
                "https://" + "@".repeat(4000),
                "https://x/" + "%".repeat(4000),
                "tel:" + "*#".repeat(2000),
                "sms:" + "9".repeat(4000) + "?body=" + "&".repeat(1000),
                "otpauth://totp/" + ":".repeat(300) + "?secret=A&" + "algorithm=x&".repeat(300),
                "ethereum:0x" + "f".repeat(3000) + "/transfer?uint256=" + "9".repeat(900),
                "BCD\n".repeat(500),
                "\u202E".repeat(4000),
            )
        val start = System.nanoTime()
        inputs.forEach { RiskAnalyzer.analyze(PayloadParser.parse(it)) }
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("took $ms ms", ms < 2_000)
    }
}
