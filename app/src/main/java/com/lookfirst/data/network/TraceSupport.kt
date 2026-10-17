package com.lookfirst.data.network

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.SocketTimeoutException

/** A monotonic clock in milliseconds, a seam for tests. */
fun interface MonotonicClock {
    fun nowMillis(): Long
}

/** The time left for the whole trace (DNS, connections, reads). Fixed when the trace starts. */
internal class TimeBudget(private val clock: MonotonicClock, totalMillis: Long) {
    private val deadline = clock.nowMillis() + totalMillis

    fun remainingMillis(): Long = deadline - clock.nowMillis()

    /** Milliseconds left for one blocking call; throws [SocketTimeoutException] once the budget is spent. */
    fun requireRemaining(): Int {
        val left = remainingMillis()
        if (left <= 0) throw SocketTimeoutException()
        return minOf(left, Int.MAX_VALUE.toLong()).toInt()
    }
}

/** Closes whatever connection is open when the caller gives up (cancel or timeout); nothing opens afterwards. */
internal class AbortHandle {
    private val lock = Any()
    private var aborted = false
    private val open = mutableListOf<Closeable>()

    fun register(closeable: Closeable) {
        val closeNow = synchronized(lock) {
            if (!aborted) open += closeable
            aborted
        }
        if (closeNow) {
            closeQuietly(closeable)
            throw IOException("aborted")
        }
    }

    fun abort() {
        val toClose = synchronized(lock) {
            aborted = true
            open.toList().also { open.clear() }
        }
        toClose.forEach { closeQuietly(it) }
    }

    private fun closeQuietly(closeable: Closeable) {
        try {
            closeable.close()
        } catch (ignored: IOException) {
            // Already closed or broken: nothing left to release.
        }
    }
}

/** Resolves a host name to all its addresses. A seam for tests; the real one is the system resolver. */
fun interface HostResolver {
    @Throws(IOException::class)
    fun resolve(host: String): List<InetAddress>
}

/**
 * What the transport needs to reach a host: [address] has been checked, [host] is for SNI and the certificate.
 * [maxBody] is the largest response body to read after the head (0: the body is never read).
 */
internal class HopTarget(val host: String, val address: InetAddress, val port: Int, val maxBody: Int = 0)

/** Sends one request to a checked address and reads the response head only. Throws [IOException] on any failure. */
internal fun interface HopTransport {
    @Throws(IOException::class)
    fun exchange(target: HopTarget, request: ByteArray, budget: TimeBudget, abort: AbortHandle): ResponseHead
}
