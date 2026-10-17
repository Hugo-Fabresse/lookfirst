package com.lookfirst.domain.usecase

import com.lookfirst.domain.analysis.AnalysisContext
import com.lookfirst.domain.model.Signal
import com.lookfirst.domain.model.SignalCode
import com.lookfirst.domain.model.Untrusted
import com.lookfirst.domain.redirect.RedirectTrace
import com.lookfirst.domain.redirect.TraceEnd
import com.lookfirst.domain.verdict.Verdict
import com.lookfirst.domain.verdict.VerdictEngine
import javax.inject.Inject

/** Longest piece of a hostile `Location` kept as evidence. */
private const val MAX_LOCATION_EVIDENCE = 200

private val REDIRECT_CODES = setOf(
    SignalCode.REDIRECT_HTTP,
    SignalCode.REDIRECT_FINAL,
    SignalCode.REDIRECT_PRIVATE,
    SignalCode.REDIRECT_EXCESSIVE,
)

/**
 * A redirect trace becomes a NEW verdict bound to the same payload (ux.md 3.7): every address on the chain goes
 * through the same analysis as the scanned one, the signals are added to those already found, and the level can
 * only stay or go up. A benign answer proves nothing (servers can serve a scanner something else), so nothing is ever
 * removed. Pure and synchronous (the analysis is CPU-bound); the caller runs it off the main thread.
 */
class ApplyRedirectTrace @Inject constructor(private val analyzeScan: AnalyzeScan) {
    operator fun invoke(
        previous: Verdict,
        trace: RedirectTrace,
        context: AnalysisContext = AnalysisContext(),
    ): Verdict {
        val kept = previous.reasons.filterNot { it.code in REDIRECT_CODES }
        val addresses = trace.hops.map { it.value } + extraTargets(trace.end)
        val seen = addresses.flatMap { analyze(it, previous.payloadId, context) }
        val merged = VerdictEngine.combine(kept + seen + endSignals(trace), understood = true, previous.payloadId)
        // Heavier levels have the smaller ordinal: never let the level fall below what the local analysis said.
        val level = if (merged.level.ordinal <= previous.level.ordinal) merged.level else previous.level
        return merged.copy(level = level)
    }

    private fun analyze(address: String, payloadId: String, context: AnalysisContext): List<Signal> =
        analyzeScan(address, payloadId, context).reasons

    /**
     * The `http://` address, the blocked one and the unusable `Location` are not hops, but they are on the chain:
     * they are analysed too (a `javascript:` location is a DANGEROUS_SCHEME).
     */
    private fun extraTargets(end: TraceEnd): List<String> = when (end) {
        is TraceEnd.InsecureHop -> listOf(end.target.value)
        is TraceEnd.BlockedAddress -> listOf(end.target.value)
        is TraceEnd.BadLocation -> listOf(end.raw.value)
        else -> emptyList()
    }

    private fun endSignals(trace: RedirectTrace): List<Signal> = when (val end = trace.end) {
        TraceEnd.Final -> listOf(
            Signal(SignalCode.REDIRECT_FINAL, evidence = Untrusted(trace.last.value), count = trace.hops.size),
        )
        is TraceEnd.InsecureHop -> listOf(Signal(SignalCode.REDIRECT_HTTP, evidence = Untrusted(end.target.value)))
        is TraceEnd.BlockedAddress ->
            listOf(Signal(SignalCode.REDIRECT_PRIVATE, evidence = Untrusted(end.target.value)))
        is TraceEnd.BadLocation -> listOf(
            Signal(SignalCode.URL_MALFORMED, evidence = Untrusted(end.raw.value.take(MAX_LOCATION_EVIDENCE))),
        )
        TraceEnd.Loop, TraceEnd.TooManyHops -> listOf(Signal(SignalCode.REDIRECT_EXCESSIVE, count = trace.hops.size))
    }
}
