package com.lookfirst.data.reputation

import com.lookfirst.domain.analysis.BrandList
import com.lookfirst.domain.host.PublicSuffixList
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.URI
import java.net.URISyntaxException
import java.net.UnknownHostException
import javax.inject.Inject

private const val MAX_REDIRECTS = 3
private const val MAX_LOCATION_LENGTH = 2048
private const val CHUNK_BYTES = 16 * 1024
private const val FILE_DEADLINE_NANOS = 5 * 60 * 1_000_000_000L
private const val STATUS_OK = 200
private const val STATUS_NOT_MODIFIED = 304
private const val STATUS_SERVER_ERROR_FIRST = 500
private const val STATUS_SERVER_ERROR_LAST = 599
private const val HTTPS_PORT = 443
private const val IF_MODIFIED = "If-Modified-Since"
private val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)

/** What one source ended with. Nothing here carries a server-provided string. */
internal sealed interface SourceOutcome {
    class Installed(val entries: Long) : SourceOutcome

    /** The publisher's file is the one already installed (`304`). */
    data object UpToDate : SourceOutcome

    /** Refused by a size, format or sanity check: the previous list is kept; retrying the same bytes cannot help. */
    class Rejected(val reason: ListFailure) : SourceOutcome

    /** Network, HTTP or storage trouble. [retry] says whether trying again later can help; [offline]: no route. */
    class Failed(val reason: ListFailure, val retry: Boolean, val offline: Boolean = false) : SourceOutcome
}

/** The outcomes of one run, one per source, in the order of [ListSources.ALL]. A source never blocks another. */
internal class UpdateReport(val outcomes: List<SourceOutcome>)

/**
 * Downloads each public list directly from its publisher and hands it to [ListStore] (ADR-0008). Every address is
 * a fixed [ListSource.url]; the only server-supplied address ever followed is a redirect to the same host over
 * `https`, at most 3 times. The size is counted while streaming (`Content-Length` is never used). Requests carry
 * a constant User-Agent without version, `Accept-Encoding: identity` and, when an index is installed, the
 * publisher's own `Last-Modified` date (parsed as an IMF-fixdate, bounded by the clock and re-formatted by the app,
 * never echoed) as `If-Modified-Since`, only while the last full download is under 7 days old; no cookie, no other
 * validator, no identifier. Nothing about a scan is available here: this class has no access to scanned content.
 */
