package com.lookfirst.domain.verdict

import com.lookfirst.domain.decoder.PayloadParser
import com.lookfirst.domain.model.ParseResult
import com.lookfirst.domain.model.Severity
import com.lookfirst.domain.model.Signal
import com.lookfirst.domain.model.SignalCode
import org.junit.Assert.assertEquals
import org.junit.Test

class VerdictEngineTest {
    private val url = PayloadParser.parse("https://example.com")
    private val text = PayloadParser.parse("hello")

    private fun combine(vararg codes: SignalCode, result: ParseResult = url) =
        VerdictEngine.combine(codes.map { Signal(it) }, result, "id")

    @Test
    fun `level is the heaviest severity`() {
        val table = listOf(
            listOf(SignalCode.SHORTENER) to VerdictLevel.CAUTION,
            listOf(SignalCode.SHORTENER, SignalCode.IP_HOST) to VerdictLevel.SUSPICIOUS,
            listOf(SignalCode.IP_HOST, SignalCode.DANGEROUS_SCHEME, SignalCode.SHORTENER) to VerdictLevel.DANGEROUS,
            listOf(SignalCode.REPUTATION_LISTED) to VerdictLevel.DANGEROUS,
            emptyList<SignalCode>() to VerdictLevel.NO_RISK_SIGNAL,
        )
        table.forEach { (codes, level) -> assertEquals(codes.toString(), level, combine(*codes.toTypedArray()).level) }
    }

    @Test
    fun `info alone is no risk signal but stays listed`() {
        val verdict = combine(SignalCode.REDIRECT_FINAL)
        assertEquals(VerdictLevel.NO_RISK_SIGNAL, verdict.level)
        assertEquals(listOf(SignalCode.REDIRECT_FINAL), verdict.reasons.map { it.code })
    }

    @Test
    fun `info does not change a caution`() {
        assertEquals(VerdictLevel.CAUTION, combine(SignalCode.REDIRECT_FINAL, SignalCode.PLAIN_HTTP).level)
    }

    @Test
    fun `text unknown too large and empty are at least caution`() {
        listOf(text, PayloadParser.parse("WIFI:"), ParseResult.TooLarge(9, 5), ParseResult.Empty).forEach {
            assertEquals(VerdictLevel.CAUTION, VerdictEngine.combine(emptyList(), it, "id").level)
        }
        assertEquals(VerdictLevel.DANGEROUS, combine(SignalCode.BIDI_IN_URL, result = text).level)
    }

    @Test
    fun `reasons are sorted by severity then stable order`() {
        val verdict = combine(
            SignalCode.SHORTENER,
            SignalCode.USERINFO_AT,
            SignalCode.REDIRECT_FINAL,
            SignalCode.DANGEROUS_SCHEME,
            SignalCode.IP_HOST,
            SignalCode.PLAIN_HTTP,
        )
        assertEquals(
            listOf(
                SignalCode.DANGEROUS_SCHEME,
                SignalCode.USERINFO_AT,
                SignalCode.IP_HOST,
                SignalCode.SHORTENER,
                SignalCode.PLAIN_HTTP,
                SignalCode.REDIRECT_FINAL,
            ),
            verdict.reasons.map { it.code },
        )
    }

    @Test
    fun `duplicate codes keep the heaviest then the first`() {
        val signals = listOf(
            Signal(SignalCode.SHORTENER, count = 1),
            Signal(SignalCode.SHORTENER, Severity.SUSPICIOUS, count = 2),
            Signal(SignalCode.SHORTENER, count = 3),
        )
        val verdict = VerdictEngine.combine(signals, url, "id")
        assertEquals(1, verdict.reasons.size)
        assertEquals(2, verdict.reasons.single().count)
        assertEquals(VerdictLevel.SUSPICIOUS, verdict.level)
    }

    @Test
    fun `payload id is kept and result is deterministic`() {
        val a = VerdictEngine.combine(listOf(Signal(SignalCode.IP_HOST)), url, "p1")
        assertEquals("p1", a.payloadId)
        assertEquals(a, VerdictEngine.combine(listOf(Signal(SignalCode.IP_HOST)), url, "p1"))
    }
}
