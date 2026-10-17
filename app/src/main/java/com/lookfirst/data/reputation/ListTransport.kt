package com.lookfirst.data.reputation

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI

private const val CONNECT_TIMEOUT_MILLIS = 15_000
private const val READ_TIMEOUT_MILLIS = 30_000
private const val STATUS_OK = 200

/** One answer: the status, the raw `Location` header and, for a 200 only, the body. Closing releases the connection. */
internal class TransportResponse(
    val status: Int,
    val location: String?,
    val body: InputStream?,
    private val onClose: () -> Unit = {},
) : Closeable {
    override fun close() {
        try {
            body?.close()
        } finally {
            onClose()
        }
    }
}

/**
 * A single GET that never follows a redirect and sends exactly `headers`. The downloader decides about redirects,
 * sizes and headers; tests replace this seam with a fake.
 */
internal fun interface ListTransport {
    @Throws(IOException::class)
    fun get(url: URI, headers: Map<String, String>): TransportResponse
}

/**
 * `HttpURLConnection` transport: https only, no automatic redirect, no cache, no cookie jar, system TLS validation
 * (no pinning, the manifest signature protects integrity), timeouts on connect and on every read.
 */
internal class UrlConnectionTransport : ListTransport {
    override fun get(url: URI, headers: Map<String, String>): TransportResponse {
        if (!url.scheme.equals("https", ignoreCase = true)) throw IOException("https only")
        val connection = url.toURL().openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.requestMethod = "GET"
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            val status = connection.responseCode
            val body = if (status == STATUS_OK) connection.inputStream else null
            return TransportResponse(status, connection.getHeaderField("Location"), body) { connection.disconnect() }
        } catch (failure: IOException) {
            connection.disconnect()
            throw failure
        }
    }
}
