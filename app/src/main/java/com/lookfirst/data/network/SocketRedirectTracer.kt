package com.lookfirst.data.network

import com.lookfirst.domain.model.Untrusted
import com.lookfirst.domain.open.CanonicalUrl
import com.lookfirst.domain.redirect.MAX_HOPS
import com.lookfirst.domain.redirect.RedirectTrace
import com.lookfirst.domain.redirect.TRACE_TIMEOUT_MILLIS
import com.lookfirst.domain.redirect.TraceEnd
import com.lookfirst.domain.redirect.TraceFailure
import com.lookfirst.domain.redirect.TraceOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

private const val HTTPS_PORT = 443
private const val MAX_CONNECT_ATTEMPTS = 3
private const val ABANDON_GRACE_MILLIS = 250L
private const val HTTPS = "https"
private const val MAX_RAW_LOCATION_KEPT = 200

/**
 * [RedirectTracer] over a hand-written HTTP/1.1 exchange on a TLS socket (ADR-0004, decision D-redirect).
 *
 * Per address: the host is resolved once, EVERY returned address is checked ([AddressFilter]) and one blocked address
 * refuses the host; the connection then goes to a checked IP, so the address that was checked is the address that is
 * contacted (no rebinding window: a host is never resolved twice in one trace). Redirects are followed by hand: at most
 * [MAX_HOPS], https only, each `Location` goes through [LocationResolver]. The blocking work runs on [executor]; the
 * caller waits with a time limit and, when it gives up (timeout or cancel), the open connection is closed.
 */
