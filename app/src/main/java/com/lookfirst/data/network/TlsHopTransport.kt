package com.lookfirst.data.network

import android.os.Build
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.security.cert.X509Certificate
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

private val ALLOWED_PROTOCOLS = setOf("TLSv1.3", "TLSv1.2")

/** The system resolver. Blocking, with no timeout of its own: the tracer abandons it after the budget. */
internal object SystemHostResolver : HostResolver {
    @Throws(IOException::class)
    override fun resolve(host: String): List<InetAddress> = InetAddress.getAllByName(host).toList()
}

/**
 * One TLS connection per request, to an IP address that was already checked. The socket is created directly (no system
 * proxy: [Proxy.NO_PROXY]), the TLS layer is the platform default (system trust anchors and the app's Network Security
 * Config), the server name sent is the host we meant to reach, and the certificate is checked for that host
 * ([CertificateHostCheck]). Only the response head is read, then everything is closed.
 */
internal class TlsHopTransport(private val sslFactory: SSLSocketFactory = SSLSocketFactoryHolder.default) :
    HopTransport {
    @Throws(IOException::class)
    override fun exchange(target: HopTarget, request: ByteArray, budget: TimeBudget, abort: AbortHandle): ResponseHead {
        val plain = Socket(Proxy.NO_PROXY)
        abort.register(plain)
        try {
            plain.tcpNoDelay = true
            plain.connect(InetSocketAddress(target.address, target.port), budget.requireRemaining())
            plain.soTimeout = budget.requireRemaining()
            val tls = sslFactory.createSocket(plain, target.host, target.port, true) as SSLSocket
            abort.register(tls)
            configure(tls)
            tls.soTimeout = budget.requireRemaining()
            tls.startHandshake()
            verify(target.host, tls)
            tls.outputStream.write(request)
            tls.outputStream.flush()
            return HttpHead.read(tls.inputStream) { tls.soTimeout = budget.requireRemaining() }
        } finally {
            try {
                plain.close()
            } catch (ignored: IOException) {
                // Nothing left to release.
            }
        }
    }

    private fun configure(tls: SSLSocket) {
        val protocols = tls.supportedProtocols.filter { it in ALLOWED_PROTOCOLS }
        if (protocols.isEmpty()) throw IOException("no TLS 1.2 or 1.3")
        tls.enabledProtocols = protocols.toTypedArray()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val parameters = tls.sslParameters
            parameters.endpointIdentificationAlgorithm = "HTTPS"
            tls.sslParameters = parameters
        }
    }

    private fun verify(host: String, tls: SSLSocket) {
        val certificate = tls.session.peerCertificates.firstOrNull() as? X509Certificate
        if (certificate == null || !CertificateHostCheck.matches(host, certificate)) {
            throw SSLPeerUnverifiedException("certificate does not name the host")
        }
    }
}

/** The platform's default factory, looked up once. */
private object SSLSocketFactoryHolder {
    val default: SSLSocketFactory by lazy { SSLSocketFactory.getDefault() as SSLSocketFactory }
}
