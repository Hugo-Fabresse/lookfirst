package com.lookfirst.domain.analysis

import com.lookfirst.domain.model.ParseResult
import com.lookfirst.domain.model.Signal
import com.lookfirst.domain.model.SignalCode

/**
 * Runs every rule set in a fixed order and concatenates the signals. No level here (LF-17), no reputation
 * lookup. Same input, same list.
 */
object RiskAnalyzer {
    private val ANALYZERS: List<SignalAnalyzer> =
        listOf(
            SchemeAnalyzer,
            HiddenCharAnalyzer,
            UrlStructureAnalyzer,
            LookalikeAnalyzer,
            WifiAnalyzer,
            OtpauthAnalyzer,
            PaymentAnalyzer,
            MessagingAnalyzer,
            UnparsableAnalyzer,
        )

    fun analyze(result: ParseResult, context: AnalysisContext = AnalysisContext()): List<Signal> = when (result) {
        is ParseResult.Ok -> ANALYZERS.flatMap { it.analyze(result, context) }
        // Nothing was decoded: nothing can be said, and that is never a clean result.
        is ParseResult.TooLarge, ParseResult.Empty -> listOf(Signal(SignalCode.UNPARSABLE))
    }
}
