package com.lookfirst.domain.usecase

import com.lookfirst.domain.model.Signal
import com.lookfirst.domain.model.SignalCode
import com.lookfirst.domain.rdap.DomainAge
import com.lookfirst.domain.rdap.RdapOutcome
import com.lookfirst.domain.verdict.Verdict
import com.lookfirst.domain.verdict.VerdictEngine
import javax.inject.Inject

private val DOMAIN_AGE_CODES = setOf(SignalCode.DOMAIN_RECENT, SignalCode.DOMAIN_UNKNOWN)

/**
 * A registry answer becomes a NEW verdict bound to the same payload (ux.md 3.7): the signal of the answer is added
 * to those already found and the level can only stay or go up. An old domain adds nothing (it proves nothing: an old
 * domain can be compromised), a failure changes nothing, and nothing is ever removed. Pure and synchronous.
 */
class ApplyDomainAge @Inject constructor() {
    operator fun invoke(previous: Verdict, outcome: RdapOutcome): Verdict {
        val added = signalOf(outcome) ?: return previous
        val kept = previous.reasons.filterNot { it.code in DOMAIN_AGE_CODES }
        val merged = VerdictEngine.combine(kept + added, understood = true, previous.payloadId)
        // Heavier levels have the smaller ordinal: never let the level fall below what the earlier analysis said.
        val level = if (merged.level.ordinal <= previous.level.ordinal) merged.level else previous.level
        return merged.copy(level = level)
    }

    private fun signalOf(outcome: RdapOutcome): Signal? = when (outcome) {
        is RdapOutcome.Registered -> DomainAge.signalFor(outcome.ageDays)
        RdapOutcome.NotFound -> Signal(SignalCode.DOMAIN_UNKNOWN)
        is RdapOutcome.Failed -> null
    }
}
