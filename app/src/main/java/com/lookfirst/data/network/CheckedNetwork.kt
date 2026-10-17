package com.lookfirst.data.network

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

/** Why a bounded piece of blocking work gave nothing. The detail never leaves this layer. */
internal enum class WorkFailure { REJECTED, TIMEOUT, NETWORK }

/**
 * Blocking network work on [executor] with one budget for all of it. The caller waits with a time limit and, when it
 * gives up (timeout or cancel), the open connection is closed; a resolver call that cannot be interrupted strands its
 * own thread only. Shared by every optional network action (redirect tracing, RDAP).
 */
internal class BoundedWork(
    private val executor: Executor,
    private val clock: MonotonicClock,
    private val timeoutMillis: Long,
) {
    suspend fun <T> run(onFailure: (WorkFailure) -> T, work: (TimeBudget, AbortHandle) -> T): T {
        val budget = TimeBudget(clock, timeoutMillis)
        val abort = AbortHandle()
        val done = CompletableDeferred<T>()
        try {
            executor.execute { done.complete(guarded(onFailure, work, budget, abort)) }
        } catch (ignored: RejectedExecutionException) {
            return onFailure(WorkFailure.REJECTED)
        }
        try {
            // A blocked resolver cannot be interrupted: the caller is released after the budget and the worker is abandoned.
            return withTimeoutOrNull(timeoutMillis + ABANDON_GRACE_MILLIS) { done.await() }
                ?: onFailure(WorkFailure.TIMEOUT)
        } finally {
            if (!done.isCompleted) abort.abort()
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun <T> guarded(
        onFailure: (WorkFailure) -> T,
        work: (TimeBudget, AbortHandle) -> T,
        budget: TimeBudget,
        abort: AbortHandle,
    ): T = try {
        work(budget, abort)
    } catch (ignored: SocketTimeoutException) {
        onFailure(WorkFailure.TIMEOUT)
    } catch (ignored: IOException) {
        onFailure(WorkFailure.NETWORK)
    } catch (ignored: RuntimeException) {
        // A bug or a platform quirk must end the check, never the app.
        onFailure(WorkFailure.NETWORK)
    }
}

/**
 * The rules every optional request obeys (threat model T-NET-2): a host is resolved once, EVERY returned address is
 * checked ([AddressFilter]) and one blocked address refuses the host; the connection then goes to a checked IP, so the
 * address that was checked is the address that is contacted. Port 443 only, TLS through [transport].
 */
internal class CheckedConnector(private val resolver: HostResolver, private val transport: HopTransport) {
    /** The addresses of [host] (no brackets), all checked; null when one of them is in a blocked range. */
    @Throws(IOException::class)
    fun checkedAddresses(
        host: String,
        budget: TimeBudget,
        resolved: MutableMap<String, List<InetAddress>>,
    ): List<InetAddress>? {
        val addresses = resolved.getOrPut(host) {
            budget.requireRemaining()
            resolver.resolve(host).distinct()
        }
        if (addresses.isEmpty()) throw IOException("no address")
        return if (addresses.any { AddressFilter.isBlocked(it) }) null else addresses
    }

    /**
     * Tries the checked [addresses] in order; an unreachable one falls through to the next, a timeout ends it all.
     * [maxBody] is the body the caller wants read (0: head only).
     */
    @Throws(IOException::class)
    fun exchange(
        host: String,
        addresses: List<InetAddress>,
        request: ByteArray,
        budget: TimeBudget,
        abort: AbortHandle,
        maxBody: Int = 0,
    ): ResponseHead {
        var failure: IOException? = null
        for (address in addresses.take(MAX_CONNECT_ATTEMPTS)) {
            budget.requireRemaining()
            try {
                return transport.exchange(HopTarget(host, address, HTTPS_PORT, maxBody), request, budget, abort)
            } catch (timeout: SocketTimeoutException) {
                throw timeout
            } catch (io: IOException) {
                failure = io
            }
        }
        throw failure ?: IOException("no address")
    }
}
