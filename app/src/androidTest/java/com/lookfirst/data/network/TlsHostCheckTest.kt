package com.lookfirst.data.network

import android.util.Base64
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory

// Server identities: a certificate for good.lf.test and one for *.lf.test, PKCS12 (legacy encryption, readable on
// API 23), password "changeit", generated with keytool for this test only.
private const val STORE_PASSWORD = "changeit"
private val GOOD_STORE = listOf(
    "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",
    "zg4EFG3G+OaE1qaONdY799nUNibTotcgAgMBhqA=",
).joinToString("")
private val WILDCARD_STORE = listOf(
    "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",
    "C0QEFIAOGqeJtZpl2WBU3CkBr+MVEVHdAgMBhqA=",
).joinToString("")

private const val TIMEOUT_MILLIS = 5_000
private const val TLS_HANDSHAKE_RECORD = 22
private const val CLIENT_HELLO = 1
private const val SNI_EXTENSION = 0
private const val HOST_NAME_TYPE = 0

/**
 * Instrumented check of the TLS client against a local `SSLServerSocket` (milestone: phone or emulator at API 23, not
 * run in CI). Uses only the SDK: a plain relay in front of the TLS server reads the ClientHello so the SNI the client
 * really sent can be compared with the host, whatever the API level (server-side SNI APIs start at API 24).
 * The test trusts the test certificate itself through the factory it injects; what is under test is the SNI sent
 * and [CertificateHostCheck], which is the only host verification of the raw socket on API 23.
 */
class TlsHostCheckTest {
    private val cleanup = mutableListOf<AutoCloseable>()

    @After
    fun tearDown() {
        cleanup.forEach { runCatching { it.close() } }
    }

    @Test
    fun sniEqualsTheHostAndTheMatchingCertificateIsAccepted() {
        val server = startServer(GOOD_STORE)
        val status = request(server, "good.lf.test")
        assertEquals(200, status)
        assertEquals("good.lf.test", server.awaitSni())
    }

    @Test
    fun aCertificateForAnotherNameIsRefused() {
        val server = startServer(GOOD_STORE)
        assertRefused(server, "evil.lf.test")
        assertEquals("evil.lf.test", server.awaitSni())
    }

    @Test
    fun aWildcardCoversExactlyOneLabel() {
        val server = startServer(WILDCARD_STORE)
        assertEquals(200, request(server, "shop.lf.test"))
        assertRefused(server, "lf.test")
        assertRefused(server, "a.b.lf.test")
    }

    private fun assertRefused(server: TestServer, host: String) {
        try {
            request(server, host)
            fail("the certificate does not name $host")
        } catch (expected: SSLPeerUnverifiedException) {
            assertEquals("certificate does not name the host", expected.message)
        }
    }

    private fun request(server: TestServer, host: String): Int {
        val transport = TlsHopTransport { server.clientFactory }
        val target = HopTarget(host, InetAddress.getByName("127.0.0.1"), server.relayPort)
        val budget = TimeBudget({ System.nanoTime() / 1_000_000 }, TIMEOUT_MILLIS.toLong())
        val head = "HEAD / HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n".toByteArray()
        return transport.exchange(target, head, budget, AbortHandle()).status
    }

