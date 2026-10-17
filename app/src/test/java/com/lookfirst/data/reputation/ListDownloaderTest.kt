package com.lookfirst.data.reputation

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.UnknownHostException

class ListDownloaderTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val server = FakeListServer()
    private val pauses = mutableListOf<Long>()
    private val root by lazy { File(folder.root, "reputation") }
    private var built = 0

    private val store by lazy {
        ReputationStore(root, null, { NOW }, directorySync = {}, acceptTestKeys = true)
    }
    private val downloader by lazy { ListDownloader(server, server.endpoint, store, pause = { pauses += it }) }

    private val day10 =
        BundleSpec(generatedAt = "2026-10-10T03:12:00Z", phishingKeys = BundleBuilder.syntheticKeys(100, 1))
    private val day12 =
        BundleSpec(generatedAt = "2026-10-12T03:12:00Z", phishingKeys = BundleBuilder.syntheticKeys(110, 2))

    private fun publish(spec: BundleSpec): BuiltBundle {
        val bundle = BundleBuilder.build(File(folder.root, "published-${built++}"), spec)
        server.publish(bundle)
        return bundle
    }

    private fun download() = runBlocking { downloader.download() }

    private fun installedVersion() = store.current()?.version?.bundle

    @Test
    fun downloadsVerifiesAndInstallsAFreshBundle() {
        publish(day10)
        val outcome = download()
        assertEquals(BundleBuilder.version(day10.generatedAt), (outcome as DownloadOutcome.Installed).version)
        assertEquals(BundleBuilder.version(day10.generatedAt), installedVersion())
        assertEquals(emptyList<String>(), File(root, "tmp").list()!!.toList())
    }

    @Test
    fun manifestComesFromLatestAndFilesFromTheTagRebuiltFromTheVerifiedVersion() {
        publish(day10)
        download()
        val tag = BundleBuilder.version(day10.generatedAt).toString()
        assertEquals(listOf("/latest/manifest.sig", "/latest/manifest.json"), server.paths().take(2))
        val files = server.paths().drop(2)
        assertEquals(3, files.size)
        assertTrue(
            files.all {
                it.startsWith("/$tag/") &&
                    it.removePrefix("/$tag/") in BundleFile.entries.map { f -> f.path }
            },
        )
    }

    @Test
    fun everyRequestIsHttpsOnTheOneCompiledHostWithOnlyTheFixedHeaders() {
        publish(day10)
        download()
        assertTrue(server.requests.isNotEmpty())
        server.requests.forEach { request ->
            assertEquals("https", request.url.scheme)
            assertEquals("codeberg.org", request.url.host)
            assertEquals(-1, request.url.port)
            assertNull(request.url.query)
            assertEquals(mapOf("User-Agent" to "LookFirst", "Accept-Encoding" to "identity"), request.headers)
            val names = request.headers.keys.map { it.lowercase() }
            listOf("if-none-match", "if-modified-since", "cookie", "authorization", "referer", "range").forEach {
                assertFalse("header $it must not be sent", it in names)
            }
        }
    }

    @Test
    fun userAgentCarriesNoAppVersion() {
        assertEquals("LookFirst", ListDownloader.USER_AGENT)
        assertFalse(ListDownloader.USER_AGENT.any(Char::isDigit))
    }

    @Test
    fun publishedBundleEqualToTheInstalledOneIsUpToDateAndFetchesNoFile() {
        publish(day10)
        download()
        server.requests.clear()
        assertEquals(DownloadOutcome.UpToDate, download())
        assertEquals(listOf("/latest/manifest.sig", "/latest/manifest.json"), server.paths())
    }

    @Test
    fun unchangedFilesAreCopiedFromTheInstalledBundleNotDownloadedAgain() {
        publish(day10)
        download()
        server.requests.clear()
        publish(day12)
        assertTrue(download() is DownloadOutcome.Installed)
        val tag = BundleBuilder.version(day12.generatedAt)
        // Only the phishing index changed (the allow-list and the Public Suffix List are byte for byte the same).
        assertEquals("/$tag/${BundleFile.PHISHING_INDEX.path}", server.paths().last())
        assertEquals(3, server.paths().size)
        assertEquals(tag, installedVersion())
    }

    @Test
    fun redirectOnTheSameHostOverHttpsIsFollowed() {
        val bundle = publish(day10)
        val moved = server.urlOf("latest", "manifest.json")
        server.putStatus(moved, 302, "/Hugo-Fabresse/attachments/abc")
        server.put("https://codeberg.org/Hugo-Fabresse/attachments/abc", bundle.manifestFile.readBytes())
        assertTrue(download() is DownloadOutcome.Installed)
    }

    @Test
    fun redirectToAnotherHostFailsTheUpdateAndKeepsTheInstalledBundle() {
        publish(day10)
        download()
        publish(day12)
        server.putStatus(server.urlOf("latest", "manifest.json"), 302, "https://evil.example/manifest.json")
        server.put("https://evil.example/manifest.json", ByteArray(10))
        server.requests.clear()
        val outcome = download()
        assertEquals(RejectReason.HTTP, (outcome as DownloadOutcome.Failed).reason)
        assertFalse(outcome.retry)
        assertTrue(server.requests.none { it.url.host != "codeberg.org" })
        assertEquals(BundleBuilder.version(day10.generatedAt), installedVersion())
    }

    @Test
    fun redirectToHttpIsRefused() {
        publish(day10)
        server.putStatus(server.urlOf("latest", "manifest.sig"), 301, "http://codeberg.org/x")
        server.put("http://codeberg.org/x", ByteArray(10))
        val outcome = download()
        assertTrue(outcome is DownloadOutcome.Failed)
        assertTrue(server.requests.none { it.url.scheme != "https" })
    }

    @Test
    fun redirectsWithUserInfoOrAnotherPortAreRefused() {
        publish(day10)
        listOf(
            "https://user@codeberg.org/x",
            "https://codeberg.org:8443/x",
            "//other.example/x",
            "https:evil",
        ).forEach {
            server.putStatus(server.urlOf("latest", "manifest.sig"), 307, it)
            server.requests.clear()
            assertTrue(it, download() is DownloadOutcome.Failed)
            assertEquals(it, 1, server.requests.size)
        }
    }

    @Test
    fun moreThanThreeRedirectsFail() {
        publish(day10)
        val first = server.urlOf("latest", "manifest.sig")
        server.putStatus(first, 302, "/r1")
        server.putStatus("https://codeberg.org/r1", 302, "/r2")
        server.putStatus("https://codeberg.org/r2", 302, "/r3")
        server.putStatus("https://codeberg.org/r3", 302, "/r4")
        server.put("https://codeberg.org/r4", ByteArray(1))
        assertTrue(download() is DownloadOutcome.Failed)
        assertEquals(4, server.requests.size)
    }

    @Test
    fun threeRedirectsAreStillFollowed() {
        val bundle = publish(day10)
        server.putStatus(server.urlOf("latest", "manifest.sig"), 302, "/r1")
        server.putStatus("https://codeberg.org/r1", 302, "/r2")
        server.putStatus("https://codeberg.org/r2", 302, "/r3")
        server.put("https://codeberg.org/r3", bundle.signatureFile.readBytes())
        assertTrue(download() is DownloadOutcome.Installed)
    }

    @Test
    fun aFileLongerThanTheManifestSaysIsRefusedWhileStreamingAndNothingIsInstalled() {
        val bundle = publish(day10)
        val tag = BundleBuilder.version(day10.generatedAt).toString()
        val real = bundle.file(BundleFile.PHISHING_INDEX).readBytes()
        server.put(server.urlOf(tag, BundleFile.PHISHING_INDEX.path), real + ByteArray(1))
        val outcome = download()
        assertEquals(RejectReason.TOO_LARGE, (outcome as DownloadOutcome.Rejected).reason)
        assertNull(installedVersion())
        assertEquals(emptyList<String>(), File(root, "tmp").list()!!.toList())
    }

    @Test
    fun anOversizedManifestIsRefusedAtTheCap() {
        publish(day10)
        server.put(server.urlOf("latest", "manifest.json"), ByteArray(64 * 1024 + 1))
        assertEquals(RejectReason.TOO_LARGE, (download() as DownloadOutcome.Rejected).reason)
    }

    @Test
    fun aTruncatedFileIsAHashProblemNotAnInstall() {
        val bundle = publish(day10)
        val tag = BundleBuilder.version(day10.generatedAt).toString()
        server.put(
            server.urlOf(tag, BundleFile.ALLOWLIST.path),
            bundle.file(BundleFile.ALLOWLIST).readBytes().copyOf(70),
        )
        assertEquals(RejectReason.HASH_MISMATCH, (download() as DownloadOutcome.Rejected).reason)
        assertNull(installedVersion())
    }

    @Test
    fun aManifestRejectedBeforeIsNotDownloadedAgain() {
        val bundle = publish(day10)
        val tag = BundleBuilder.version(day10.generatedAt).toString()
        val tampered = bundle.file(BundleFile.ALLOWLIST).readBytes().also {
            it[it.size - 1] =
                (it[it.size - 1] + 1).toByte()
        }
        server.put(server.urlOf(tag, BundleFile.ALLOWLIST.path), tampered)
        assertEquals(RejectReason.HASH_MISMATCH, (download() as DownloadOutcome.Rejected).reason)
        server.requests.clear()
        assertEquals(DownloadOutcome.AlreadyRejected, download())
        assertEquals(listOf("/latest/manifest.sig", "/latest/manifest.json"), server.paths())
    }

    @Test
    fun aNewManifestAfterARejectionIsDownloadedAgain() {
        publish(day10)
        val tag = BundleBuilder.version(day10.generatedAt).toString()
        server.put(server.urlOf(tag, BundleFile.ALLOWLIST.path), ByteArray(80))
        download()
        publish(day12)
        assertTrue(download() is DownloadOutcome.Installed)
    }

    @Test
    fun badSignatureIsRetriedOnceAfterSixtySecondsThenRefusedWithoutRetryRequest() {
        publish(day10.copy(signer = TestKeys.other))
        val outcome = download()
        assertEquals(RejectReason.BAD_SIGNATURE, (outcome as DownloadOutcome.Rejected).reason)
        assertEquals(listOf(60_000L), pauses)
        assertEquals(4, server.requests.size)
        assertNull(installedVersion())
    }

    @Test
    fun aMismatchedPairThatHealsWithinTheRetryInstalls() {
        val good = publish(day10)
        val goodSignature = good.signatureFile.readBytes()
        server.put(server.urlOf("latest", "manifest.sig"), "ecdsa-p256-sha256 0000 AAAA\n".toByteArray())
        val heal = ListDownloader(
            server,
            server.endpoint,
            store,
            pause = {
                pauses += it
                server.put(server.urlOf("latest", "manifest.sig"), goodSignature)
            },
        )
        assertTrue(runBlocking { heal.download() } is DownloadOutcome.Installed)
        assertEquals(listOf(60_000L), pauses)
    }

    @Test
    fun serverErrorsRetryClientErrorsDoNot() {
        server.putStatus(server.urlOf("latest", "manifest.sig"), 503)
        assertTrue((download() as DownloadOutcome.Failed).retry)
        server.putStatus(server.urlOf("latest", "manifest.sig"), 404)
        val notFound = download() as DownloadOutcome.Failed
        assertEquals(RejectReason.HTTP, notFound.reason)
        assertFalse(notFound.retry)
    }

    @Test
    fun networkErrorsRetryAndNoRouteIsReportedAsOffline() {
        server.failure = IOException("reset")
        val reset = download() as DownloadOutcome.Failed
        assertEquals(RejectReason.NETWORK, reset.reason)
        assertTrue(reset.retry)
        assertFalse(reset.offline)
        server.failure = UnknownHostException()
        assertTrue((download() as DownloadOutcome.Failed).offline)
    }

    @Test
    fun aConnectionCutInTheMiddleOfAFileIsANetworkFailure() {
        val bundle = publish(day10)
        val tag = BundleBuilder.version(day10.generatedAt).toString()
        val broken = object : java.io.InputStream() {
            var sent = 0

            override fun read(): Int = throw IOException("cut")

            override fun read(b: ByteArray): Int {
                if (sent > 0) throw IOException("cut")
                sent = 10
                return 10
            }
        }
        val url = server.urlOf(tag, BundleFile.PHISHING_INDEX.path)
        val original = bundle.file(BundleFile.PHISHING_INDEX).readBytes()
        server.put(url, original)
        val cutting = ListTransport { uri, headers ->
            if (uri.toString() == url) TransportResponse(200, null, broken) else server.get(uri, headers)
        }
        val outcome = runBlocking { ListDownloader(cutting, server.endpoint, store, pause = {}).download() }
        assertEquals(RejectReason.NETWORK, (outcome as DownloadOutcome.Failed).reason)
        assertTrue(outcome.retry)
        assertNull(installedVersion())
    }

    @Test
    fun aFailedDownloadLeavesTheInstalledBundleAndNoTemporaryFile() {
        publish(day10)
        download()
        publish(day12)
        server.failure = IOException("down")
        server.requests.clear()
        download()
        assertEquals(BundleBuilder.version(day10.generatedAt), installedVersion())
        assertEquals(emptyList<String>(), File(root, "tmp").list()!!.toList())
    }

    @Test
    fun theTagIsOnlyEverTheVerifiedDecimalVersion() {
        val endpoint = ListsEndpoint()
        assertNotNull(endpoint.bundleUrl("123", "a.idx"))
        listOf("", "12345678901", "../1", "1/2", "latest", "-1", "1a", "١٢").forEach {
            assertNull(it, endpoint.bundleUrl(it, "a.idx"))
        }
    }

    @Test
    fun aTooLargeFileRejectionIsRememberedByManifestHash() {
        val bundle = publish(day10)
        val tag = BundleBuilder.version(day10.generatedAt).toString()
        val real = bundle.file(BundleFile.PHISHING_INDEX).readBytes()
        server.put(server.urlOf(tag, BundleFile.PHISHING_INDEX.path), real + ByteArray(1))
        assertEquals(RejectReason.TOO_LARGE, (download() as DownloadOutcome.Rejected).reason)
        server.requests.clear()
        assertEquals(DownloadOutcome.AlreadyRejected, download())
        assertEquals(listOf("/latest/manifest.sig", "/latest/manifest.json"), server.paths())
    }

    @Test
    fun withoutTrustedKeysNothingIsRequested() {
        publish(day10)
        val release = ReputationStore(root, null, { NOW }, directorySync = {})
        val outcome = runBlocking { ListDownloader(server, server.endpoint, release, pause = {}).download() }
        assertEquals(RejectReason.BAD_SIGNATURE, (outcome as DownloadOutcome.Rejected).reason)
        assertTrue(server.requests.isEmpty())
    }
}
