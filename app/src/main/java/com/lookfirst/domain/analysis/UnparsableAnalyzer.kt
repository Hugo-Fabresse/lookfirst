package com.lookfirst.domain.analysis

import com.lookfirst.domain.model.ParseResult
import com.lookfirst.domain.model.ScanPayload
import com.lookfirst.domain.model.Signal
import com.lookfirst.domain.model.SignalCode

/** Plain text and content that claims a format but is malformed: never NO_RISK_SIGNAL (ux.md 4.1). */
object UnparsableAnalyzer : SignalAnalyzer {
    override fun analyze(result: ParseResult.Ok): List<Signal> = when (result.payload) {
        is ScanPayload.Text, is ScanPayload.Unknown -> listOf(Signal(SignalCode.UNPARSABLE))
        else -> emptyList()
    }
}
