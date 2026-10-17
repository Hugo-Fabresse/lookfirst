package com.lookfirst.data.reputation

import com.lookfirst.domain.host.PublicSuffixList
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Fixed "now" of the list tests: 2026-10-17T12:00:00Z. */
internal const val TEST_NOW: Long = 1_792_238_400_000L

internal const val TEST_DAY: Long = 24L * 60 * 60 * 1000

internal val TEST_PSL: PublicSuffixList =
    PublicSuffixList.parse(listOf("// icann", "com", "net", "uk", "co.uk", "// private", "#PRIVATE", "blogspot.com"))

internal val TEST_URL: URI = URI("https://lists.example.org/domains.txt")

/** A small source so that tests stay fast: 3 to 1000 entries, 64 KiB download cap. */
internal fun testSource(
    id: Int = SourceIds.CERT_PL,
    url: URI = TEST_URL,
    maxDownloadBytes: Long = 64 * 1024,
    minEntries: Long = 3,
    maxEntries: Long = 1000,
) = ListSource(id, "Test list $id", url, maxDownloadBytes, minEntries, maxEntries)

/** `count` registrable domains `<prefix>0.com`, `<prefix>1.com`... one per line. */
internal fun domainLines(count: Int, prefix: String = "site"): String =
    (0 until count).joinToString("\n", postfix = "\n") { "$prefix$it.com" }

internal fun parsed(vararg names: String, officials: Set<String> = emptySet()): ParseOutcome.Parsed {
    val file = java.io.File.createTempFile("list", ".txt")
    try {
        file.writeText(names.joinToString("\n") + "\n")
        return DomainListParser.parse(file, 1000, officials, TEST_PSL) as ParseOutcome.Parsed
    } finally {
        file.delete()
    }
}

/** Raw index keys written as `h:host` or `d:domain`, sorted and unique like the parser's output. */
internal fun keyed(vararg specs: String): ParseOutcome.Parsed {
    val keys = specs.map { Lfix.keyOf(it[0], it.substring(2)) xor Long.MIN_VALUE }.sorted().distinct()
    return ParseOutcome.Parsed(LongArray(keys.size) { keys[it] xor Long.MIN_VALUE }, 0)
}

/** A fake of the publisher's server: routes keyed by the full URL, every request recorded with its headers. */
internal class FakePublisher : ListTransport {
    val requests = mutableListOf<PublisherRequest>()
    private val routes = mutableMapOf<String, () -> TransportResponse>()
    var onRequest: (URI) -> Unit = {}
    var failure: IOException? = null

    fun put(url: URI, text: String, lastModified: String? = null) = putBytes(url, text.toByteArray(), lastModified)

    fun putBytes(url: URI, bytes: ByteArray, lastModified: String? = null) {
        routes[url.toString()] = { TransportResponse(200, null, ByteArrayInputStream(bytes), lastModified) }
    }

    fun putStream(url: URI, open: () -> InputStream) {
        routes[url.toString()] = { TransportResponse(200, null, open()) }
    }

    fun putStatus(url: URI, status: Int, location: String? = null) {
        routes[url.toString()] = { TransportResponse(status, location, null) }
    }

    override fun get(url: URI, headers: Map<String, String>): TransportResponse {
        requests += PublisherRequest(url, headers)
        onRequest(url)
        failure?.let { throw it }
        return routes[url.toString()]?.invoke() ?: TransportResponse(404, null, null)
    }
}

internal class PublisherRequest(val url: URI, val headers: Map<String, String>)

/** A source id that no real source uses: the second source of the multi-source tests. */
internal const val OTHER_SOURCE_ID = 99

/** Raw LFIX bytes for the format tests (the app itself writes indexes with `IndexWriter`). */
internal object TestIndex {
    fun indexOfKeys(sourceId: Int, keys: List<Long>, pslSha: String): ByteArray {
        val out = ByteArrayOutputStream()
        val header = ByteBuffer.allocate(64).order(ByteOrder.BIG_ENDIAN)
        header.put("LFIX".toByteArray(Charsets.US_ASCII))
        header.putShort(1)
        header.putShort(sourceId.toShort())
        header.putInt(keys.size)
        header.position(32)
        header.put(Hex.decodeLower(pslSha)!!)
        out.write(header.array())
        val body = ByteBuffer.allocate(keys.size * 8).order(ByteOrder.BIG_ENDIAN)
        keys.forEach { body.putLong(it) }
        out.write(body.array())
        return out.toByteArray()
    }

    /** `count` sorted pseudo-random keys. */
    fun syntheticKeys(count: Int, seed: Int): List<Long> {
        val random = java.util.Random(seed.toLong())
        val unique = generateSequence { random.nextLong() }.distinct().take(count)
        return unique.sortedWith { a, b -> java.lang.Long.compareUnsigned(a, b) }.toList()
    }
}
