package com.lookfirst.domain.verdict

import com.lookfirst.domain.model.ParseResult
import com.lookfirst.domain.model.ScanPayload
import com.lookfirst.domain.model.Severity
import com.lookfirst.domain.model.Signal

/** Merges signals into a level and ordered reasons. Pure, deterministic, no I/O. */
object VerdictEngine {
    /**
     * Level = heaviest severity, [Severity.INFO] excluded. Reasons: heaviest first, then the order the analyzers
     * produced them (stable), one per (code, evidence) pair (the first, so the heaviest, wins): the same code on two different pieces of
     * evidence (two hosts) stays twice. Content that was not understood
     * (text, unknown format, too large, empty) is never [VerdictLevel.NO_RISK_SIGNAL].
     */
    fun combine(signals: List<Signal>, result: ParseResult, payloadId: String): Verdict {
        val reasons = signals
            .withIndex()
            .sortedWith(compareBy({ it.value.severity.ordinal }, { it.index }))
            .map { it.value }
            .distinctBy { it.code to it.evidence }
        val level = reasons.firstOrNull()?.let { levelOf(it.severity) } ?: VerdictLevel.NO_RISK_SIGNAL
        val floor = if (isUnderstood(result)) VerdictLevel.NO_RISK_SIGNAL else VerdictLevel.CAUTION
        // VerdictLevel is declared heaviest first, so the smaller ordinal is the heavier level.
        return Verdict(if (level.ordinal <= floor.ordinal) level else floor, reasons, payloadId)
    }

    private fun levelOf(severity: Severity): VerdictLevel = when (severity) {
        Severity.DANGEROUS -> VerdictLevel.DANGEROUS
        Severity.SUSPICIOUS -> VerdictLevel.SUSPICIOUS
        Severity.CAUTION -> VerdictLevel.CAUTION
        Severity.INFO -> VerdictLevel.NO_RISK_SIGNAL
    }

    private fun isUnderstood(result: ParseResult): Boolean = result is ParseResult.Ok &&
        result.payload !is ScanPayload.Text &&
        result.payload !is ScanPayload.Unknown
}