internal class SocketRedirectTracer(
    private val resolver: HostResolver,
    private val transport: HopTransport,
    private val executor: Executor,
    private val clock: MonotonicClock = MonotonicClock { System.nanoTime() / NANOS_PER_MILLI },
    private val timeoutMillis: Long = TRACE_TIMEOUT_MILLIS,
) : RedirectTracer {
    override suspend fun trace(start: CanonicalUrl): TraceOutcome {
        if (start.scheme != HTTPS) return TraceOutcome.Failed(TraceFailure.NOT_HTTPS)
        val budget = TimeBudget(clock, timeoutMillis)
        val abort = AbortHandle()
        val done = CompletableDeferred<TraceOutcome>()
        try {
            executor.execute { done.complete(runGuarded(start, budget, abort)) }
        } catch (ignored: RejectedExecutionException) {
            return TraceOutcome.Failed(TraceFailure.NETWORK)
        }
        try {
            // A blocked resolver cannot be interrupted: the caller is released after the budget and the worker is abandoned.
            return withTimeoutOrNull(timeoutMillis + ABANDON_GRACE_MILLIS) { done.await() }
                ?: TraceOutcome.Failed(TraceFailure.TIMEOUT)
        } finally {
            if (!done.isCompleted) abort.abort()
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun runGuarded(start: CanonicalUrl, budget: TimeBudget, abort: AbortHandle): TraceOutcome = try {
        Walk(start, budget, abort).run()
    } catch (ignored: SocketTimeoutException) {
        TraceOutcome.Failed(TraceFailure.TIMEOUT)
    } catch (ignored: IOException) {
        TraceOutcome.Failed(TraceFailure.NETWORK)
    } catch (ignored: RuntimeException) {
        // A bug or a platform quirk must end the check, never the app.
        TraceOutcome.Failed(TraceFailure.NETWORK)
    }

    /** One trace: its state (visited addresses, resolved hosts) lives and dies with it. */
    private inner class Walk(
        private val start: CanonicalUrl,
        private val budget: TimeBudget,
        private val abort: AbortHandle,
    ) {
        private val hops = mutableListOf<CanonicalUrl>()
        private val visited = hashSetOf(key(start))
        private val resolved = HashMap<String, List<InetAddress>>()

        fun run(): TraceOutcome {
            var current = start
            while (true) {
                when (val step = advance(current)) {
                    is Step.Continue -> current = step.url
                    is Step.Stop -> return finish(step.end)
                }
            }
        }

        /** Contacts [current] and decides where the chain goes next. */
        private fun advance(current: CanonicalUrl): Step {
            val addresses = checkedAddresses(current) ?: return Step.Stop(TraceEnd.BlockedAddress(current))
            val head = request(current, addresses)
            val location = head.locations.firstOrNull()
            return when {
                !head.isRedirect || location == null -> Step.Stop(TraceEnd.Final)
                head.locations.distinct().size > 1 -> Step.Stop(badLocation(location))
                hops.size >= MAX_HOPS -> Step.Stop(TraceEnd.TooManyHops)
                else -> follow(current, location)
            }
        }

        private fun follow(current: CanonicalUrl, location: String): Step =
            when (val next = LocationResolver.resolve(current, location)) {
                is NextHop.Unusable -> Step.Stop(badLocation(next.raw))
                is NextHop.Insecure -> Step.Stop(TraceEnd.InsecureHop(next.url))
                is NextHop.Secure -> if (visited.add(key(next.url))) {
                    hops += next.url
                    Step.Continue(next.url)
                } else {
                    Step.Stop(TraceEnd.Loop)
                }
            }

        private fun finish(end: TraceEnd) = TraceOutcome.Completed(RedirectTrace(start, hops.toList(), end))

        private fun badLocation(raw: String) = TraceEnd.BadLocation(Untrusted(raw.take(MAX_RAW_LOCATION_KEPT)))

        /** The addresses of the host, all checked; null when one of them is in a blocked range. */
        private fun checkedAddresses(url: CanonicalUrl): List<InetAddress>? {
            val host = url.host.removePrefix("[").removeSuffix("]")
            val addresses = resolved.getOrPut(host) {
                budget.requireRemaining()
                resolver.resolve(host).distinct()
            }
            if (addresses.isEmpty()) throw IOException("no address")
            return if (addresses.any { AddressFilter.isBlocked(it) }) null else addresses
        }

        private fun request(url: CanonicalUrl, addresses: List<InetAddress>): ResponseHead {
            val head = exchange(url, addresses, rangeProbe = false)
            return if (head.rejectsHead) exchange(url, addresses, rangeProbe = true) else head
        }

        /** Tries the checked addresses in order; an unreachable one falls through to the next, a timeout ends it all. */
        private fun exchange(url: CanonicalUrl, addresses: List<InetAddress>, rangeProbe: Boolean): ResponseHead {
            val port = url.port ?: HTTPS_PORT
            val hostHeader = if (url.port == null || url.port == HTTPS_PORT) url.host else "${url.host}:${url.port}"
            val bytes = requestBytes(url, hostHeader, rangeProbe)
            val host = url.host.removePrefix("[").removeSuffix("]")
            var failure: IOException? = null
            for (address in addresses.take(MAX_CONNECT_ATTEMPTS)) {
                budget.requireRemaining()
                try {
                    return transport.exchange(HopTarget(host, address, port), bytes, budget, abort)
                } catch (timeout: SocketTimeoutException) {
                    throw timeout
                } catch (io: IOException) {
                    failure = io
                }
            }
            throw failure ?: IOException("no address")
        }
    }

    private sealed interface Step {
        data class Continue(val url: CanonicalUrl) : Step

        data class Stop(val end: TraceEnd) : Step
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L

        /** The address without its fragment: two hops that differ only by `#` are the same request. */
        fun key(url: CanonicalUrl): String = url.value.substringBefore('#')

        fun requestBytes(url: CanonicalUrl, hostHeader: String, rangeProbe: Boolean): ByteArray = try {
            HttpHead.request(requestTarget(url), hostHeader, rangeProbe)
        } catch (invalid: IllegalArgumentException) {
            throw IOException("unusable request", invalid)
        }

        /** Path and query as sent on the request line; the canonical form is percent-encoded and starts with `/`. */
        fun requestTarget(url: CanonicalUrl): String {
            val rest = url.value.removePrefix(LocationResolver.origin(url)).substringBefore('#')
            return if (rest.startsWith("/")) rest else "/$rest"
        }
    }
}
