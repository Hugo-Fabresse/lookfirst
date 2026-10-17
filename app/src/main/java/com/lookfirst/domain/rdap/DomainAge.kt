package com.lookfirst.domain.rdap

import com.lookfirst.domain.model.Severity
import com.lookfirst.domain.model.Signal
import com.lookfirst.domain.model.SignalCode

/** Milliseconds in a day. */
private const val DAY_MILLIS = 86_400_000L

/**
 * How a registration age weighs (decision D-rdap, threat model T-NET-5). A new domain is not malicious in itself,
 * but most phishing domains are days old: under [SUSPICIOUS_BELOW_DAYS] days it is SUSPICIOUS, from there to
 * [CAUTION_BELOW_DAYS] days it is CAUTION, older says nothing (a benign answer proves nothing: an old domain can be
 * compromised). Day 0 is the day of registration.
 */
object DomainAge {
    const val SUSPICIOUS_BELOW_DAYS = 30
    const val CAUTION_BELOW_DAYS = 90

    /**
     * Whole days from [registeredAtMillis] to [nowMillis]. A date in the future (a clock a little behind) is day 0;
     * the caller refuses dates that are far in the future before it gets here.
     */
    fun daysBetween(registeredAtMillis: Long, nowMillis: Long): Int {
        val days = Math.floorDiv(nowMillis - registeredAtMillis, DAY_MILLIS)
        return days.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }

    /** The signal for a domain registered [ageDays] ago, or null when it is old enough to say nothing. */
    fun signalFor(ageDays: Int): Signal? = when {
        ageDays < SUSPICIOUS_BELOW_DAYS -> Signal(SignalCode.DOMAIN_RECENT, Severity.SUSPICIOUS, count = ageDays)
        ageDays < CAUTION_BELOW_DAYS -> Signal(SignalCode.DOMAIN_RECENT, Severity.CAUTION, count = ageDays)
        else -> null
    }
}
