package com.lookfirst.domain.usecase

import com.lookfirst.domain.analysis.AnalysisContext
import com.lookfirst.domain.analysis.ReputationAnalyzer
import com.lookfirst.domain.analysis.ReputationLookup
import com.lookfirst.domain.analysis.RiskAnalyzer
import com.lookfirst.domain.decoder.PayloadParser
import com.lookfirst.domain.model.ParseResult
import com.lookfirst.domain.verdict.Verdict
import com.lookfirst.domain.verdict.VerdictEngine
import javax.inject.Inject

/**
 * Raw scan -> decode -> analyze (rules + local reputation) -> [Verdict]. Synchronous and CPU-bound: the caller runs
 * it off the main thread (the first call loads the host tables, about 150 ms). Nothing is opened or sent anywhere.
 */
class AnalyzeScan @Inject constructor(lookup: ReputationLookup) {
    private val reputation = ReputationAnalyzer(lookup)

    operator fun invoke(raw: String, payloadId: String, context: AnalysisContext = AnalysisContext()): Verdict {
        val parsed = PayloadParser.parse(raw)
        val signals = RiskAnalyzer.analyze(parsed, context) +
            if (parsed is ParseResult.Ok) reputation.analyze(parsed, context) else emptyList()
        return VerdictEngine.combine(signals, parsed, payloadId)
    }
}
