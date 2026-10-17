package com.lookfirst.data.network

import com.lookfirst.data.reputation.UtcTime
import com.lookfirst.domain.rdap.RDAP_MAX_BODY_BYTES
import com.lookfirst.domain.rdap.RDAP_MAX_REDIRECTS
import com.lookfirst.domain.rdap.RdapFailure
import com.lookfirst.domain.rdap.RdapOutcome
import com.lookfirst.domain.rdap.RdapServer
import com.lookfirst.domain.rdap.RdapTarget
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

class SocketRdapClientTest {
    private class Sent(val target: HopTarget, val text: String) {
        val firstLine: String get() = text.lineSequence().first()
        val path: String get() = firstLine.substringAfter(' ').substringBefore(' ')
        fun header(name: String): String? =
            text.lineSequence().firstOrNull { it.startsWith("$name:", ignoreCase = true) }?.substringAfter(':')?.trim()
    }

    private val registry =
        InetAddress.getByAddress("rdap.verisign.com", byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34))
    private val other = InetAddress.getByAddress("rdap.other.example", byteArrayOf(8, 8, 8, 8))

    private var now = 0L
    private val wall = UtcTime.parseInstant("2026-10-17T12:00:00Z")!!
    private val resolutions = mutableListOf<String>()
    private val answers = mutableMapOf<String, List<InetAddress>>()
    private val sent = mutableListOf<Sent>()
    private var script: (Sent) -> ResponseHead = { found("2026-10-10T00:00:00Z") }

    private val resolver = HostResolver { host ->
        resolutions += host
        answers[host] ?: throw UnknownHostException(host)
    }

    private val transport = HopTransport { target, request, budget, _ ->
        budget.requireRemaining()
        val record = Sent(target, String(request, Charsets.US_ASCII))
        sent += record
        script(record)
    }

    private val direct = Executor { it.run() }

    private val server = RdapServer("rdap.verisign.com", "/com/v1/")
    private val target = RdapTarget("example.com", server)

    private fun client(timeout: Long = 5_000, transport: HopTransport = this.transport) =
        SocketRdapClient(resolver, transport, direct, { now }, timeout, { wall })

    private fun lookup(t: RdapTarget = target, timeout: Long = 5_000): RdapOutcome =
        runBlocking { client(timeout).lookup(t) }

    private fun found(date: String, name: String = "EXAMPLE.COM") = ResponseHead(
        200,
        emptyList(),
        (
            """{"objectClassName":"domain","ldhName":"$name",""" +
                """"events":[{"eventAction":"registration","eventDate":"$date"}]}"""
            ).toByteArray(),
    )

    private fun failure(outcome: RdapOutcome) = (outcome as RdapOutcome.Failed).reason

    init {
        answers["rdap.verisign.com"] = listOf(registry)
        answers["rdap.other.example"] = listOf(other)
    }

    @Test
    fun `a 200 with a registration date gives the date and the age in whole days`() {
        val outcome = lookup() as RdapOutcome.Registered
        assertEquals(UtcTime.parseInstant("2026-10-10T00:00:00Z"), outcome.registeredAtMillis)
        assertEquals(7, outcome.ageDays)
    }

    @Test
    fun `the request is a plain get of the domain on the registry, to a checked ip on 443`() {
        lookup()
        val request = sent.single()
        assertEquals("GET /com/v1/domain/example.com HTTP/1.1", request.firstLine)
        assertEquals("rdap.verisign.com", request.header("Host"))
        assertEquals("application/rdap+json", request.header("Accept"))
        assertEquals(registry, request.target.address)
        assertEquals("rdap.verisign.com", request.target.host)
        assertEquals(443, request.target.port)
        assertEquals(RDAP_MAX_BODY_BYTES, request.target.maxBody)
        val names = request.text.lineSequence().map { it.substringBefore(':').lowercase() }.toSet()
        assertTrue(names.intersect(setOf("cookie", "authorization", "referer", "origin", "range")).isEmpty())
        assertEquals(listOf("rdap.verisign.com"), resolutions)
    }

    @Test
    fun `only the registrable domain reaches the registry, not a path or a scanned address`() {
        lookup(RdapTarget("example.co.uk", RdapServer("rdap.verisign.com", "/")))
        val text = sent.single().text
        assertTrue(text.startsWith("GET /domain/example.co.uk HTTP/1.1"))
        assertFalse(text.contains("login", ignoreCase = true))
    }

    @Test
    fun `an answer without a registration event is a bad answer`() {
        script = { ResponseHead(200, emptyList(), """{"objectClassName":"domain","events":[]}""".toByteArray()) }
        assertEquals(RdapFailure.BAD_ANSWER, failure(lookup()))
    }

    @Test
    fun `an invalid, implausible or future date is a bad answer`() {
        for (date in listOf("not a date", "1900-01-01T00:00:00Z", "2030-01-01T00:00:00Z", "2026-10-17", "")) {
            script = { found(date) }
            assertEquals(date, RdapFailure.BAD_ANSWER, failure(lookup()))
        }
    }

    @Test
    fun `an answer that names another domain is a bad answer`() {
        script = { found("2026-10-10T00:00:00Z", name = "OTHER.COM") }
        assertEquals(RdapFailure.BAD_ANSWER, failure(lookup()))
    }

    @Test
    fun `a body that is not json is a bad answer`() {
        script = { ResponseHead(200, emptyList(), "<html>nope</html>".toByteArray()) }
        assertEquals(RdapFailure.BAD_ANSWER, failure(lookup()))
        script = { ResponseHead(200, emptyList(), ByteArray(0)) }
        assertEquals(RdapFailure.BAD_ANSWER, failure(lookup()))
    }

    @Test
    fun `a registry 404 is unknown to the registry`() {
        script = { ResponseHead(404, emptyList()) }
        assertEquals(RdapOutcome.NotFound, lookup())
    }

    @Test
    fun `other statuses are a failure`() {
        for (status in listOf(204, 400, 401, 403, 429, 500, 503)) {
            script = { ResponseHead(status, emptyList()) }
            assertEquals("$status", RdapFailure.NETWORK, failure(lookup()))
        }
    }

    @Test
    fun `a body that is too big, as the transport reports it, is a failure`() {
        val tooBig = HopTransport { _, _, _, _ -> throw java.net.ProtocolException("body too large") }
        assertEquals(RdapFailure.NETWORK, failure(runBlocking { client(transport = tooBig).lookup(target) }))
    }

    @Test
    fun `a redirect to the same host is followed`() {
        script = {
            if (it.path.startsWith("/com/")) {
                ResponseHead(301, listOf("/v2/domain/example.com"))
            } else {
                found("2026-10-10T00:00:00Z")
            }
        }
        assertTrue(lookup() is RdapOutcome.Registered)
        assertEquals(listOf("/com/v1/domain/example.com", "/v2/domain/example.com"), sent.map { it.path })
        assertEquals(listOf("rdap.verisign.com"), resolutions)
    }

    @Test
    fun `an absolute same-host location over https is followed`() {
        script = {
            if (it.path.startsWith("/com")) {
                ResponseHead(308, listOf("https://rdap.verisign.com/moved/example.com"))
            } else {
                found("2026-10-10T00:00:00Z")
            }
        }
        assertTrue(lookup() is RdapOutcome.Registered)
    }

    @Test
    fun `a redirect to another host is a failure and that host is never contacted nor resolved`() {
        script = { ResponseHead(302, listOf("https://rdap.other.example/domain/example.com")) }
        assertEquals(RdapFailure.BAD_ANSWER, failure(lookup()))
        assertEquals(1, sent.size)
        assertEquals(listOf("rdap.verisign.com"), resolutions)
    }

    @Test
    fun `redirects that leave the host, the scheme or the port are failures`() {
        val bad = listOf(
            "https://rdap.verisign.com.evil.example/x",
            "https://evil.example/x",
            "https://rdap.verisign.com@evil.example/x",
            "http://rdap.verisign.com/x",
            "https://rdap.verisign.com:8443/x",
            "//evil.example/x",
            "javascript:alert(1)",
            "https://127.0.0.1/x",
            "",
        )
        for (location in bad) {
            sent.clear()
            script = { ResponseHead(302, listOf(location)) }
            assertEquals(location, RdapFailure.BAD_ANSWER, failure(lookup()))
            assertEquals(location, 1, sent.size)
        }
    }

    @Test
    fun `a redirect with two different locations, or none, is a failure`() {
        script = { ResponseHead(302, listOf("/a/", "/b/")) }
        assertEquals(RdapFailure.BAD_ANSWER, failure(lookup()))
        script = { ResponseHead(302, emptyList()) }
        assertEquals(RdapFailure.BAD_ANSWER, failure(lookup()))
    }

    @Test
    fun `redirects are bounded`() {
        var n = 0
        script = { ResponseHead(302, listOf("/hop/${++n}")) }
        assertEquals(RdapFailure.NETWORK, failure(lookup()))
        assertEquals(RDAP_MAX_REDIRECTS + 1, sent.size)
    }

    @Test
    fun `a registry that resolves to a private address is never contacted`() {
        for (address in listOf(
            "10.0.0.1",
            "127.0.0.1",
            "192.168.1.1",
            "169.254.169.254",
            "::1",
            "fd00::1",
            "100.64.0.1",
        )) {
            answers["rdap.verisign.com"] = listOf(InetAddress.getByName(address))
            assertEquals(address, RdapFailure.NETWORK, failure(lookup()))
        }
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `one private address among public ones refuses the host`() {
        answers["rdap.verisign.com"] = listOf(registry, InetAddress.getByName("10.0.0.1"))
        assertEquals(RdapFailure.NETWORK, failure(lookup()))
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `an unresolvable registry is a network failure`() {
        answers.clear()
        assertEquals(RdapFailure.NETWORK, failure(lookup()))
    }

    @Test
    fun `a transport that fails on the first address falls through to the next, a timeout ends it`() {
        answers["rdap.verisign.com"] = listOf(registry, other)
        var calls = 0
        val flaky = HopTransport { t, _, _, _ ->
            calls++
            if (t.address == registry) throw IOException("refused") else found("2026-10-10T00:00:00Z")
        }
        assertTrue(runBlocking { client(transport = flaky).lookup(target) } is RdapOutcome.Registered)
        assertEquals(2, calls)
        val slow = HopTransport { _, _, _, _ -> throw SocketTimeoutException() }
        assertEquals(RdapFailure.TIMEOUT, failure(runBlocking { client(transport = slow).lookup(target) }))
    }

    @Test
    fun `an unexpected exception in the transport ends the check, never the app`() {
        val broken = HopTransport { _, _, _, _ -> throw IllegalStateException("bug") }
        assertEquals(RdapFailure.NETWORK, failure(runBlocking { client(transport = broken).lookup(target) }))
    }

    @Test
    fun `a target whose domain could break the request line is refused before anything is resolved`() {
        for (domain in listOf("a b.com", "a\r\nX: y.com", "é.com", "a/b.com", "", "a?b.com", "A.com")) {
            assertEquals(domain, RdapFailure.BAD_ANSWER, failure(lookup(RdapTarget(domain, server))))
        }
        assertTrue(resolutions.isEmpty())
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `a blocked executor reports a network failure`() {
        val refusing = Executor { throw java.util.concurrent.RejectedExecutionException() }
        val outcome = runBlocking {
            SocketRdapClient(resolver, transport, refusing, { now }, 5_000, { wall }).lookup(target)
        }
        assertEquals(RdapFailure.NETWORK, failure(outcome))
    }

    @Test
    fun `the whole budget is spent across the resolution and the request`() {
        val slow = HopTransport { _, _, budget, _ ->
            now += 6_000
            budget.requireRemaining()
            found("2026-10-10T00:00:00Z")
        }
        assertEquals(RdapFailure.TIMEOUT, failure(runBlocking { client(transport = slow).lookup(target) }))
    }

    @Test
    fun `a resolver that never answers is abandoned after the budget and the caller is released`() {
        val release = CountDownLatch(1)
        val blocking = HostResolver {
            release.await(30, TimeUnit.SECONDS)
            throw UnknownHostException("late")
        }
        val pool = Executor { Thread(it).apply { isDaemon = true }.start() }
        val started = System.nanoTime()
        val outcome = runBlocking {
            SocketRdapClient(blocking, transport, pool, timeoutMillis = 200, wallClock = { wall }).lookup(target)
        }
        release.countDown()
        assertEquals(RdapFailure.TIMEOUT, failure(outcome))
        assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000)
    }

    @Test
    fun `cancelling the caller closes the open connection`() {
        val closed = AtomicBoolean(false)
        val entered = CountDownLatch(1)
        val hanging = HopTransport { _, _, _, abort ->
            abort.register(Closeable { closed.set(true) })
            entered.countDown()
            Thread.sleep(2_000)
            found("2026-10-10T00:00:00Z")
        }
        val pool = Executor { Thread(it).apply { isDaemon = true }.start() }
        runBlocking {
            val job: Job = launch {
                SocketRdapClient(resolver, hanging, pool, timeoutMillis = 10_000, wallClock = { wall }).lookup(target)
            }
            while (entered.count > 0) delay(10)
            job.cancel()
            job.join()
        }
        assertTrue(closed.get())
    }
}
