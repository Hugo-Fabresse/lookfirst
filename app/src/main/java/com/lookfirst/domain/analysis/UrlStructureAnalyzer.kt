package com.lookfirst.domain.analysis

import com.lookfirst.domain.decoder.HostNumbers
import com.lookfirst.domain.host.HostError
import com.lookfirst.domain.host.HostResult
import com.lookfirst.domain.model.HostKind
import com.lookfirst.domain.model.ParseResult
import com.lookfirst.domain.model.ScanPayload
import com.lookfirst.domain.model.Severity
import com.lookfirst.domain.model.Signal
import com.lookfirst.domain.model.SignalCode
import com.lookfirst.domain.model.Untrusted
import com.lookfirst.domain.model.UrlIssue

/** Above this many characters the site name is buried in the rest of the address (DECISIONS: LONG_URL). */
internal const val LONG_URL_THRESHOLD = 250

private val STANDARD_PORTS = setOf(80, 443)

private val ODD_FORMAT_ISSUES =
    setOf(UrlIssue.MISSING_SCHEME, UrlIssue.MISSING_SLASHES, UrlIssue.EXTRA_SLASHES, UrlIssue.BACKSLASH)

private val MALFORMED_ISSUES =
    setOf(
        UrlIssue.CONTROL_OR_SPACE,
        UrlIssue.EMPTY_HOST,
        UrlIssue.HOST_TOO_LONG,
        UrlIssue.INVALID_HOST_CHARS,
        UrlIssue.INVALID_PORT,
    )

private val IP_KINDS = setOf(HostKind.IPV4, HostKind.IPV6, HostKind.IPV4_OBFUSCATED)

/**
 * Structure of an http(s) URL as the decoder split it: `user@host` tricks, numeric hosts, ports, plain http,
 * length, shorteners, decoder oddities. The host is the one [com.lookfirst.domain.host.HostNormalizer] produces
 * (UTS #46), so `ｂｉｔ.ｌｙ`, `bit.ly。` and `%`-encoded names are judged as a browser would see them; when the
 * normalizer fails the host is reported as malformed and the analysis as incomplete.
 */
object UrlStructureAnalyzer : SignalAnalyzer {
    override fun analyze(result: ParseResult.Ok, context: AnalysisContext): List<Signal> {
        val url = result.payload as? ScanPayload.Url ?: return emptyList()
        val normalized = url.normalizedHost()
        return buildList {
            val shownHost = (normalized as? HostResult.Normalized)?.let { Untrusted(it.host.ascii) } ?: url.rawHost
            userinfoSignal(url, shownHost)?.let { add(it) }
            addAll(hostSignals(url, normalized))
            if (url.port != null && url.port !in STANDARD_PORTS) {
                add(Signal(SignalCode.UNUSUAL_PORT, evidence = Untrusted("${url.port}")))
            }
            if (url.scheme == "http") add(Signal(SignalCode.PLAIN_HTTP))
            if (url.raw.value.trim().length > LONG_URL_THRESHOLD) add(Signal(SignalCode.LONG_URL))
            addAll(issueSignals(url))
        }.distinctBy { it.code }
    }

    /** IP_HOST, SHORTENER and the failure signals, all from the normalized host. */
    private fun hostSignals(url: ScanPayload.Url, normalized: HostResult?): List<Signal> = when (normalized) {
        null -> listOf(Signal(SignalCode.IP_HOST, evidence = url.rawHost)) // IPv6 literal
        is HostResult.Invalid -> buildList {
            add(Signal(SignalCode.ANALYSIS_INCOMPLETE, evidence = url.rawHost))
            if (normalized.error != HostError.DATA_UNAVAILABLE) add(Signal(SignalCode.URL_MALFORMED))
        }
        is HostResult.Normalized -> buildList {
            val ascii = Untrusted(normalized.host.ascii)
            if (normalized.host.kind in IP_KINDS) add(Signal(SignalCode.IP_HOST, evidence = ascii))
            if (normalized.host.kind == HostKind.DOMAIN && ShortenerDomains.matches(normalized.host.ascii)) {
                add(Signal(SignalCode.SHORTENER, evidence = ascii))
            }
        }
    }

    /** A dot or a numeric name before the `@` dresses the userinfo up as a site: DANGEROUS, else SUSPICIOUS. */
    private fun userinfoSignal(url: ScanPayload.Url, shownHost: Untrusted): Signal? {
        val userinfo = url.userinfo?.value ?: return null
        val name = userinfo.substringBefore(':')
        val looksLikeHost = '.' in userinfo || (name.isNotEmpty() && HostNumbers.classify(name) != HostKind.DOMAIN)
        val severity = if (looksLikeHost) Severity.DANGEROUS else Severity.SUSPICIOUS
        return Signal(SignalCode.USERINFO_AT, severity, evidence = shownHost)
    }

    private fun issueSignals(url: ScanPayload.Url): List<Signal> = buildList {
        if (url.issues.any { it in ODD_FORMAT_ISSUES }) add(Signal(SignalCode.URL_ODD_FORMAT))
        if (UrlIssue.HOST_PERCENT_ENCODED in url.issues) {
            add(Signal(SignalCode.URL_HOST_ENCODED, evidence = url.rawHost))
        }
        if (url.issues.any { it in MALFORMED_ISSUES }) add(Signal(SignalCode.URL_MALFORMED))
    }
}
