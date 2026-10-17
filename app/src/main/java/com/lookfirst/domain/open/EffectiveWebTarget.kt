package com.lookfirst.domain.open

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
