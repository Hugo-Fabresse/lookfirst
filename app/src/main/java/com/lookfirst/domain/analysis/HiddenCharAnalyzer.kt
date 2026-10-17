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
 * direction overrides in the authority of a URL (BIDI_IN_HOST). The authority is checked after percent-decoding,
 * since `%E2%80%AE` is invisible once decoded.
 */
object HiddenCharAnalyzer : SignalAnalyzer {
    override fun analyze(result: ParseResult.Ok): List<Signal> {
        val count = result.hiddenChars.size + result.fieldHiddenChars.sumOf { it.chars.size }
        val url = result.payload as? ScanPayload.Url
        return buildList {
            if (url != null && hasBidiInAuthority(url)) add(Signal(SignalCode.BIDI_IN_HOST, evidence = url.rawHost))
            if (count > 0) add(Signal(SignalCode.HIDDEN_CHARS, count = count))
        }
    }

    private fun hasBidiInAuthority(url: ScanPayload.Url): Boolean = listOfNotNull(url.userinfo, url.rawHost).any {
        ContentSanitizer.findHiddenChars(PercentCodec.decode(it.value)).any { c -> c.kind == HiddenCharKind.BIDI }
    }
}
