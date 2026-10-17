package com.lookfirst.domain.analysis

import com.lookfirst.domain.decoder.UrlParser
import com.lookfirst.domain.host.HostResult
import com.lookfirst.domain.host.NormalizedHost
import com.lookfirst.domain.host.PublicSuffixList
import com.lookfirst.domain.model.HostKind
import com.lookfirst.domain.model.ScanPayload
import com.lookfirst.domain.model.UrlIssue

/** Longest text taken for an address (the decoder already caps the whole content far above this). */
internal const val ADDRESS_LIKE_MAX_CHARS = 2048

private const val ASCII_LIMIT = 0x80
private const val UNICODE_DOTS = "\u3002\uFF0E\uFF61"

private val REJECTED_ISSUES =
    setOf(
        UrlIssue.CONTROL_OR_SPACE,
        UrlIssue.EMPTY_HOST,
        UrlIssue.HOST_TOO_LONG,
        UrlIssue.INVALID_HOST_CHARS,
        UrlIssue.HOST_PERCENT_ENCODED,
        UrlIssue.INVALID_PORT,
        UrlIssue.BACKSLASH,
        UrlIssue.EXTRA_SLASHES,
    )

/**
 * The host a person would reach by pasting this text in a browser, when the text is a single word shaped like
 * `host[:port][/path][?query][#fragment]` (LF-42, T-CNT-10). The decoder keeps the payload a [ScanPayload.Text]
 * (nothing is opened); this is only the address the analysis looks at, as a scheme-less [ScanPayload.Url] that goes
 * through the same host analyses and the same reputation key as a real one. Computed once per payload
 * ([ScanPayload.Text.addressLikeUrl]).
 *
 * Kept narrow on purpose, to leave ordinary text alone. Spaces and line breaks at both ends are ignored (a browser
 * drops them), none inside; no `@` (an e-mail address; `user@listed.com/x` is not looked at, residual), no
 * backslash, no `%` in the host. The labels of the NORMALIZED host (UTS #46) are letters, digits and hyphens, at
 * least two, none empty. A trailing dot or an ideographic dot (`。`) only counts when a port, path, query or fragment
 * follows (`listed.com./x`, `paypal。com/x`), unless the rest of the host is ASCII. The name must end in a suffix of
 * the embedded Public Suffix List (ICANN or private) with a label in front of it (`rapport.pdf`, `3.14`, `v1.2.3`,
 * `Mr.Smith` have no such suffix), or, when a port or path follows, in a reserved or private-use suffix of
 * [LocalHosts] (`nas.local:5000/`, `router.lan/admin`). An IPv4 must be dotted decimal and come with a port, a
 * path, a query or a fragment (a bare `1.2.3.4` is more often a version number); obfuscated forms (`0x7f.1`,
 * `2130706433`) are not looked at: dates and versions look like them. Names that are both a file extension and a TLD
 * (`.zip`, `.mov`, `.py`, `.md`...) are analysed: a `.zip` site is a real lure.
 */
internal fun ScanPayload.Text.computeAddressLikeUrl(): ScanPayload.Url? {
    val text = raw.value.trim { it <= ' ' }
    if (text.length > ADDRESS_LIKE_MAX_CHARS || text.any { it <= ' ' || it in "@\\" }) return null
    val url = UrlParser.parse(text, "", "//$text", missingScheme = true)
    val rejected = url.userinfo != null ||
        url.issues.any { it in REJECTED_ISSUES } ||
        url.hostKind == HostKind.IPV6
    val host = (url.normalizedHost() as? HostResult.Normalized)?.host
    if (rejected || host == null) return null
    return url.takeIf { hasHostShape(url, host) && hasKnownSuffix(url, host) }
}

/** More than the bare host was typed: a port, a path, a query or a fragment. */
internal fun ScanPayload.Url.hasMoreThanHost(): Boolean =
    port != null || path.value.isNotEmpty() || query != null || fragment != null

private fun hasHostShape(url: ScanPayload.Url, host: NormalizedHost): Boolean {
    val more = url.hasMoreThanHost()
    val labels = host.asciiLabels
    val labelsOk = labels.size >= 2 &&
        labels.all { label ->
            label.isNotEmpty() &&
                label.all { it == '-' || (it.code < ASCII_LIMIT && it.isLetterOrDigit()) }
        }
    val raw = url.rawHost.value
    val unicodeDots = raw.any { it in UNICODE_DOTS }
    val dotsOk = !unicodeDots || more || raw.all { it.code < ASCII_LIMIT || it in UNICODE_DOTS }
    val trailingDotOk = !host.hadTrailingDot || more
    return labelsOk && dotsOk && trailingDotOk
}

private fun hasKnownSuffix(url: ScanPayload.Url, host: NormalizedHost): Boolean = when (host.kind) {
    HostKind.IPV4 -> url.hostKind == HostKind.IPV4 && url.hasMoreThanHost()
    HostKind.DOMAIN -> hasDomainSuffix(url, host)
    else -> false
}

private fun hasDomainSuffix(url: ScanPayload.Url, host: NormalizedHost): Boolean {
    if (url.hasMoreThanHost() && LocalHosts.hasReservedSuffix(host.ascii)) return true
    val list = PublicSuffixList.instance ?: return false
    val labels = host.asciiLabels
    val count = list.suffixLabelCount(labels)
    val known = list.isIcannTld(labels.last()) || list.isPrivateSuffix(labels, count)
    return known && labels.size > count
}
