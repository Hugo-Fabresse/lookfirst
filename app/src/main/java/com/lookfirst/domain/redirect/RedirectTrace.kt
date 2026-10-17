package com.lookfirst.domain.redirect

import com.lookfirst.domain.model.Untrusted
import com.lookfirst.domain.open.CanonicalUrl

/**
 * Where a redirect chain went. [hops] are the addresses reached after the scanned one, in order (empty when the
 * scanned address answered without redirecting); the last of them is the destination when [end] is [TraceEnd.Final].
 * Every address is in canonical form: the form that was requested and the form that is analysed and shown.
 */
data class RedirectTrace(val start: CanonicalUrl, val hops: List<CanonicalUrl>, val end: TraceEnd) {
    /** The address that answered last: the scanned one when nothing redirected. */
    val last: CanonicalUrl get() = hops.lastOrNull() ?: start
}

/** Why the chain stopped. Nothing here is followed any further. */
sealed interface TraceEnd {
    /** The last address answered without redirecting (or redirected without any `Location`). */
    data object Final : TraceEnd

    /** The next hop is `http://`: never contacted (the app has no cleartext), reported as a risk (T-NET-4). */
    data class InsecureHop(val target: CanonicalUrl) : TraceEnd

    /** The `Location` could not be turned into an https address: malformed, too long, or another scheme. */
    data class BadLocation(val raw: Untrusted) : TraceEnd

    /** [target] resolves to a private, local or reserved address: nothing was sent to it (T-NET-2). */
    data class BlockedAddress(val target: CanonicalUrl) : TraceEnd

    /** The chain came back to an address it had already visited. */
    data object Loop : TraceEnd

    /** More redirects than [MAX_HOPS]. */
    data object TooManyHops : TraceEnd
}

/** Why no trace could be made. Shown as one generic sentence; the detail never reaches the user or the logs. */
enum class TraceFailure { NOT_HTTPS, TIMEOUT, NETWORK }

sealed interface TraceOutcome {
    data class Completed(val trace: RedirectTrace) : TraceOutcome

    data class Failed(val reason: TraceFailure) : TraceOutcome
}

/** Redirects followed at most (ADR-0004). */
const val MAX_HOPS = 5

/** Whole budget for a trace: DNS, connections and reads (ADR-0004). */
const val TRACE_TIMEOUT_MILLIS = 5_000L
