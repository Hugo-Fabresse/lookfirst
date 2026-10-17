package com.lookfirst.domain.usecase

import com.lookfirst.domain.analysis.ReputationLookup
import com.lookfirst.domain.analysis.ReputationResult
import com.lookfirst.domain.model.Severity
import com.lookfirst.domain.model.SignalCode
import com.lookfirst.domain.rdap.DomainAge
import com.lookfirst.domain.rdap.RdapFailure
import com.lookfirst.domain.rdap.RdapOutcome
import com.lookfirst.domain.verdict.Verdict
import com.lookfirst.domain.verdict.VerdictLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplyDomainAgeTest {
    private val analyze = AnalyzeScan(
        ReputationLookup {
            if (it.ascii == "evil.example") ReputationResult.Listed("test") else ReputationResult.NotListed
        },
    )
    private val apply = ApplyDomainAge()

    private fun scanned(raw: String): Verdict = analyze(raw, "payload-1")

    private fun registered(days: Int) = RdapOutcome.Registered(registeredAtMillis = 0L, ageDays = days)

    private fun codes(verdict: Verdict) = verdict.reasons.map { it.code }

    @Test
    fun `the thresholds are 30 and 90 days`() {
        assertEquals(Severity.SUSPICIOUS, DomainAge.signalFor(0)?.severity)
        assertEquals(Severity.SUSPICIOUS, DomainAge.signalFor(29)?.severity)
        assertEquals(Severity.CAUTION, DomainAge.signalFor(30)?.severity)
        assertEquals(Severity.CAUTION, DomainAge.signalFor(89)?.severity)
        assertNull(DomainAge.signalFor(90))
        assertNull(DomainAge.signalFor(10_000))
        assertEquals(12, DomainAge.signalFor(12)?.count)
    }

    @Test
    fun `days are whole days, never negative`() {
        val day = 86_400_000L
        assertEquals(0, DomainAge.daysBetween(1_000, 1_000))
        assertEquals(0, DomainAge.daysBetween(0, day - 1))
        assertEquals(1, DomainAge.daysBetween(0, day))
        assertEquals(0, DomainAge.daysBetween(day, 0))
        assertEquals(365, DomainAge.daysBetween(0, 365 * day + 5))
    }

    @Test
    fun `a domain registered 3 days ago makes a clean link suspicious`() {
        val before = scanned("https://a.example/")
        assertEquals(VerdictLevel.NO_RISK_SIGNAL, before.level)
        val after = apply(before, registered(3))
        assertEquals(VerdictLevel.SUSPICIOUS, after.level)
        assertEquals(SignalCode.DOMAIN_RECENT, after.reasons.first().code)
        assertEquals(3, after.reasons.first().count)
        assertEquals(before.payloadId, after.payloadId)
    }

    @Test
    fun `between 30 and 90 days the level is caution`() {
        assertEquals(VerdictLevel.CAUTION, apply(scanned("https://a.example/"), registered(45)).level)
    }

    @Test
    fun `an old domain changes nothing and is not called safe`() {
        val before = scanned("https://a.example/")
        val after = apply(before, registered(4_000))
        assertEquals(before, after)
        assertEquals(VerdictLevel.NO_RISK_SIGNAL, after.level)
    }

    @Test
    fun `a failure leaves the very same verdict`() {
        val before = scanned("https://a.example/")
        assertSame(before, apply(before, RdapOutcome.Failed(RdapFailure.TIMEOUT)))
    }

    @Test
    fun `an unknown domain is information that does not move the level`() {
        val before = scanned("https://a.example/")
        val after = apply(before, RdapOutcome.NotFound)
        assertEquals(before.level, after.level)
        val reason = after.reasons.single { it.code == SignalCode.DOMAIN_UNKNOWN }
        assertEquals(Severity.INFO, reason.severity)
    }

    @Test
    fun `the level never falls below what the earlier analysis said`() {
        val listed = scanned("https://evil.example/")
        assertEquals(VerdictLevel.DANGEROUS, listed.level)
        for (outcome in listOf(registered(2), registered(60), registered(5_000), RdapOutcome.NotFound)) {
            val after = apply(listed, outcome)
            assertEquals(VerdictLevel.DANGEROUS, after.level)
            assertNotNull(after.reasons.firstOrNull { it.code == SignalCode.REPUTATION_LISTED })
        }
    }

    @Test
    fun `no earlier signal is removed`() {
        val before = scanned("http://a.example:8080/")
        val after = apply(before, registered(10))
        assertTrue(codes(before).all { it in codes(after) })
    }

    @Test
    fun `a second answer replaces the first one and cannot lower the level`() {
        val first = apply(scanned("https://a.example/"), registered(3))
        val second = apply(first, registered(60))
        assertEquals(1, second.reasons.count { it.code == SignalCode.DOMAIN_RECENT })
        assertEquals(VerdictLevel.SUSPICIOUS, second.level)
    }
}
