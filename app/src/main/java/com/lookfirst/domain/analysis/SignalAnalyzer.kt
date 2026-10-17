package com.lookfirst.domain.analysis

import com.lookfirst.domain.model.ParseResult
import com.lookfirst.domain.model.Signal

/** Pure rule set: decoded content -> signals. Never throws, no network, no state, same input same output. */
fun interface SignalAnalyzer {
    fun analyze(result: ParseResult.Ok): List<Signal>
}
