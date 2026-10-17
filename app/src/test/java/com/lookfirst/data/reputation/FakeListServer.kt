package com.lookfirst.data.reputation

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.URI

internal class RecordedRequest(val url: URI, val headers: Map<String, String>)

/**
 * A fake of the Codeberg release assets. Routes are keyed by the full URL; a route is a ready response. Every
 * request is recorded so that tests can check what the downloader asked for and with which headers.
 */
internal class FakeListServer : ListTransport {
    val requests = mutableListOf<RecordedRequest>()
    private val routes = mutableMapOf<String, () -> TransportResponse>()
    var onRequest: (URI) -> Unit = {}
    var failure: IOException? = null

    val endpoint = ListsEndpoint()

    fun urlOf(tag: String, file: String) = "https://${endpoint.host}${endpoint.basePath}/$tag/$file"

    fun put(url: String, bytes: ByteArray) {
        routes[url] = { TransportResponse(200, null, ByteArrayInputStream(bytes)) }
    }

    fun putStatus(url: String, status: Int, location: String? = null) {
        routes[url] = { TransportResponse(status, location, null) }
    }

    /** Publishes a built bundle: `latest/manifest.*` and every file under its version tag. */
    fun publish(bundle: BuiltBundle, version: Long = BundleBuilder.version(bundle.spec.generatedAt)) {
        put(urlOf("latest", "manifest.json"), bundle.manifestFile.readBytes())
        put(urlOf("latest", "manifest.sig"), bundle.signatureFile.readBytes())
        BundleFile.entries.map { bundle.file(it) }.filter(File::isFile).forEach {
            put(urlOf(version.toString(), it.name), it.readBytes())
        }
    }

    override fun get(url: URI, headers: Map<String, String>): TransportResponse {
        requests += RecordedRequest(url, headers)
        onRequest(url)
        failure?.let { throw it }
        return routes[url.toString()]?.invoke() ?: TransportResponse(404, null, null)
    }

    fun paths(): List<String> = requests.map { it.url.path.removePrefix(endpoint.basePath) }
}
