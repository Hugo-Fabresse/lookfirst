package com.lookfirst.domain.open

import com.lookfirst.domain.host.HostResult
import com.lookfirst.domain.host.Ipv4Check
import com.lookfirst.domain.host.NormalizedHost
import com.lookfirst.domain.model.HostKind
import com.lookfirst.domain.model.ScanPayload

internal const val HTTP_SCHEME = "http"
internal const val HTTPS_SCHEME = "https"

/**
 * The scheme a browser will be given for this URL, or null when the Open action would not lead to a browser.
 * A URL typed without scheme (`host:8443/x`) is opened as https. Single source for what is analysed and what is
 * opened: [CanonicalUrl], the reputation lookup and the Open action all derive from it (LF-30, T-CNT-7).
 */
internal fun ScanPayload.Url.effectiveWebScheme(): String? =
    scheme.ifEmpty { HTTPS_SCHEME }.takeIf { it == HTTP_SCHEME || it == HTTPS_SCHEME }

/**
 * The host the browser is given, as a [NormalizedHost]: punycode for a name, dotted decimal for an IPv4 written in
 * any form (`0x7f.1`, `2130706433`). Null for an IPv6 literal (the lists do not index them) or an unusable host.
 * [CanonicalUrl] and the reputation lookup both use it, so the host analysed is the host opened.
 */
internal fun ScanPayload.Url.openedHost(): NormalizedHost? {
    val host = (normalizedHostResult as? HostResult.Normalized)?.host ?: return null
    return when (host.kind) {
        HostKind.IPV4, HostKind.IPV4_OBFUSCATED -> Ipv4Check.toDotted(host.ascii)?.let { host.copy(ascii = it) }
        else -> host
    }
}
