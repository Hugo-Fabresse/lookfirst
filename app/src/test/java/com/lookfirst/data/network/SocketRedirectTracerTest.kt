package com.lookfirst.data.network

import com.lookfirst.domain.decoder.decode
import com.lookfirst.domain.model.ScanPayload
import com.lookfirst.domain.open.CanonicalUrl
import com.lookfirst.domain.redirect.RedirectTrace
import com.lookfirst.domain.redirect.TraceEnd
import com.lookfirst.domain.redirect.TraceFailure
import com.lookfirst.domain.redirect.TraceOutcome
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class SocketRedirectTracerTest {
    private class Sent(val target: HopTarget, val text: String) {
        val method: String get() = text.substringBefore(' ')
        val path: String get() = text.substringAfter(' ').substringBefore(' ')
        val header: String get() = text.lineSequence().first { it.startsWith("Host:") }.removePrefix("Host: ")
    }

    private val public1 = InetAddress.getByAddress(
        "a.example",
        byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34),
    )
    private val public2 = InetAddress.getByAddress("b.example", byteArrayOf(8, 8, 8, 8))

    private var now = 0L
    private val resolutions = mutableListOf<String>()
    private val answers = mutableMapOf<String, ArrayDeque<List<InetAddress>>>()
    private val sent = mutableListOf<Sent>()
    private var script: (Sent) -> ResponseHead = { ResponseHead(200, emptyList()) }

    private val resolver = HostResolver { host ->
        resolutions += host
        val queue = answers[host]
        when {
            queue != null -> if (queue.size > 1) queue.removeFirst() else queue.first()
            host.any { it == ':' } || host.all { it in '0'..'9' || it == '.' } -> listOf(InetAddress.getByName(host))
            else -> throw UnknownHostException(host)
        }
    }

    private val transport = HopTransport { target, request, budget, _ ->
        budget.requireRemaining()
        val record = Sent(target, String(request, Charsets.US_ASCII))
        sent += record
        script(record)
    }

    private val direct = Executor { it.run() }

    private fun tracer(timeout: Long = 5_000): SocketRedirectTracer =
        SocketRedirectTracer(resolver, transport, direct, { now }, timeout)

    private fun url(raw: String) = checkNotNull(CanonicalUrl.of(decode<ScanPayload.Url>(raw)))

    private fun redirect(location: String, status: Int = 302) = ResponseHead(status, listOf(location))

    private fun final() = ResponseHead(200, emptyList())

    private fun host(name: String, vararg addresses: InetAddress) {
        answers[name] = ArrayDeque(listOf(addresses.toList()))
    }

    private fun trace(start: String, timeout: Long = 5_000): TraceOutcome = runBlocking {
        tracer(timeout).trace(url(start))
    }

    private fun completed(start: String): RedirectTrace = (trace(start) as TraceOutcome.Completed).trace

    @Test
    fun `a normal chain is followed to its destination`() {
        host("a.example", public1)
        host("b.example", public2)
        script = {
            when (it.path) {
                "/start" -> redirect("https://b.example/mid")
                "/mid" -> redirect("/end?x=1", 301)
                else -> final()
            }
        }
        // b.example/mid -> /end is relative to b.example
        val trace = completed("https://a.example/start")
        assertEquals(listOf("https://b.example/mid", "https://b.example/end?x=1"), trace.hops.map { it.value })
        assertEquals(TraceEnd.Final, trace.end)
        assertEquals("https://b.example/end?x=1", trace.last.value)
        assertEquals(listOf("HEAD", "HEAD", "HEAD"), sent.map { it.method })
        assertEquals(listOf(public1, public2, public2), sent.map { it.target.address })
        assertEquals(listOf("a.example", "b.example", "b.example"), sent.map { it.target.host })
    }

    @Test
    fun `the scanned address answering directly is a trace without hops`() {
        host("a.example", public1)
        val trace = completed("https://a.example/")
        assertTrue(trace.hops.isEmpty())
        assertEquals(TraceEnd.Final, trace.end)
        assertEquals("https://a.example/", trace.last.value)
    }

    @Test
    fun `a redirect status without a location ends the chain there`() {
        host("a.example", public1)
        script = { ResponseHead(302, emptyList()) }
        assertEquals(TraceEnd.Final, completed("https://a.example/").end)
    }

    @Test
    fun `the request carries the host and port headers and nothing identifying`() {
        host("a.example", public1)
        completed("https://a.example:443/p?q=1#frag")
        val request = sent.single()
        assertEquals(443, request.target.port)
        assertEquals("a.example", request.header)
        assertEquals("/p?q=1", request.path)
        assertFalse(request.text.contains("Cookie", ignoreCase = true))
        assertFalse(request.text.contains("frag"))
    }

    @Test
    fun `a server that refuses HEAD gets one byte range GET on the same checked address`() {
        host("a.example", public1)
        script = {
            when {
                it.target.host != "a.example" -> final()
                it.method == "HEAD" -> ResponseHead(405, emptyList())
                else -> redirect("https://b.example/")
            }
        }
        host("b.example", public2)
        val trace = completed("https://a.example/")
        assertEquals(listOf("HEAD", "GET", "HEAD"), sent.map { it.method })
        assertTrue(sent[1].text.contains("Range: bytes=0-0"))
        assertEquals(sent[0].target.address, sent[1].target.address)
        assertEquals(1, resolutions.count { it == "a.example" })
        assertEquals(1, trace.hops.size)
    }

    @Test
    fun `a loop is detected`() {
        host("a.example", public1)
        script = {
            if (it.path == "/a") redirect("/b") else redirect("/a#again")
        }
        val trace = completed("https://a.example/a")
        assertEquals(TraceEnd.Loop, trace.end)
        assertEquals(listOf("https://a.example/b"), trace.hops.map { it.value })
    }

    @Test
    fun `a self redirect is a loop`() {
        host("a.example", public1)
        script = { redirect("https://A.EXAMPLE/") }
        assertEquals(TraceEnd.Loop, completed("https://a.example/").end)
    }

    @Test
    fun `five hops are followed and a sixth redirect stops the chain`() {
        host("a.example", public1)
        script = { redirect("/p${sent.size}") }
        val trace = completed("https://a.example/")
        assertEquals(TraceEnd.TooManyHops, trace.end)
        assertEquals(5, trace.hops.size)
        assertEquals(6, sent.size)
    }

    @Test
    fun `exactly five hops ending on a page is a normal destination`() {
        host("a.example", public1)
        script = { if (sent.size <= 5) redirect("/p${sent.size}") else final() }
        val trace = completed("https://a.example/")
        assertEquals(TraceEnd.Final, trace.end)
        assertEquals(5, trace.hops.size)
    }

    @Test
    fun `a hop to http is reported and never contacted`() {
        host("a.example", public1)
        host("b.example", public2)
        script = { if (it.path == "/") redirect("https://b.example/x") else redirect("http://c.example/plain") }
        val trace = completed("https://a.example/")
        val end = trace.end as TraceEnd.InsecureHop
        assertEquals("http://c.example/plain", end.target.value)
        assertEquals(listOf("https://b.example/x"), trace.hops.map { it.value })
        assertEquals(2, sent.size)
        assertFalse(resolutions.contains("c.example"))
    }

    @Test
    fun `only port 443 is ever contacted, for the scanned link and for a hop`() {
        host("a.example", public1)
        val start = completed("https://a.example:8443/")
        assertEquals(TraceEnd.UnsupportedPort(url("https://a.example:8443/")), start.end)
        assertTrue(sent.isEmpty())
        assertTrue(resolutions.isEmpty())
        script = { redirect("https://a.example:22/x") }
        val hop = completed("https://a.example/")
        assertEquals(TraceEnd.UnsupportedPort(url("https://a.example:22/x")), hop.end)
        assertEquals(1, hop.hops.size)
        assertEquals(1, sent.size)
    }

    @Test
    fun `a refused execution is busy, not a network failure`() {
        val full =
            SocketRedirectTracer(resolver, transport, { throw java.util.concurrent.RejectedExecutionException() })
        assertEquals(TraceOutcome.Failed(TraceFailure.BUSY), runBlocking { full.trace(url("https://a.example/")) })
    }

    @Test
    fun `a plain http start is not traced`() {
        host("a.example", public1)
        val outcome = trace("http://a.example/")
        assertEquals(TraceOutcome.Failed(TraceFailure.NOT_HTTPS), outcome)
        assertTrue(sent.isEmpty())
        assertTrue(resolutions.isEmpty())
    }

    @Test
    fun `private loopback link-local and mapped addresses are never contacted`() {
        val mapped = InetAddress.getByAddress(
            "a.example",
            byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -1, -1, 10, 0, 0, 1),
        )
        listOf(
            byteArrayOf(10, 0, 0, 1),
            byteArrayOf(127, 0, 0, 1),
            byteArrayOf(-87, -2, -87, -2),
            byteArrayOf(-64, -88, 1, 1),
            byteArrayOf(100, 64, 0, 1),
            byteArrayOf(0, 0, 0, 0),
        ).map { InetAddress.getByAddress("a.example", it) }.plus(mapped).forEach { blocked ->
            sent.clear()
            host("a.example", blocked)
            val trace = completed("https://a.example/")
            assertEquals(TraceEnd.BlockedAddress(url("https://a.example/")), trace.end)
            assertTrue(sent.isEmpty())
        }
    }

    @Test
    fun `one blocked address among public ones refuses the host`() {
        host("a.example", public1, InetAddress.getByAddress("a.example", byteArrayOf(127, 0, 0, 1)))
        assertTrue(completed("https://a.example/").end is TraceEnd.BlockedAddress)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `an IP literal host is checked too`() {
        assertTrue(completed("https://127.0.0.1/").end is TraceEnd.BlockedAddress)
        assertTrue(completed("https://[::1]/").end is TraceEnd.BlockedAddress)
        assertTrue(completed("https://0x7f.1/").end is TraceEnd.BlockedAddress)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `a hop to a name that resolves to the local network stops before any request to it`() {
        host("a.example", public1)
        host("router.evil.example", InetAddress.getByAddress("router.evil.example", byteArrayOf(-64, -88, 0, 1)))
        script = { redirect("https://router.evil.example/admin") }
        val trace = completed("https://a.example/")
        assertEquals(TraceEnd.BlockedAddress(url("https://router.evil.example/admin")), trace.end)
        assertEquals(1, sent.size)
    }

    @Test
    fun `a hostname is resolved once per trace, so a second answer is never used (rebinding)`() {
        answers["a.example"] =
            ArrayDeque(
                listOf(listOf(public1), listOf(InetAddress.getByAddress("a.example", byteArrayOf(127, 0, 0, 1)))),
            )
        script = { if (sent.size < 3) redirect("/p${sent.size}") else final() }
        val trace = completed("https://a.example/")
        assertEquals(TraceEnd.Final, trace.end)
        assertEquals(1, resolutions.count { it == "a.example" })
        assertTrue(sent.all { it.target.address == public1 })
    }

    @Test
    fun `the connection goes to the checked address, whatever the name`() {
        val ip = InetAddress.getByAddress("whatever", byteArrayOf(8, 8, 4, 4))
        host("a.example", ip)
        completed("https://a.example/")
        assertEquals(ip, sent.single().target.address)
    }

    @Test
    fun `an unreachable first address falls through to the next checked one`() {
        host("a.example", public1, public2)
        var tries = 0
        val flaky = HopTransport { target, request, budget, abort ->
            if (tries++ == 0) throw IOException("refused")
            transport.exchange(target, request, budget, abort)
        }
        val outcome =
            runBlocking { SocketRedirectTracer(resolver, flaky, direct, { now }).trace(url("https://a.example/")) }
        assertTrue(outcome is TraceOutcome.Completed)
        assertEquals(public2, sent.single().target.address)
    }

    @Test
    fun `malformed and hostile locations stop the chain with the raw text kept short`() {
        host("a.example", public1)
        listOf(
            "javascript:alert(1)",
            "data:text/html,x",
            "file:///etc/passwd",
            "intent://x#Intent;end",
            "https://",
            "/ok\r\nSet-Cookie: x",
            "ftp://a.example/",
            "a".repeat(10_000),
        ).forEach { bad ->
            sent.clear()
            script = { redirect(bad) }
            val end = completed("https://a.example/").end as TraceEnd.BadLocation
            assertTrue(end.raw.value.length <= 200)
            assertEquals(1, sent.size)
        }
    }

    @Test
    fun `two different locations are not trusted`() {
        host("a.example", public1)
        script = { ResponseHead(302, listOf("https://b.example/", "https://c.example/")) }
        assertTrue(completed("https://a.example/").end is TraceEnd.BadLocation)
    }

    @Test
    fun `a relative location is resolved against the hop that sent it`() {
        host("a.example", public1)
        script = { if (it.path == "/x/y") redirect("../z") else final() }
        assertEquals("https://a.example/z", completed("https://a.example/x/y").last.value)
    }

    @Test
    fun `an unknown host and a refused connection are a network failure`() {
        assertEquals(TraceOutcome.Failed(TraceFailure.NETWORK), trace("https://nowhere.example/"))
        host("a.example", public1)
        script = { throw IOException("reset") }
        assertEquals(TraceOutcome.Failed(TraceFailure.NETWORK), trace("https://a.example/"))
        script = { throw IllegalStateException("bug") }
        assertEquals(TraceOutcome.Failed(TraceFailure.NETWORK), trace("https://a.example/"))
    }

    @Test
    fun `a host with no address is a network failure`() {
        host("a.example")
        assertEquals(TraceOutcome.Failed(TraceFailure.NETWORK), trace("https://a.example/"))
    }

    @Test
    fun `a socket timeout is a timeout`() {
        host("a.example", public1)
        script = { throw SocketTimeoutException() }
        assertEquals(TraceOutcome.Failed(TraceFailure.TIMEOUT), trace("https://a.example/"))
    }

    @Test
    fun `the whole chain shares one 5 second budget`() {
        host("a.example", public1)
        script = {
            now += 2_000
            redirect("/p${sent.size}")
        }
        // 3 requests of 2 s each: the third starts after 4 s, the fourth would start after 6 s.
        assertEquals(TraceOutcome.Failed(TraceFailure.TIMEOUT), trace("https://a.example/"))
        assertEquals(3, sent.size)
    }

    @Test
    fun `a resolver that never answers releases the caller after the budget`() {
        val hung = CountDownLatch(1)
        val blocking = HostResolver {
            hung.await()
            listOf(public1)
        }
        val real =
            SocketRedirectTracer(blocking, transport, {
                Thread(it).apply { isDaemon = true }.start()
            }, timeoutMillis = 150)
        val started = System.nanoTime()
        val outcome = runBlocking { real.trace(url("https://a.example/")) }
        assertEquals(TraceOutcome.Failed(TraceFailure.TIMEOUT), outcome)
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 3_000)
        hung.countDown()
    }

    @Test
    fun `cancelling the caller closes the connection under way`() {
        host("a.example", public1)
        val closed = AtomicBoolean(false)
        val entered = CountDownLatch(1)
        val blocking = HopTransport { _, _, _, abort ->
            val gate = CountDownLatch(1)
            abort.register(
                Closeable {
                    closed.set(true)
                    gate.countDown()
                },
            )
            entered.countDown()
            gate.await()
            throw IOException("closed")
        }
        val real = SocketRedirectTracer(resolver, blocking, { Thread(it).apply { isDaemon = true }.start() })
        runBlocking {
            val job: Job = launch { real.trace(url("https://a.example/")) }
            while (entered.count > 0) delay(5)
            job.cancel()
            job.join()
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (!closed.get() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue(closed.get())
    }

    @Test
    fun `an abort handle refuses new connections once aborted`() {
        val handle = AbortHandle()
        handle.abort()
        val closed = AtomicBoolean(false)
        var thrown: IOException? = null
        try {
            handle.register(Closeable { closed.set(true) })
        } catch (e: IOException) {
            thrown = e
        }
        assertTrue(closed.get())
        assertEquals("aborted", thrown?.message)
    }
}
