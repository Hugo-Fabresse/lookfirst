package com.lookfirst.domain.analysis

import com.lookfirst.domain.host.HostNormalizer
import com.lookfirst.domain.host.HostResult
import com.lookfirst.domain.model.HostKind
import com.lookfirst.domain.model.ScanPayload

/** The host of this URL after UTS #46 normalization; null for an IPv6 literal (nothing to normalize). */
internal fun ScanPayload.Url.normalizedHost(): HostResult? =
    if (hostKind == HostKind.IPV6) null else HostNormalizer.normalize(rawHost.value)
