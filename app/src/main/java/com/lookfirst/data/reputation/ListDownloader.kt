package com.lookfirst.data.reputation

import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
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
private const val CHUNK_BYTES = 16 * 1024
private const val SIGNATURE_MAX_BYTES = 256L
private const val MANIFEST_RETRY_DELAY_MILLIS = 60_000L
private const val FILE_DEADLINE_NANOS = 5 * 60 * 1_000_000_000L
private const val STATUS_OK = 200
private const val STATUS_SERVER_ERROR_FIRST = 500
private const val STATUS_SERVER_ERROR_LAST = 599
private val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)

/** What one download attempt ended with. Nothing here carries a server-provided string. */
internal sealed interface DownloadOutcome {
    class Installed(val version: Long) : DownloadOutcome

    /** The published bundle is the installed one. */
    data object UpToDate : DownloadOutcome

    /** This manifest was rejected before: its files were not fetched again. */
    data object AlreadyRejected : DownloadOutcome

    /** Refused by a security or format check: retrying the same bytes cannot help (`Result.success`). */
    class Rejected(val reason: RejectReason) : DownloadOutcome

    /** Network, HTTP or storage trouble. [retry] says whether trying again later can help; [offline]: no route. */
    class Failed(val reason: RejectReason, val retry: Boolean, val offline: Boolean = false) : DownloadOutcome
}

/**
 * Fetches the manifest, then the files that changed, and hands the candidate directory to [ReputationStore]
 * (design document, section 6). Every URL is built here from [ListsEndpoint] and the verified manifest, never taken
 * from server data. Redirects are followed by hand, only to the same host over `https` (at most 3). The response size
 * is counted while streaming, `Content-Length` is never used. No cache, no validator header, no cookie, a constant
 * User-Agent without version, `Accept-Encoding: identity`.
 */
