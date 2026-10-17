package com.lookfirst.domain.analysis

import com.lookfirst.domain.decoder.PayloadParser
import com.lookfirst.domain.model.ParseResult
import com.lookfirst.domain.model.Severity
import com.lookfirst.domain.model.SignalCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagingRiskAnalyzerTest {
    private fun codes(raw: String) = MessagingAnalyzer.on(raw).codes()

    @Test
    fun ussdCodesAreSuspicious() {
        val signals = MessagingAnalyzer.on("tel:*21*+33699999999#")
        assertEquals(Severity.SUSPICIOUS, signals.of(SignalCode.USSD_CODE).severity)
        assertEquals(setOf(SignalCode.USSD_CODE), codes("tel:*%2306%23"))
        assertEquals(setOf(SignalCode.USSD_CODE), codes("tel:%2321%23"))
        assertTrue(SignalCode.USSD_CODE in codes("sms:*100#"))
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
        assertTrue(codes("tel:0899123456").isEmpty())
        assertTrue(codes("tel:089123456").isEmpty())
        assertTrue(codes("tel:0912345678").isEmpty())
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
    fun shortCodeRuleDoesNotApplyToTel() {
        assertTrue(codes("tel:36789").isEmpty())
    }

    @Test
    fun riskAnalyzerKeepsAStableOrder() {
        val raw = "http://paypal.com@203.0.113.9:8080/​login"
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
                "‮".repeat(4000),
            )
        val start = System.nanoTime()
        inputs.forEach { RiskAnalyzer.analyze(PayloadParser.parse(it)) }
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("took $ms ms", ms < 2_000)
    }
}
