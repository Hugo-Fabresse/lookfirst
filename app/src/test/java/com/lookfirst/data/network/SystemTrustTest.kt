package com.lookfirst.data.network

import com.lookfirst.data.platform.SdkVersion
import com.lookfirst.data.reputation.UrlConnectionTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URI
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

private const val SYSTEM_CERT = """-----BEGIN CERTIFICATE-----
MIIBSjCB8aADAgECAgkAuo5GXWZ8T14wCgYIKoZIzj0EAwIwDDEKMAgGA1UEAxMB
eDAgFw0yNjEwMTcyMTUyMTZaGA8yMTI2MDkyMzIxNTIxNlowDDEKMAgGA1UEAxMB
eDBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABGknq/EtPpH895YQRDPhwj1w26OA
owv3jqvKbsDcyDgjOpny+4PU++M3x+TzwVZ64OMdzZbeuoakWA0vThEdMb2jOjA4
MB0GA1UdDgQWBBT6TEz1rrLFWGSf/nef4pytNJImhzAXBgNVHREEEDAOggxnb29k
LmxmLnRlc3QwCgYIKoZIzj0EAwIDSAAwRQIhAL+ibHXKVT9kDJcMfZUiiDzoxApX
L3MNZV1gn9qdULw4AiAuGC6/i6o3CieSRFoDEl6eW8nv2f3licUk7U+NJX8qtA==
-----END CERTIFICATE-----
"""

private const val USER_CERT = """-----BEGIN CERTIFICATE-----
MIIBRzCB7qADAgECAgkAnP0e5ug2MaIwCgYIKoZIzj0EAwIwDDEKMAgGA1UEAxMB
eDAgFw0yNjEwMTcyMTUyMTZaGA8yMTI2MDkyMzIxNTIxNlowDDEKMAgGA1UEAxMB
eDBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABDYsEtlrQbCxoBrqDMYDjDtHZNtP
1LBp3SHU6qF4vJfTRNyLty2p3GSW7Lha33rxMtmdHyp1KJ6r0jA55C4pQ0OjNzA1
MB0GA1UdDgQWBBRFpoFdHcYrzl8zXb723ULwgZa8eTAUBgNVHREEDTALggkqLmxm
LnRlc3QwCgYIKoZIzj0EAwIDSAAwRQIhAPehlt+now9qrNf0yyHqKE92lpO2LSYl
OcPrZCj1L817AiAdaXV1Sn4/o0O6cjhWOZGk0b4DATZfMeCPI0zlNfUbig==
-----END CERTIFICATE-----
"""

private fun certificate(pem: String): X509Certificate = CertificateFactory.getInstance("X.509")
    .generateCertificate(ByteArrayInputStream(pem.toByteArray())) as X509Certificate

/** A stand-in for AndroidCAStore: `system:` and `user:` aliases like the platform's. */
private fun fakeAndroidCaStore(system: Boolean = true, user: Boolean = true): KeyStore {
    val store = KeyStore.getInstance(KeyStore.getDefaultType())
    store.load(null, null)
    if (system) store.setCertificateEntry("system:aaaa.0", certificate(SYSTEM_CERT))
    if (user) store.setCertificateEntry("user:bbbb.0", certificate(USER_CERT))
    return store
}

private fun sdk(level: Int) = SdkVersion { level }

class SystemTrustTest {
    @Test
    fun `the alias filter keeps system entries and drops user entries`() {
        val filtered = SystemAnchors.onlySystem(fakeAndroidCaStore())
        assertEquals(listOf("system:aaaa.0"), filtered.aliases().toList())
        assertEquals(certificate(SYSTEM_CERT), filtered.getCertificate("system:aaaa.0"))
    }

    @Test
    fun `a store with only user authorities fails closed`() {
        try {
            SystemAnchors.onlySystem(fakeAndroidCaStore(system = false))
            fail("expected a refusal")
        } catch (expected: IOException) {
            // No system authority: nothing may be trusted.
        }
    }

    @Test
    fun `the trust manager built from the filtered store refuses a user authority`() {
        val manager = managerFor(fakeAndroidCaStore())
        manager.checkServerTrusted(arrayOf(certificate(SYSTEM_CERT)), "ECDSA")
        try {
            manager.checkServerTrusted(arrayOf(certificate(USER_CERT)), "ECDSA")
            fail("a user-installed authority must not be trusted")
        } catch (expected: CertificateException) {
            // Expected.
        }
    }

    @Test
    fun `api 23 returns one cached factory for every client`() {
        var loads = 0
        val trust = SelectingSystemTrust(sdk(23)) {
            loads++
            fakeAndroidCaStore()
        }
        val first = trust.socketFactory()
        assertNotNull(first)
        assertSame(first, trust.socketFactory())
        assertEquals(1, loads)
    }

    @Test
    fun `api 24 and later keep the platform default and never touch the store`() {
        for (level in listOf(24, 28, 34, 36)) {
            val trust = SelectingSystemTrust(sdk(level)) { error("the store must not be read") }
            assertNull(trust.socketFactory())
        }
    }

    @Test
    fun `api 23 with an unavailable store refuses and never falls back to the default`() {
        val trust = SelectingSystemTrust(sdk(23)) { throw IOException("no AndroidCAStore") }
        repeat(2) {
            try {
                trust.socketFactory()
                fail("the platform default must never be used")
            } catch (expected: IOException) {
                // Closed failure, retried on the next call.
            }
        }
    }

    @Test
    fun `api 23 with only user authorities refuses`() {
        val trust = SelectingSystemTrust(sdk(23)) { fakeAndroidCaStore(system = false) }
        try {
            trust.socketFactory()
            fail("expected a refusal")
        } catch (expected: IOException) {
            assertTrue(expected.message.orEmpty().isNotEmpty())
        }
    }

    @Test
    fun `the list downloader fails closed before any connection when trust is unavailable`() {
        val transport = UrlConnectionTransport { throw IOException("closed") }
        try {
            transport.get(URI("https://127.0.0.1:9/list"), emptyMap())
            fail("expected a refusal")
        } catch (expected: IOException) {
            assertEquals("closed", expected.message)
        }
    }

    @Test
    fun `the tracer transport fails closed before any socket when trust is unavailable`() {
        val transport = TlsHopTransport { throw IOException("closed") }
        val budget = TimeBudget({ 0L }, 1_000)
        val target = HopTarget("example.org", java.net.InetAddress.getByName("127.0.0.1"), 9)
        try {
            transport.exchange(target, ByteArray(0), budget, AbortHandle())
            fail("expected a refusal")
        } catch (expected: IOException) {
            assertEquals("closed", expected.message)
        }
    }

    @Test
    fun `both clients ask the same shared trust for their factory`() {
        val asked = mutableListOf<SSLSocketFactory?>()
        val shared = SystemTrust { null.also { asked += it } }
        val budget = TimeBudget({ 0L }, 1_000)
        val target = HopTarget("example.org", java.net.InetAddress.getByName("127.0.0.1"), 9)
        runCatching { TlsHopTransport(shared).exchange(target, ByteArray(0), budget, AbortHandle()) }
        runCatching { UrlConnectionTransport(shared).get(URI("https://127.0.0.1:9/x"), emptyMap()) }
        assertEquals(2, asked.size)
        assertFalse(asked.any { it != null })
    }

    private fun managerFor(anchors: KeyStore): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(SystemAnchors.onlySystem(anchors))
        return factory.trustManagers.filterIsInstance<X509TrustManager>().single()
    }
}
