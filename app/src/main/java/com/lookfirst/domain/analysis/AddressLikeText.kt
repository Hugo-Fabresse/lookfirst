package com.lookfirst.domain.analysis

import com.lookfirst.domain.decoder.UrlParser
import com.lookfirst.domain.host.HostResult
import com.lookfirst.domain.host.PublicSuffixList
import com.lookfirst.domain.model.HostKind
import com.lookfirst.domain.model.ScanPayload
import com.lookfirst.domain.model.UrlIssue

/** Longest text taken for an address (the decoder already caps the whole content far above this). */
internal const val ADDRESS_LIKE_MAX_CHARS = 2048

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
 * through the same host analyses and the same reputation key as a real one.
 *
 * Kept narrow on purpose, to leave ordinary text alone: no space, no `@` (an e-mail address), no `%`, no
 * backslash; the host is made of letters, digits and hyphens in at least two labels; the name must end in a suffix
 * of the embedded Public Suffix List (ICANN or private) with a label in front of it (`rapport.pdf`, `3.14`,
 * `v1.2.3`, `Mr.Smith` have no such suffix); an IPv4 must be dotted decimal and come with a port, a path, a query or
 * a fragment (a bare `1.2.3.4` is more often a version number). Names that are both a file extension and a TLD
 * (`.zip`, `.mov`, `.py`, `.md`...) are analysed: a `.zip` site is a real lure, and the only effect on an innocent
 * file name is the status of the lists, on a text that is already CAUTION.
 */
internal fun ScanPayload.Text.addressLikeUrl(): ScanPayload.Url? {
    val text = raw.value
    if (text.length > ADDRESS_LIKE_MAX_CHARS || text.any { it <= ' ' || it in "@\\" }) return null
    val url = UrlParser.parse(text, "", "//$text", missingScheme = true)
    val rejected = url.userinfo != null ||
        url.issues.any { it in REJECTED_ISSUES } ||
        url.hostKind == HostKind.IPV6 ||
        !hasHostCharsOnly(url.rawHost.value)
    return url.takeUnless { rejected || !hasKnownSuffix(it) }
}

/** Letters, digits and hyphens in dot-separated labels, no empty label (so no trailing dot either). */
private fun hasHostCharsOnly(host: String): Boolean {
    val labels = host.split('.')
    return labels.size >= 2 &&
        labels.all { label ->
            label.isNotEmpty() && label.all { it == '-' || Character.isLetterOrDigit(it) }
        }
}

private fun hasKnownSuffix(url: ScanPayload.Url): Boolean {
    val host = (url.normalizedHost() as? HostResult.Normalized)?.host ?: return false
    return when (host.kind) {
        HostKind.IPV4 -> url.hostKind == HostKind.IPV4 && hasMoreThanHost(url)
        HostKind.DOMAIN -> {
            val list = PublicSuffixList.instance ?: return false
            val labels = host.asciiLabels
            val count = list.suffixLabelCount(labels)
            val known = list.isIcannTld(labels.last()) || list.isPrivateSuffix(labels, count)
            known && labels.size > count
        }
        else -> false
    }
}

private fun hasMoreThanHost(url: ScanPayload.Url): Boolean =
    url.port != null || url.path.value.isNotEmpty() || url.query != null || url.fragment != null
