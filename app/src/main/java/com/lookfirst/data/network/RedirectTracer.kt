package com.lookfirst.data.network

import com.lookfirst.domain.open.CanonicalUrl
import com.lookfirst.domain.redirect.TraceOutcome

/**
 * Follows the redirects of an https address, on explicit user action only (ADR-0004, threat model T-NET-1/2/4).
 * Never throws: a failure is a [TraceOutcome.Failed]. Cancelling the caller aborts the connection under way.
 */
fun interface RedirectTracer {
    suspend fun trace(start: CanonicalUrl): TraceOutcome
}
