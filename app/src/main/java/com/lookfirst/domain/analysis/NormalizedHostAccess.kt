package com.lookfirst.domain.analysis

import com.lookfirst.domain.host.HostResult
import com.lookfirst.domain.model.ScanPayload

/** The host of this URL after UTS #46 normalization (computed once per payload); null for an IPv6 literal. */
internal fun ScanPayload.Url.normalizedHost(): HostResult? = normalizedHostResult
