package com.lookfirst.domain.analysis

import com.lookfirst.domain.host.NormalizedHost
import com.lookfirst.domain.model.ParseResult
import com.lookfirst.domain.model.ScanPayload
import com.lookfirst.domain.model.Signal
import com.lookfirst.domain.model.SignalCode
import com.lookfirst.domain.model.Untrusted
import com.lookfirst.domain.open.effectiveWebScheme
import com.lookfirst.domain.open.openedHost

/** Lists older than this are a CAUTION reason (D16). */
const val LISTS_STALE_AFTER_DAYS = 14

/**
 * Reputation signals for every URL that Open sends to a browser (a scheme-less one is opened as https), looked up on
 * the host that is opened. Other content has no host to look up.
 */
class ReputationAnalyzer(private val lookup: ReputationLookup) : SignalAnalyzer {
    override fun analyze(result: ParseResult.Ok, context: AnalysisContext): List<Signal> {
        val payload = result.payload as? ScanPayload.Url ?: return emptyList()
        if (payload.effectiveWebScheme() == null) return emptyList()
        val host = payload.openedHost() ?: return emptyList()
        return signalsFor(safeLookup(host))
    }

    private fun safeLookup(host: NormalizedHost): ReputationResult? = try {
        lookup.lookup(host)
    } catch (@Suppress("TooGenericExceptionCaught") ignored: Exception) {
        // Nothing is logged: the host comes from a scanned code. A failing lookup is never a clean result.
        null
    }

    private fun signalsFor(found: ReputationResult?): List<Signal> = when (found) {
        null -> listOf(Signal(SignalCode.LISTS_UNAVAILABLE))
        is ReputationResult.Listed ->
            listOf(Signal(SignalCode.REPUTATION_LISTED, evidence = Untrusted(found.source)))
        ReputationResult.NotListed -> emptyList()
        ReputationResult.ListsMissing -> listOf(Signal(SignalCode.LISTS_MISSING))
        is ReputationResult.ListsStale ->
            if (found.ageDays > LISTS_STALE_AFTER_DAYS || found.ageDays < 0) {
                listOf(Signal(SignalCode.LISTS_STALE, count = found.ageDays))
            } else {
                emptyList()
            }
    }
}