internal class PublisherDownloader internal constructor(
    private val transport: ListTransport,
    private val sources: List<ListSource>,
    private val store: ListStore,
    private val officialDomains: Set<String>,
    private val publicSuffixList: () -> PublicSuffixList?,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    @Inject
    internal constructor(store: ListStore) : this(
        UrlConnectionTransport(),
        ListSources.ALL,
        store,
        BrandList.officialDomains,
        { PublicSuffixList.instance },
    )

    /** Updates every source in turn. Blocking I/O and parsing: call it from an I/O dispatcher. */
    internal suspend fun download(): UpdateReport {
        val job = currentCoroutineContext()[Job]
        val active = { job?.isActive != false }
        val outcomes = ArrayList<SourceOutcome>(sources.size)
        for (source in sources) {
            if (!active()) break
            outcomes += updateSource(source, active)
        }
        return UpdateReport(outcomes)
    }

    private fun updateSource(source: ListSource, active: () -> Boolean): SourceOutcome {
        val candidate = try {
            store.newCandidate()
        } catch (@Suppress("SwallowedException") ignored: IOException) {
            return SourceOutcome.Failed(ListFailure.STORAGE, retry = true)
        }
        try {
            val since = store.installedInfo(source)?.validatorMillis?.let(HttpDate::format)
            return when (val fetched = fetch(source, candidate, since, active)) {
                is Fetched.Failure -> fetched.outcome.also { remember(source, it) }
                Fetched.NotModified -> {
                    store.markUpToDate(source)
                    SourceOutcome.UpToDate
                }
                is Fetched.Downloaded ->
                    parseAndInstall(source, candidate, fetched.lastModified?.let(HttpDate::parse), active)
            }
        } finally {
            store.releaseCandidate(candidate)
        }
    }

    /** Network, HTTP and size failures are remembered per source like the refusals of the checks. */
    private fun remember(source: ListSource, outcome: SourceOutcome) {
        val reason = when (outcome) {
            is SourceOutcome.Failed -> outcome.reason
            is SourceOutcome.Rejected -> outcome.reason
            else -> null
        }
        if (reason != null) store.recordFailure(source, reason)
    }

    private fun parseAndInstall(
        source: ListSource,
        candidate: File,
        lastModified: Long?,
        active: () -> Boolean,
    ): SourceOutcome {
        if (!active()) return SourceOutcome.Failed(ListFailure.NETWORK, retry = true)
        return try {
            when (
                val parsed = DomainListParser.parse(
                    candidate,
                    source.maxEntries,
                    officialDomains,
                    publicSuffixList(),
                )
            ) {
                is ParseOutcome.Rejected -> {
                    store.recordFailure(source, parsed.reason)
                    SourceOutcome.Rejected(parsed.reason)
                }
                is ParseOutcome.Parsed -> when (val result = store.install(source, parsed, lastModified)) {
                    is ListInstallResult.Installed -> SourceOutcome.Installed(result.entries)
                    is ListInstallResult.Rejected ->
                        if (result.reason == ListFailure.STORAGE) {
                            SourceOutcome.Failed(result.reason, retry = true)
                        } else {
                            SourceOutcome.Rejected(result.reason)
                        }
                }
            }
        } catch (@Suppress("SwallowedException") ignored: IOException) {
            SourceOutcome.Failed(ListFailure.STORAGE, retry = true)
        }
    }

    private sealed interface Fetched {
        class Downloaded(val lastModified: String?) : Fetched

        data object NotModified : Fetched

        class Failure(val outcome: SourceOutcome) : Fetched
    }

    /** Downloads the source into [candidate], following the allowed redirects by hand. */
    private fun fetch(source: ListSource, candidate: File, since: String?, active: () -> Boolean): Fetched {
        val headers = if (since != null) {
            REQUEST_HEADERS + (IF_MODIFIED to since)
        } else {
            REQUEST_HEADERS
        }
        var target = source.url
        var redirects = 0
        while (true) {
            val response = try {
                transport.get(target, headers)
            } catch (failure: IOException) {
                return Fetched.Failure(networkFailure(failure))
            }
            val conditional = headers.containsKey(IF_MODIFIED)
            val hop = response.use { answer -> follow(source, target, answer, conditional, candidate, active) }
            if (hop.done != null) return hop.done
            redirects++
            if (hop.next == null || redirects > MAX_REDIRECTS) {
                return Fetched.Failure(SourceOutcome.Failed(ListFailure.HTTP, retry = false))
            }
            target = hop.next
        }
    }

    /** Either the end of the download ([done]) or the redirect to follow ([next]); neither means "give up". */
    private class Hop(val done: Fetched?, val next: URI?)

    private fun follow(
        source: ListSource,
        from: URI,
        answer: TransportResponse,
        conditional: Boolean,
        candidate: File,
        active: () -> Boolean,
    ): Hop = when {
        answer.status == STATUS_OK && answer.body != null ->
            Hop(stream(answer, candidate, source.maxDownloadBytes, active), null)
        answer.status == STATUS_NOT_MODIFIED && conditional -> Hop(Fetched.NotModified, null)
        answer.status in REDIRECT_STATUSES -> Hop(null, redirectTarget(source, from, answer.location))
        else -> Hop(Fetched.Failure(httpFailure(answer.status)), null)
    }

    /** The redirect destination if it is an allowed one (same host, https, standard port, no user info). */
    private fun redirectTarget(source: ListSource, from: URI, location: String?): URI? {
        if (location == null || location.length > MAX_LOCATION_LENGTH) return null
        val resolved = try {
            from.resolve(location)
        } catch (@Suppress("SwallowedException") ignored: URISyntaxException) {
            return null
        } catch (@Suppress("SwallowedException") ignored: IllegalArgumentException) {
            return null
        }
        val allowed = resolved.scheme.equals("https", ignoreCase = true) &&
            resolved.host.equals(source.host, ignoreCase = true) &&
            resolved.userInfo == null &&
            (resolved.port == -1 || resolved.port == HTTPS_PORT)
        return resolved.takeIf { allowed }
    }

    private fun stream(response: TransportResponse, target: File, maxBytes: Long, active: () -> Boolean): Fetched {
        val body = response.body ?: return Fetched.Failure(SourceOutcome.Failed(ListFailure.HTTP, retry = false))
        val failure = try {
            copyBounded(body, target, maxBytes, active)
        } catch (failure: ReadFailure) {
            networkFailure(failure.cause as? IOException ?: IOException())
        } catch (@Suppress("SwallowedException") ignored: IOException) {
            SourceOutcome.Failed(ListFailure.STORAGE, retry = true)
        }
        return if (failure != null) Fetched.Failure(failure) else Fetched.Downloaded(response.lastModified)
    }

    /** Streams [body] into [target], counting bytes: more than [maxBytes] or a stalled download ends the attempt. */
    private fun copyBounded(body: InputStream, target: File, maxBytes: Long, active: () -> Boolean): SourceOutcome? {
        val started = nanoTime()
        var total = 0L
        val buffer = ByteArray(CHUNK_BYTES)
        FileOutputStream(target).use { out ->
            while (true) {
                val read = readChunk(body, buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) return SourceOutcome.Rejected(ListFailure.TOO_LARGE)
                if (!active() || nanoTime() - started > FILE_DEADLINE_NANOS) {
                    return SourceOutcome.Failed(ListFailure.NETWORK, retry = true)
                }
                out.write(buffer, 0, read)
            }
            out.fd.sync()
        }
        return null
    }

    /** Separates a network read error from a disk write error: only the first is wrapped. */
    private fun readChunk(body: InputStream, buffer: ByteArray): Int = try {
        body.read(buffer)
    } catch (failure: IOException) {
        throw ReadFailure(failure)
    }

    private class ReadFailure(cause: IOException) : RuntimeException(cause)

    private fun networkFailure(failure: IOException): SourceOutcome {
        val offline = failure is UnknownHostException ||
            failure is ConnectException ||
            failure is NoRouteToHostException
        return SourceOutcome.Failed(ListFailure.NETWORK, retry = true, offline = offline)
    }

    private fun httpFailure(status: Int): SourceOutcome = SourceOutcome.Failed(
        ListFailure.HTTP,
        retry = status in STATUS_SERVER_ERROR_FIRST..STATUS_SERVER_ERROR_LAST,
    )

    internal companion object {
        const val USER_AGENT = "LookFirst"

        /** Exactly these request headers, plus `If-Modified-Since` when an index is installed. */
        val REQUEST_HEADERS: Map<String, String> = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept-Encoding" to "identity",
        )
    }
}
