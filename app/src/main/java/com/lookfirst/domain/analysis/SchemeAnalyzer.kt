package com.lookfirst.domain.analysis

import com.lookfirst.domain.model.ParseResult
import com.lookfirst.domain.model.ScanPayload
import com.lookfirst.domain.model.Signal
import com.lookfirst.domain.model.SignalCode
import com.lookfirst.domain.model.Untrusted

/** Schemes that are not a web page: commands (`javascript:`, `intent:`...) and app deep links. Copy only (D12). */
object SchemeAnalyzer : SignalAnalyzer {
    override fun analyze(result: ParseResult.Ok): List<Signal> = when (val payload = result.payload) {
        is ScanPayload.DangerousScheme ->
            listOf(Signal(SignalCode.DANGEROUS_SCHEME, evidence = Untrusted(payload.scheme)))
        is ScanPayload.AppLink -> listOf(Signal(SignalCode.UNKNOWN_SCHEME, evidence = Untrusted(payload.scheme)))
        else -> emptyList()
    }
}
