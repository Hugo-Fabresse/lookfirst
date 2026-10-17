package com.lookfirst.data.network

import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

private val MODERN_PROTOCOLS = setOf("TLSv1.3", "TLSv1.2")

/**
 * Wraps a factory so that every socket it creates enables TLS 1.2 and 1.3 only. Needed where the app supplies its own
 * factory (API 23), as the platform default of that API would otherwise enable older protocols on list downloads.
 * No supported modern protocol is a refusal.
 */
internal class ModernTlsSocketFactory(private val delegate: SSLSocketFactory) : SSLSocketFactory() {
    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites

    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    @Throws(IOException::class)
    override fun createSocket(s: Socket, host: String?, port: Int, autoClose: Boolean): Socket =
        restrict(delegate.createSocket(s, host, port, autoClose))

    @Throws(IOException::class)
    override fun createSocket(host: String?, port: Int): Socket = restrict(delegate.createSocket(host, port))

    @Throws(IOException::class)
    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        restrict(delegate.createSocket(host, port, localHost, localPort))

    @Throws(IOException::class)
    override fun createSocket(host: InetAddress?, port: Int): Socket = restrict(delegate.createSocket(host, port))

    @Throws(IOException::class)
    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        restrict(delegate.createSocket(address, port, localAddress, localPort))

    @Throws(IOException::class)
    override fun createSocket(): Socket = restrict(delegate.createSocket())

    private fun restrict(socket: Socket): Socket {
        if (socket is SSLSocket) {
            val protocols = socket.supportedProtocols.filter { it in MODERN_PROTOCOLS }
            if (protocols.isEmpty()) {
                socket.close()
                throw IOException("no TLS 1.2 or 1.3")
            }
            socket.enabledProtocols = protocols.toTypedArray()
        }
        return socket
    }
}
