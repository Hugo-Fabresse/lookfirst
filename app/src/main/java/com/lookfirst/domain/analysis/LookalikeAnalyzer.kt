package com.lookfirst.domain.analysis

import com.lookfirst.domain.host.Confusables
import com.lookfirst.domain.host.HostResult
import com.lookfirst.domain.host.NormalizedHost
import com.lookfirst.domain.host.PublicSuffixList
import com.lookfirst.domain.host.ScriptData
import com.lookfirst.domain.model.HostKind
import com.lookfirst.domain.model.MixedScripts
import com.lookfirst.domain.model.ParseResult
import com.lookfirst.domain.model.ScanPayload
import com.lookfirst.domain.model.Signal
import com.lookfirst.domain.model.SignalCode
import com.lookfirst.domain.model.Untrusted

private const val ASCII_LIMIT = 0x80

/** Unicode scripts a single label may combine without being reported (UTS #39 "highly restrictive", CJK part). */
private val ALLOWED_SCRIPT_SETS: List<Set<String>> =
    listOf(
        setOf("Latin", "Han", "Hiragana", "Katakana"),
        setOf("Latin", "Han", "Bopomofo"),
        setOf("Latin", "Han", "Hangul"),
    )

/**
 * Host names that imitate someone else: labels that mix alphabets (MIXED_SCRIPT) and names that look like a known
 * brand (BRAND_LOOKALIKE: look-alike characters, small typos, brand inside a longer name or sub-domain). Works on the
 * normalized host, so `bit.ly。`, fullwidth and `%`-encoded forms are seen as what a browser would use.
 */
object LookalikeAnalyzer : SignalAnalyzer {
    override fun analyze(result: ParseResult.Ok, context: AnalysisContext): List<Signal> {
        val url = result.payload as? ScanPayload.Url ?: return emptyList()
        val host = (url.normalizedHost() as? HostResult.Normalized)?.host
        if (host == null || host.kind != HostKind.DOMAIN) return emptyList()
        val suffixes = PublicSuffixList.instance
        if (suffixes == null || !Confusables.isAvailable || !ScriptData.isAvailable) {
            return listOf(Signal(SignalCode.ANALYSIS_INCOMPLETE, evidence = url.rawHost))
        }
        return listOfNotNull(mixedScriptSignal(host), BrandMatcher(host, suffixes).signal())
    }

    private fun mixedScriptSignal(host: NormalizedHost): Signal? = host.unicodeLabels.firstNotNullOfOrNull { label ->
        val scripts = if (label.all { it.code < ASCII_LIMIT }) null else ScriptData.scriptsOf(label)
        if (scripts != null && scripts.size >= 2 && ALLOWED_SCRIPT_SETS.none { it.containsAll(scripts) }) {
            Signal(
                SignalCode.MIXED_SCRIPT,
                evidence = Untrusted(label),
                count = scripts.size,
                details = listOf(MixedScripts(scripts)),
            )
        } else {
            null
        }
    }
}
