package com.lookfirst.domain.analysis

import com.lookfirst.domain.model.ParseResult
import com.lookfirst.domain.model.Signal

/**
 * Extra facts the pure rules may use, supplied later by the data layer. [regionHint] is an ISO 3166 country code
 * (for example the SIM or locale country) used for national phone numbers; null when unknown.
 */
data class AnalysisContext(val regionHint: String? = null)

/** Pure rule set: decoded content -> signals. Never throws, no network, no state, same input same output. */
fun interface SignalAnalyzer {
    fun analyze(result: ParseResult.Ok, context: AnalysisContext): List<Signal>
}