class ListDownloader internal constructor(
    private val transport: ListTransport,
    private val endpoint: ListsEndpoint,
    private val store: ReputationStore,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val nanoTime: () -> Long = System::nanoTime,
) {
    @Inject
    internal constructor(store: ReputationStore) : this(UrlConnectionTransport(), ListsEndpoint(), store)

    /** Runs one download and installation. Blocking I/O: call it from an I/O dispatcher. */
    internal suspend fun download(): DownloadOutcome {
        if (!store.keysTrusted) return DownloadOutcome.Rejected(RejectReason.BAD_SIGNATURE)
        val job = currentCoroutineContext()[Job]
        val active = { job?.isActive != false }
        var retriedOnce = false
        while (true) {
            val candidate = try {
                store.newCandidateDirectory()
            } catch (@Suppress("SwallowedException") ignored: IOException) {
                return DownloadOutcome.Failed(RejectReason.STORAGE, retry = true)
            }
            val outcome = try {
                fetchAndInstall(candidate, active)
            } finally {
                store.releaseCandidate(candidate)
            }
            // A manifest and a signature replaced one after the other can briefly disagree: one retry after 60 s.
            val mismatch = outcome is DownloadOutcome.Rejected && outcome.reason == RejectReason.BAD_SIGNATURE
            if (!mismatch || retriedOnce || !active()) return outcome
            retriedOnce = true
            pause(MANIFEST_RETRY_DELAY_MILLIS)
        }
    }

    private fun fetchAndInstall(candidate: File, active: () -> Boolean): DownloadOutcome {
        val signature = fetch(endpoint.latestUrl(BundleLimits.SIGNATURE_FILE), candidate, SIGNATURE_MAX_BYTES, active)
        if (signature != null) return signature
        val manifestFetch = fetch(
            endpoint.latestUrl(BundleLimits.MANIFEST_FILE),
            candidate,
            BundleLimits.MANIFEST_MAX_BYTES,
            active,
        )
        if (manifestFetch != null) return manifestFetch
        return when (val inspection = store.inspectManifest(candidate)) {
            is ManifestInspection.Rejected -> rejection(inspection.reason)
            ManifestInspection.UpToDate -> DownloadOutcome.UpToDate
            ManifestInspection.KnownRejected -> DownloadOutcome.AlreadyRejected
            is ManifestInspection.Proceed -> fetchFilesAndInstall(inspection, candidate, active)
        }
    }

    private fun fetchFilesAndInstall(
        inspection: ManifestInspection.Proceed,
        candidate: File,
        active: () -> Boolean,
    ): DownloadOutcome {
        val tag = inspection.manifest.bundleVersion.toString()
        for (declared in inspection.needed) {
            val kind = BundleFile.forPath(declared.path) ?: continue
            val url = endpoint.bundleUrl(tag, kind.path) ?: return DownloadOutcome.Rejected(RejectReason.BAD_MANIFEST)
            // A file longer than the manifest says (or than its own cap) is refused as soon as the cap is passed.
            val failure = fetch(url, candidate, minOf(declared.size, kind.maxSize), active, kind.path)
            if (failure != null) {
                if (failure is DownloadOutcome.Rejected) {
                    store.rejectCandidate(
                        inspection.manifestSha256,
                        failure.reason,
                    )
                }
                return failure
            }
        }
        return when (val result = store.install(candidate)) {
            is InstallResult.Installed -> DownloadOutcome.Installed(result.version.bundle)
            InstallResult.UpToDate -> DownloadOutcome.UpToDate
            is InstallResult.Rejected -> rejection(result.reason)
        }
    }

    private fun rejection(reason: RejectReason): DownloadOutcome = if (reason ==
        RejectReason.STORAGE
    ) {
        DownloadOutcome.Failed(reason, retry = true)
    } else {
        DownloadOutcome.Rejected(reason)
    }

    /** Downloads [url] into `candidate/[name]`. Null on success, otherwise the outcome that ends the attempt. */
    private fun fetch(
        url: URI,
        candidate: File,
        maxBytes: Long,
        active: () -> Boolean,
        name: String = url.path.substringAfterLast('/'),
    ): DownloadOutcome? {
        var target = url
        var redirects = 0
        while (true) {
            val response = try {
                transport.get(target, REQUEST_HEADERS)
            } catch (failure: IOException) {
                return networkFailure(failure)
            }
            val next = response.use { answer ->
                when {
                    answer.status == STATUS_OK && answer.body != null ->
                        return stream(answer.body, File(candidate, name), maxBytes, active)
                    answer.status in REDIRECT_STATUSES -> redirectTarget(target, answer.location)
                    else -> return httpFailure(answer.status)
                }
            }
            redirects++
            if (next == null ||
                redirects > MAX_REDIRECTS
            ) {
                return DownloadOutcome.Failed(RejectReason.HTTP, retry = false)
            }
            target = next
        }
    }

    /** The redirect destination if it is an allowed one (same host, https, standard port, no user info). */
    private fun redirectTarget(from: URI, location: String?): URI? {
        if (location == null || location.length > MAX_LOCATION_LENGTH) return null
        val resolved = try {
            from.resolve(location)
        } catch (@Suppress("SwallowedException") ignored: URISyntaxException) {
            return null
        } catch (@Suppress("SwallowedException") ignored: IllegalArgumentException) {
            return null
        }
        return resolved.takeIf { endpoint.isAllowedTarget(it) }
    }

    private fun stream(body: InputStream, target: File, maxBytes: Long, active: () -> Boolean): DownloadOutcome? = try {
        copyBounded(body, target, maxBytes, active)
    } catch (failure: ReadFailure) {
        networkFailure(failure.cause as? IOException ?: IOException())
    } catch (@Suppress("SwallowedException") ignored: IOException) {
        DownloadOutcome.Failed(RejectReason.STORAGE, retry = true)
    }

    /** Streams [body] into [target], counting bytes: more than [maxBytes] or a stalled download ends the attempt. */
    private fun copyBounded(body: InputStream, target: File, maxBytes: Long, active: () -> Boolean): DownloadOutcome? {
        val started = nanoTime()
        var total = 0L
        val buffer = ByteArray(CHUNK_BYTES)
        FileOutputStream(target).use { out ->
            while (true) {
                val read = readChunk(body, buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) return DownloadOutcome.Rejected(RejectReason.TOO_LARGE)
                if (!active() || nanoTime() - started > FILE_DEADLINE_NANOS) {
                    return DownloadOutcome.Failed(RejectReason.NETWORK, retry = true)
                }
                out.write(buffer, 0, read)
            }
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

    private fun networkFailure(failure: IOException): DownloadOutcome {
        val offline = failure is UnknownHostException ||
            failure is ConnectException ||
            failure is NoRouteToHostException
        return DownloadOutcome.Failed(RejectReason.NETWORK, retry = true, offline = offline)
    }

    private fun httpFailure(status: Int): DownloadOutcome =
        DownloadOutcome.Failed(RejectReason.HTTP, retry = status in STATUS_SERVER_ERROR_FIRST..STATUS_SERVER_ERROR_LAST)

    internal companion object {
        const val USER_AGENT = "LookFirst"
        private const val MAX_LOCATION_LENGTH = 2048

        /** Exactly these request headers, nothing else is added by the app. */
        val REQUEST_HEADERS: Map<String, String> = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept-Encoding" to "identity",
        )
    }
}
