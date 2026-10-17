package com.lookfirst.data.network

import com.lookfirst.domain.model.Untrusted
import com.lookfirst.domain.open.CanonicalUrl
import com.lookfirst.domain.redirect.MAX_HOPS
import com.lookfirst.domain.redirect.RedirectTrace
import com.lookfirst.domain.redirect.TRACE_TIMEOUT_MILLIS
import com.lookfirst.domain.redirect.TraceEnd
import com.lookfirst.domain.redirect.TraceFailure
import com.lookfirst.domain.redirect.TraceOutcome
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.Executor

private const val HTTPS_PORT = 443
private const val HTTPS = "https"
private const val MAX_RAW_LOCATION_KEPT = 200

/**
 * [RedirectTracer] over a hand-written HTTP/1.1 exchange on a TLS socket (ADR-0004, decision D-redirect).
 *
 * Per address: the host is resolved once, EVERY returned address is checked ([AddressFilter]) and one blocked address
 * refuses the host; the connection then goes to a checked IP, so the address that was checked is the address that is
 * contacted (no rebinding window: a host is never resolved twice in one trace). Redirects are followed by hand: at most
 * [MAX_HOPS], https only, each `Location` goes through [LocationResolver]. The blocking work runs on the given executor under
 * a time limit ([BoundedWork]); the checks and the connection are those of [CheckedConnector].
 */
internal class SocketRedirectTracer(
    resolver: HostResolver,
    transport: HopTransport,
    executor: Executor,
    clock: MonotonicClock = MonotonicClock { System.nanoTime() / NANOS_PER_MILLI },
    timeoutMillis: Long = TRACE_TIMEOUT_MILLIS,
) : RedirectTracer {
    private val connector = CheckedConnector(resolver, transport)
    private val work = BoundedWork(executor, clock, timeoutMillis)

    override suspend fun trace(start: CanonicalUrl): TraceOutcome {
        if (start.scheme != HTTPS) return TraceOutcome.Failed(TraceFailure.NOT_HTTPS)
        return work.run(::failed) { budget, abort -> Walk(start, budget, abort).run() }
    }

    private fun failed(failure: WorkFailure): TraceOutcome = TraceOutcome.Failed(
        when (failure) {
            WorkFailure.TIMEOUT -> TraceFailure.TIMEOUT
            WorkFailure.REJECTED -> TraceFailure.BUSY
            WorkFailure.NETWORK -> TraceFailure.NETWORK
        },
    )

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
            if ((current.port ?: HTTPS_PORT) != HTTPS_PORT) return Step.Stop(TraceEnd.UnsupportedPort(current))
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
        private fun checkedAddresses(url: CanonicalUrl): List<InetAddress>? =
            connector.checkedAddresses(bareHost(url), budget, resolved)

        private fun request(url: CanonicalUrl, addresses: List<InetAddress>): ResponseHead {
            val head = exchange(url, addresses, rangeProbe = false)
            return if (head.rejectsHead) exchange(url, addresses, rangeProbe = true) else head
        }

        private fun exchange(url: CanonicalUrl, addresses: List<InetAddress>, rangeProbe: Boolean): ResponseHead {
            val bytes = requestBytes(url, url.host, rangeProbe)
            return connector.exchange(bareHost(url), addresses, bytes, budget, abort)
        }

        private fun bareHost(url: CanonicalUrl) = url.host.removePrefix("[").removeSuffix("]")
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
