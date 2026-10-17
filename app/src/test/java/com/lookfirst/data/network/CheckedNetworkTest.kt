package com.lookfirst.data.network

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

class CheckedNetworkTest {
    private var now = 0L
    private val clock = MonotonicClock { now }
    private val inline = Executor { it.run() }
    private val threaded = Executor { Thread(it).apply { isDaemon = true }.start() }

    private fun address(vararg octets: Int): InetAddress =
        InetAddress.getByAddress(ByteArray(octets.size) { octets[it].toByte() })

    private val publicOne = address(93, 184, 216, 34)
    private val publicTwo = address(1, 1, 1, 1)
    private val publicThree = address(8, 8, 8, 8)
    private val publicFour = address(9, 9, 9, 9)
    private val privateOne = address(10, 0, 0, 1)

    private fun budget(millis: Long = 10_000) = TimeBudget(clock, millis)

    private class RecordingResolver(private val answer: List<InetAddress>) : HostResolver {
        val calls = mutableListOf<String>()

        override fun resolve(host: String): List<InetAddress> {
            calls += host
            return answer
        }
    }

    private class FakeTransport(private val behaviour: (HopTarget) -> ResponseHead) : HopTransport {
        val targets = mutableListOf<HopTarget>()

        override fun exchange(
            target: HopTarget,
            request: ByteArray,
            budget: TimeBudget,
            abort: AbortHandle,
        ): ResponseHead {
            targets += target
            return behaviour(target)
        }
    }

    private val ok = ResponseHead(200, emptyList())

    private fun connector(
        answer: List<InetAddress>,
        behaviour: (HopTarget) -> ResponseHead = { ok },
    ): Triple<CheckedConnector, RecordingResolver, FakeTransport> {
        val resolver = RecordingResolver(answer)
        val transport = FakeTransport(behaviour)
        return Triple(CheckedConnector(resolver, transport), resolver, transport)
    }

    // CheckedConnector

    @Test
    fun `all public addresses are returned and resolved once per host`() {
        val (connector, resolver, _) = connector(listOf(publicOne, publicOne, publicTwo))
        val resolved = mutableMapOf<String, List<InetAddress>>()

        val first = connector.checkedAddresses("a.example", budget(), resolved)
        val second = connector.checkedAddresses("a.example", budget(), resolved)

        assertEquals(listOf(publicOne, publicTwo), first)
        assertEquals(first, second)
        assertEquals(listOf("a.example"), resolver.calls)
    }

    @Test
    fun `one blocked address refuses the whole host`() {
        val (connector, _, _) = connector(listOf(publicOne, privateOne))

        assertNull(connector.checkedAddresses("a.example", budget(), mutableMapOf()))
    }

    @Test
    fun `an empty answer is a network failure`() {
        val (connector, _, _) = connector(emptyList())

        try {
            connector.checkedAddresses("a.example", budget(), mutableMapOf())
            fail("expected an IOException")
        } catch (expected: IOException) {
            assertTrue(expected !is SocketTimeoutException)
        }
    }

    @Test
    fun `a spent budget stops the resolution before it starts`() {
        val (connector, resolver, _) = connector(listOf(publicOne))
        val spent = budget(100)
        now = 100

        try {
            connector.checkedAddresses("a.example", spent, mutableMapOf())
            fail("expected a timeout")
        } catch (expected: SocketTimeoutException) {
            assertTrue(resolver.calls.isEmpty())
        }
    }

    @Test
    fun `the connection goes to the checked address on port 443 with the host for the certificate`() {
        val (connector, _, transport) = connector(listOf(publicOne))

        val head = connector.exchange(
            "a.example",
            listOf(publicOne),
            byteArrayOf(),
            budget(),
            AbortHandle(),
            maxBody = 7,
        )

        assertSame(ok, head)
        val target = transport.targets.single()
        assertEquals("a.example", target.host)
        assertEquals(publicOne, target.address)
        assertEquals(443, target.port)
        assertEquals(7, target.maxBody)
    }

    @Test
    fun `an unreachable address falls through to the next one`() {
        val (connector, _, transport) = connector(listOf(publicOne, publicTwo)) { target ->
            if (target.address == publicOne) throw IOException("refused") else ok
        }

        val head = connector.exchange("a.example", listOf(publicOne, publicTwo), byteArrayOf(), budget(), AbortHandle())

        assertSame(ok, head)
        assertEquals(listOf(publicOne, publicTwo), transport.targets.map { it.address })
    }

    @Test
    fun `a timeout ends the attempts at once`() {
        val (connector, _, transport) = connector(listOf(publicOne, publicTwo)) { throw SocketTimeoutException() }

        try {
            connector.exchange("a.example", listOf(publicOne, publicTwo), byteArrayOf(), budget(), AbortHandle())
            fail("expected a timeout")
        } catch (expected: SocketTimeoutException) {
            assertEquals(1, transport.targets.size)
        }
    }

    @Test
    fun `at most three addresses are tried and the last failure is reported`() {
        val (connector, _, transport) = connector(emptyList()) { throw IOException("down ${it.address.hostAddress}") }
        val all = listOf(publicOne, publicTwo, publicThree, publicFour)

        try {
            connector.exchange("a.example", all, byteArrayOf(), budget(), AbortHandle())
            fail("expected an IOException")
        } catch (expected: IOException) {
            assertEquals(3, transport.targets.size)
            assertEquals("down 8.8.8.8", expected.message)
        }
    }

    // BoundedWork

    private fun outcome(failure: WorkFailure?): String = failure?.name ?: "none"

    private fun run(work: BoundedWork, body: (TimeBudget, AbortHandle) -> String): String =
        runBlocking { work.run({ outcome(it) }, body) }

    @Test
    fun `the result of the work is returned`() {
        assertEquals("done", run(BoundedWork(inline, clock, 1_000)) { _, _ -> "done" })
    }

    @Test
    fun `an executor that refuses the work gives REJECTED`() {
        val refusing = Executor { throw RejectedExecutionException() }

        assertEquals("REJECTED", run(BoundedWork(refusing, clock, 1_000)) { _, _ -> "never" })
    }

    @Test
    fun `a socket timeout gives TIMEOUT`() {
        assertEquals("TIMEOUT", run(BoundedWork(inline, clock, 1_000)) { _, _ -> throw SocketTimeoutException() })
    }

    @Test
    fun `an IO error gives NETWORK`() {
        assertEquals("NETWORK", run(BoundedWork(inline, clock, 1_000)) { _, _ -> throw IOException("reset") })
    }

    @Test
    fun `a bug in the work gives NETWORK and never escapes`() {
        assertEquals("NETWORK", run(BoundedWork(inline, clock, 1_000)) { _, _ -> error("bug") })
    }

    @Test
    fun `the work receives a budget of the requested size`() {
        var remaining = 0L

        run(BoundedWork(inline, clock, 1_234)) { budget, _ ->
            budget.remainingMillis().also { remaining = it }.toString()
        }

        assertEquals(1_234L, remaining)
    }

    @Test
    fun `work that never returns is abandoned after the budget and its connection is closed`() {
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val started = System.nanoTime()

        val result = run(BoundedWork(threaded, clock, 100)) { _, abort ->
            abort.register(Closeable { closed.countDown() })
            release.await(30, TimeUnit.SECONDS)
            "late"
        }
        release.countDown()

        assertEquals("TIMEOUT", result)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000)
        assertTrue(closed.await(5, TimeUnit.SECONDS))
    }
}