    private fun startServer(storeBase64: String): TestServer {
        val keyStore = KeyStore.getInstance("PKCS12")
        keyStore.load(ByteArrayInputStream(Base64.decode(storeBase64, Base64.DEFAULT)), STORE_PASSWORD.toCharArray())
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .also { it.init(keyStore, STORE_PASSWORD.toCharArray()) }.keyManagers
        val serverContext = SSLContext.getInstance("TLS").also { it.init(keyManagers, null, null) }
        val trusted = KeyStore.getInstance(KeyStore.getDefaultType()).also {
            it.load(null, null)
            it.setCertificateEntry("test", keyStore.getCertificate(keyStore.aliases().nextElement()))
        }
        val trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .also { it.init(trusted) }.trustManagers
        val clientContext = SSLContext.getInstance("TLS").also { it.init(null, trustManagers, null) }

        val tls = serverContext.serverSocketFactory.createServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))
            as SSLServerSocket
        val relay = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))
        cleanup += tls
        cleanup += relay
        val server = TestServer(clientContext.socketFactory, relay.localPort)
        Thread { serveTls(tls) }.apply { isDaemon = true }.start()
        Thread { serveRelay(relay, tls.localPort, server) }.apply { isDaemon = true }.start()
        return server
    }

    private fun serveTls(tls: SSLServerSocket) {
        while (true) {
            val socket = try {
                tls.accept()
            } catch (ignored: IOException) {
                return
            } as SSLSocket
            Thread {
                runCatching {
                    socket.soTimeout = TIMEOUT_MILLIS
                    socket.startHandshake()
                    val input = socket.inputStream
                    var last = 0
                    var seen = 0
                    while (seen < 4 && input.read().also { last = it } >= 0) {
                        seen = if (last == '\r'.code || last == '\n'.code) seen + 1 else 0
                    }
                    socket.outputStream.write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".toByteArray())
                    socket.outputStream.flush()
                }
                runCatching { socket.close() }
            }.apply { isDaemon = true }.start()
        }
    }

    /** Accepts the client, reads the first record (the ClientHello), then copies bytes both ways. */
    private fun serveRelay(relay: ServerSocket, tlsPort: Int, server: TestServer) {
        while (true) {
            val client = try {
                relay.accept()
            } catch (ignored: IOException) {
                return
            }
            Thread {
                runCatching {
                    client.soTimeout = TIMEOUT_MILLIS
                    val upstream = Socket(InetAddress.getByName("127.0.0.1"), tlsPort)
                    val hello = readRecord(client.getInputStream())
                    server.recordSni(parseSni(hello))
                    upstream.getOutputStream().write(hello)
                    upstream.getOutputStream().flush()
                    Thread { pump(client.getInputStream(), upstream.getOutputStream()) }
                        .apply { isDaemon = true }.start()
                    pump(upstream.getInputStream(), client.getOutputStream())
                    upstream.close()
                }
                runCatching { client.close() }
            }.apply { isDaemon = true }.start()
        }
    }

    private fun pump(from: InputStream, to: java.io.OutputStream) {
        val buffer = ByteArray(4096)
        runCatching {
            while (true) {
                val n = from.read(buffer)
                if (n < 0) break
                to.write(buffer, 0, n)
                to.flush()
            }
        }
    }

    private fun readRecord(input: InputStream): ByteArray {
        val header = ByteArray(5)
        readFully(input, header)
        val length = ((header[3].toInt() and 0xFF) shl 8) or (header[4].toInt() and 0xFF)
        val body = ByteArray(length)
        readFully(input, body)
        return ByteArrayOutputStream().also {
            it.write(header)
            it.write(body)
        }.toByteArray()
    }

    private fun readFully(input: InputStream, into: ByteArray) {
        var offset = 0
        while (offset < into.size) {
            val n = input.read(into, offset, into.size - offset)
            if (n < 0) throw IOException("short read")
            offset += n
        }
    }

    /** The host_name of the server_name extension of a ClientHello record, or null. */
    private fun parseSni(record: ByteArray): String? {
        var p = 5
        if (record[0].toInt() != TLS_HANDSHAKE_RECORD || record[p].toInt() != CLIENT_HELLO) return null
        p += 4 + 2 + 32 // handshake header, version, random
        p += 1 + (record[p].toInt() and 0xFF) // session id
        p += 2 + u16(record, p) // cipher suites
        p += 1 + (record[p].toInt() and 0xFF) // compression
        val end = p + 2 + u16(record, p)
        p += 2
        while (p + 4 <= end) {
            val type = u16(record, p)
            val size = u16(record, p + 2)
            if (type == SNI_EXTENSION && record[p + 6].toInt() == HOST_NAME_TYPE) {
                return String(record, p + 9, u16(record, p + 7), Charsets.US_ASCII)
            }
            p += 4 + size
        }
        return null
    }

    private fun u16(bytes: ByteArray, at: Int) =
        ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)

    private class TestServer(val clientFactory: javax.net.ssl.SSLSocketFactory, val relayPort: Int) {
        private val latch = CountDownLatch(1)

        @Volatile
        private var sni: String? = null

        fun recordSni(value: String?) {
            sni = value
            latch.countDown()
        }

        fun awaitSni(): String? {
            assertTrue(latch.await(TIMEOUT_MILLIS.toLong(), TimeUnit.MILLISECONDS))
            return sni
        }
    }
}
