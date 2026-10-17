package com.lookfirst.domain.analysis

import com.lookfirst.domain.decoder.ContentSanitizer
import com.lookfirst.domain.decoder.PercentCodec
import com.lookfirst.domain.model.HiddenCharKind
import com.lookfirst.domain.model.ParseResult
import com.lookfirst.domain.model.ScanPayload
import com.lookfirst.domain.model.Signal
import com.lookfirst.domain.model.SignalCode

/**
 * Invisible characters anywhere in the content or in a decoded field (HIDDEN_CHARS, count for the plural), and
 * direction controls anywhere in a URL (BIDI_IN_HOST, BIDI_IN_URL). URLs are checked after percent-decoding,
 * since `%E2%80%AE` is invisible once decoded.
 */
object HiddenCharAnalyzer : SignalAnalyzer {
    override fun analyze(result: ParseResult.Ok, context: AnalysisContext): List<Signal> {
        val count = result.hiddenChars.size + result.fieldHiddenChars.sumOf { it.chars.size }
        val url = result.payload as? ScanPayload.Url
        return buildList {
            if (url != null) bidiSignal(url)?.let { add(it) }
            if (count > 0) add(Signal(SignalCode.HIDDEN_CHARS, count = count))
        }
    }

    /** Authority first (BIDI_IN_HOST), else anywhere else in the URL (BIDI_IN_URL): both DANGEROUS (T-CNT-3). */
    private fun bidiSignal(url: ScanPayload.Url): Signal? = when {
        listOfNotNull(url.userinfo, url.rawHost).any { hasBidi(it.value) } ->
            Signal(SignalCode.BIDI_IN_HOST, evidence = url.rawHost)
        hasBidi(url.raw.value) -> Signal(SignalCode.BIDI_IN_URL)
        else -> null
    }

    private fun hasBidi(text: String): Boolean =
        ContentSanitizer.findHiddenChars(PercentCodec.decode(text)).any { it.kind == HiddenCharKind.BIDI }
}
