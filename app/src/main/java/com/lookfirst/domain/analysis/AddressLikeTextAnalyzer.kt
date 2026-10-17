package com.lookfirst.domain.analysis

import com.lookfirst.domain.model.ParseResult
import com.lookfirst.domain.model.ScanPayload
import com.lookfirst.domain.model.Signal

/**
 * Host analyses for a [ScanPayload.Text] that is an address in disguise (`login-paypa1.com/x`, `listé.example/x`,
 * `1.2.3.4:80/x`): numeric or local host, shortener, mixed alphabets, brand look-alikes. The URL-only signals (port,
 * plain http, userinfo, layout) are not repeated: the text is not a link and is never opened. The list lookup is in
 * [ReputationAnalyzer].
 */
object AddressLikeTextAnalyzer : SignalAnalyzer {
    override fun analyze(result: ParseResult.Ok, context: AnalysisContext): List<Signal> {
        val url = (result.payload as? ScanPayload.Text)?.addressLikeUrl ?: return emptyList()
        val asUrl = result.copy(payload = url)
        return (UrlStructureAnalyzer.hostSignals(url, url.normalizedHost()) + LookalikeAnalyzer.analyze(asUrl, context))
            .distinctBy { it.code }
    }
}
