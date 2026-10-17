package com.lookfirst.domain.analysis

import com.lookfirst.domain.model.HostKind
import com.lookfirst.domain.model.ParseResult
import com.lookfirst.domain.model.ScanPayload
import com.lookfirst.domain.model.Signal
import com.lookfirst.domain.model.SignalCode
import com.lookfirst.domain.model.Untrusted
import com.lookfirst.domain.model.UrlIssue
import java.util.Locale

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
 * length, shorteners, decoder oddities. Host names are not normalized here (IDN, homographs: LF-16), so a host
 * whose kind is [HostKind.UNRESOLVED] only gets the checks that do not depend on the host.
 */
object UrlStructureAnalyzer : SignalAnalyzer {
    override fun analyze(result: ParseResult.Ok): List<Signal> {
        val url = result.payload as? ScanPayload.Url ?: return emptyList()
        return buildList {
            if (url.hasUserinfo) add(Signal(SignalCode.USERINFO_AT, evidence = url.rawHost))
            if (url.hostKind in IP_KINDS) add(Signal(SignalCode.IP_HOST, evidence = url.rawHost))
            if (url.port != null && url.port !in STANDARD_PORTS) {
                add(Signal(SignalCode.UNUSUAL_PORT, evidence = Untrusted("${url.port}")))
            }
            if (url.scheme == "http") add(Signal(SignalCode.PLAIN_HTTP))
            if (url.raw.value.trim().length > LONG_URL_THRESHOLD) add(Signal(SignalCode.LONG_URL))
            if (isShortener(url)) add(Signal(SignalCode.SHORTENER, evidence = url.rawHost))
            addAll(issueSignals(url))
        }
    }

    private fun isShortener(url: ScanPayload.Url): Boolean = url.hostKind == HostKind.DOMAIN &&
        ShortenerDomains.matches(url.rawHost.value.lowercase(Locale.ROOT).trimEnd('.'))

    private fun issueSignals(url: ScanPayload.Url): List<Signal> = buildList {
        if (url.issues.any { it in ODD_FORMAT_ISSUES }) add(Signal(SignalCode.URL_ODD_FORMAT))
        if (UrlIssue.HOST_PERCENT_ENCODED in url.issues) {
            add(Signal(SignalCode.URL_HOST_ENCODED, evidence = url.rawHost))
        }
        if (url.issues.any { it in MALFORMED_ISSUES }) add(Signal(SignalCode.URL_MALFORMED))
    }
}
