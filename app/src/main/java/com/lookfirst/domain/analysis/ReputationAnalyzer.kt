package com.lookfirst.domain.analysis

import com.lookfirst.domain.host.HostResult
import com.lookfirst.domain.model.ParseResult
import com.lookfirst.domain.model.ScanPayload
import com.lookfirst.domain.model.Signal
import com.lookfirst.domain.model.SignalCode
import com.lookfirst.domain.model.Untrusted

/** Lists older than this are a CAUTION reason (D16). */
const val LISTS_STALE_AFTER_DAYS = 14

/** Reputation signals for http(s) URLs with a normalized host. Other content has no host to look up. */
class ReputationAnalyzer(private val lookup: ReputationLookup) : SignalAnalyzer {
    override fun analyze(result: ParseResult.Ok, context: AnalysisContext): List<Signal> {
        val payload = result.payload as? ScanPayload.Url ?: return emptyList()
        if (payload.scheme != "http" && payload.scheme != "https") return emptyList()
        val host = (payload.normalizedHost() as? HostResult.Normalized)?.host ?: return emptyList()
        return when (val found = lookup.lookup(host)) {
            is ReputationResult.Listed ->
                listOf(Signal(SignalCode.REPUTATION_LISTED, evidence = Untrusted(found.source)))
            ReputationResult.NotListed -> emptyList()
            ReputationResult.ListsMissing -> listOf(Signal(SignalCode.LISTS_MISSING))
            is ReputationResult.ListsStale ->
                if (found.ageDays > LISTS_STALE_AFTER_DAYS) {
                    listOf(Signal(SignalCode.LISTS_STALE, count = found.ageDays))
                } else {
                    emptyList()
                }
        }
    }
}
