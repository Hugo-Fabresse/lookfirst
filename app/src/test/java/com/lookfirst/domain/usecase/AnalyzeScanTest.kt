package com.lookfirst.domain.usecase

import com.lookfirst.domain.analysis.ReputationLookup
import com.lookfirst.domain.analysis.ReputationResult
import com.lookfirst.domain.model.Severity
import com.lookfirst.domain.model.SignalCode
import com.lookfirst.domain.verdict.VerdictLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
}
