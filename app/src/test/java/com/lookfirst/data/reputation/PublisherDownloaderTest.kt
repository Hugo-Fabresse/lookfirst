package com.lookfirst.data.reputation

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException

class PublisherDownloaderTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val server = FakePublisher()
    private var now = TEST_NOW
    private val first = testSource(id = SourceIds.CERT_PL, url = URI("https://lists.example.org/domains.txt"))
    private val second = testSource(
        id = OTHER_SOURCE_ID,
        url = URI("https://other.example.net/list/phish.txt"),
    )
    private val store by lazy { ListStore(File(folder.root, "reputation"), listOf(first, second), { now }, {}) }

    private fun downloader(vararg sources: ListSource) = PublisherDownloader(
        server,
        sources.toList().ifEmpty { listOf(first) },
        store,
        officialDomains = setOf("paypal.com"),
        publicSuffixList = { TEST_PSL },
    )

    private fun run(vararg sources: ListSource) = runBlocking { downloader(*sources).download() }

    private fun only(report: UpdateReport) = report.outcomes.single()

    private fun entries() = store.snapshot()?.entries

    @Test
    fun aListIsDownloadedParsedAndInstalled() {
        server.put(first.url, domainLines(20), lastModified = TEST_LAST_MODIFIED)
        val outcome = only(run())
        assertEquals(20L, (outcome as SourceOutcome.Installed).entries)
        assertEquals(20L, entries())
        assertEquals(TEST_LAST_MODIFIED_MILLIS, store.installedInfo(first)!!.validatorMillis)
    }

    @Test
    fun theRequestIsTheFixedUrlWithTheMinimalHeadersOnly() {
        server.put(first.url, domainLines(5))
        run()
        val request = server.requests.single()
        assertEquals(first.url, request.url)
        assertNull(request.url.query)
        assertEquals(mapOf("User-Agent" to "LookFirst", "Accept-Encoding" to "identity"), request.headers)
    }

    @Test
    fun noRequestEverCarriesAnythingButTheCompiledSourceUrls() {
        server.put(first.url, domainLines(5))
        server.put(second.url, domainLines(5, "other"))
        run(first, second)
        val urls = server.requests.map { it.url }.toSet()
        assertEquals(setOf(first.url, second.url), urls)
        assertTrue(server.requests.all { it.url.query == null && it.url.fragment == null && it.url.userInfo == null })
        assertTrue(server.requests.all { "Cookie" !in it.headers && "Authorization" !in it.headers })
    }

    @Test
    fun theCompiledSourcesAreHttpsWithoutQueryOrUserInfo() {
        ListSources.ALL.forEach {
            assertEquals("https", it.url.scheme)
            assertNull(it.url.query)
            assertNull(it.url.userInfo)
            assertTrue(it.maxDownloadBytes <= 10L * 1024 * 1024)
        }
        assertTrue(ListSources.ALL.sumOf { it.maxDownloadBytes } <= 10L * 1024 * 1024)
        assertEquals(ListSources.ALL.size, ListSources.ALL.map { it.id }.toSet().size)
    }

    @Test
    fun theSecondRunSendsTheValidatorAndA304KeepsTheListAndRefreshesItsAge() {
        server.put(first.url, domainLines(20), lastModified = TEST_LAST_MODIFIED)
        run()
        now += 2 * TEST_DAY
        server.putStatus(first.url, 304)
        val outcome = only(run())
        assertEquals(SourceOutcome.UpToDate, outcome)
        assertEquals(TEST_LAST_MODIFIED, server.requests.last().headers["If-Modified-Since"])
        assertEquals(0, store.snapshot()!!.ageDays)
        assertEquals(20L, entries())
    }

    @Test
    fun aLastModifiedThatIsNotAStrictImfFixdateInThePastIsNeverSentBack() {
        listOf(
            "user=42;track",
            "Saturday, 17-Oct-26 09:20:06 GMT",
            "Sun, 17 Oct 2026 09:20:06 GMT",
            "Sat, 30 Feb 2026 09:20:06 GMT",
            "Sat, 17 Oct 2026 09:20:06 GMT; id=42",
            "Sun, 18 Oct 2026 09:20:06 GMT",
            "x".repeat(500),
        ).forEach { value ->
            server.requests.clear()
            server.put(first.url, domainLines(20), lastModified = value)
            run()
            server.put(first.url, domainLines(20))
            run()
            assertFalse(value, server.requests.last().headers.containsKey("If-Modified-Since"))
        }
    }

    @Test
    fun aValidDateIsReformattedByTheAppNotEchoed() {
        server.put(first.url, domainLines(20), lastModified = TEST_LAST_MODIFIED)
        run()
        run()
        assertEquals(TEST_LAST_MODIFIED, server.requests.last().headers["If-Modified-Since"])
        assertEquals(HttpDate.format(TEST_LAST_MODIFIED_MILLIS), server.requests.last().headers["If-Modified-Since"])
    }

    @Test
    fun aServerThatAnswers304ForeverIsAskedForTheWholeFileAfterSevenDays() {
        server.put(first.url, domainLines(20), lastModified = TEST_LAST_MODIFIED)
        run()
        server.putStatus(first.url, 304)
        now += 6 * TEST_DAY
        assertEquals(SourceOutcome.UpToDate, only(run()))
        assertTrue(server.requests.last().headers.containsKey("If-Modified-Since"))
        now += 2 * TEST_DAY
        val outcome = only(run())
        assertFalse(server.requests.last().headers.containsKey("If-Modified-Since"))
        assertTrue(outcome is SourceOutcome.Failed)
        server.put(first.url, domainLines(20), lastModified = TEST_LAST_MODIFIED)
        assertTrue(only(run()) is SourceOutcome.Installed)
    }

    @Test
    fun networkHttpAndSizeFailuresAreRememberedPerSource() {
        val root = File(folder.root, "reputation")
        server.failure = SocketTimeoutException()
        run()
        assertEquals(ListFailure.NETWORK, failureOf(root, first.id))
        server.failure = null
        server.putStatus(first.url, 503)
        run()
        assertEquals(ListFailure.HTTP, failureOf(root, first.id))
        server.putBytes(first.url, ByteArray((first.maxDownloadBytes + 10).toInt()) { 'a'.code.toByte() })
        run()
        assertEquals(ListFailure.TOO_LARGE, failureOf(root, first.id))
        assertNull(failureOf(root, second.id))
    }

    @Test
    fun withoutThePublicSuffixListNothingIsInstalled() {
        val noPsl = PublisherDownloader(server, listOf(first), store, emptySet(), publicSuffixList = { null })
        server.put(first.url, domainLines(20))
        val outcome = runBlocking { noPsl.download() }.outcomes.single() as SourceOutcome.Rejected
        assertEquals(ListFailure.BAD_FORMAT, outcome.reason)
        assertNull(entries())
    }

    @Test
    fun a304WithoutAConditionalRequestIsAnHttpFailure() {
        server.putStatus(first.url, 304)
        val outcome = only(run()) as SourceOutcome.Failed
        assertEquals(ListFailure.HTTP, outcome.reason)
    }

    @Test
    fun aRedirectToTheSameHostIsFollowed() {
        server.putStatus(first.url, 301, "/moved/domains.txt")
        server.put(URI("https://lists.example.org/moved/domains.txt"), domainLines(10))
        assertTrue(only(run()) is SourceOutcome.Installed)
        assertEquals(2, server.requests.size)
    }

    @Test
    fun redirectsToAnotherHostAnotherSchemeAnotherPortOrWithUserInfoAreRefused() {
        listOf(
            "https://evil.example.com/domains.txt",
            "http://lists.example.org/domains.txt",
            "https://lists.example.org:8443/domains.txt",
            "https://user@lists.example.org/domains.txt",
            "//evil.example.com/x",
            "file:///etc/passwd",
            "javascript:alert(1)",
        ).forEach { target ->
            server.requests.clear()
            server.putStatus(first.url, 302, target)
            val outcome = only(run())
            assertTrue(target, outcome is SourceOutcome.Failed)
            assertEquals(target, 1, server.requests.size)
        }
        assertNull(entries())
    }

    @Test
    fun atMostThreeRedirectsAreFollowed() {
        val hops = (0..5).map { URI("https://lists.example.org/hop$it") }
        server.putStatus(first.url, 302, "/hop0")
        for (i in 0 until 5) server.putStatus(hops[i], 302, "/hop${i + 1}")
        server.put(hops[5], domainLines(10))
        val outcome = only(run()) as SourceOutcome.Failed
        assertEquals(ListFailure.HTTP, outcome.reason)
        assertEquals(4, server.requests.size)
    }

    @Test
    fun aBodyOverTheCapIsRefusedWhileStreamingWhateverTheContentLength() {
        val huge = ByteArray((first.maxDownloadBytes + 10).toInt()) { 'a'.code.toByte() }
        server.putBytes(first.url, huge)
        val outcome = only(run()) as SourceOutcome.Rejected
        assertEquals(ListFailure.TOO_LARGE, outcome.reason)
        assertNull(entries())
        assertEquals(emptyList<String>(), File(folder.root, "reputation/tmp").list().orEmpty().toList())
    }

    @Test
    fun anEndlessBodyIsStoppedAtTheCap() {
        var produced = 0L
        server.putStream(first.url) {
            object : InputStream() {
                override fun read(): Int = 'a'.code
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    produced += len
                    b.fill('a'.code.toByte(), off, off + len)
                    return len
                }
            }
        }
        val outcome = only(run()) as SourceOutcome.Rejected
        assertEquals(ListFailure.TOO_LARGE, outcome.reason)
        assertTrue(produced < first.maxDownloadBytes + 64 * 1024)
    }

    @Test
    fun networkErrorsAskForARetryAndNoRouteIsOffline() {
        server.failure = SocketTimeoutException()
        val timeout = only(run()) as SourceOutcome.Failed
        assertTrue(timeout.retry)
        assertFalse(timeout.offline)
        server.failure = UnknownHostException()
        assertTrue((only(run()) as SourceOutcome.Failed).offline)
        server.failure = ConnectException()
        assertTrue((only(run()) as SourceOutcome.Failed).offline)
    }

    @Test
    fun serverErrorsRetryAndClientErrorsDoNot() {
        server.putStatus(first.url, 503)
        assertTrue((only(run()) as SourceOutcome.Failed).retry)
        server.putStatus(first.url, 404)
        val notFound = only(run()) as SourceOutcome.Failed
        assertFalse(notFound.retry)
        assertEquals(ListFailure.HTTP, notFound.reason)
    }

    @Test
    fun aConnectionCutMidBodyKeepsTheOldListAndLeavesNoTemporaryFile() {
        server.put(first.url, domainLines(20))
        run()
        server.putStream(first.url) {
            object : InputStream() {
                private val inner = ByteArrayInputStream(domainLines(20, "new").toByteArray())
                private var calls = 0
                override fun read(): Int = inner.read()
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (calls++ > 0) throw IOException("reset")
                    return inner.read(b, off, 50)
                }
            }
        }
        val outcome = only(run()) as SourceOutcome.Failed
        assertEquals(ListFailure.NETWORK, outcome.reason)
        assertEquals(20L, entries())
        assertTrue(store.current()!!.sources.single().index.contains(Lfix.keyOf('d', "site3.com")))
        assertEquals(emptyList<String>(), File(folder.root, "reputation/tmp").list().orEmpty().toList())
    }

    @Test
    fun aGarbageFileIsRefusedAndTheOldListStays() {
        server.put(first.url, domainLines(20))
        run()
        server.putBytes(first.url, ByteArray(10_000) { (it * 31).toByte() })
        val outcome = only(run()) as SourceOutcome.Rejected
        assertEquals(ListFailure.BAD_FORMAT, outcome.reason)
        assertEquals(20L, entries())
        assertEquals(ListFailure.BAD_FORMAT, failureOf(File(folder.root, "reputation"), first.id))
    }

    @Test
    fun anHtmlErrorPageServedAs200IsRefused() {
        server.put(first.url, "<html><body>Service unavailable</body></html>\n".repeat(10))
        val outcome = only(run()) as SourceOutcome.Rejected
        assertEquals(ListFailure.BAD_FORMAT, outcome.reason)
    }

    @Test
    fun aListThatShrinksTooMuchIsRefusedAndRemembered() {
        server.put(first.url, domainLines(100))
        run()
        server.put(first.url, domainLines(40))
        val outcome = only(run()) as SourceOutcome.Rejected
        assertEquals(ListFailure.VARIATION, outcome.reason)
        assertEquals(100L, entries())
        assertEquals(ListFailure.VARIATION, failureOf(File(folder.root, "reputation"), first.id))
    }

    @Test
    fun aListThatTargetsOfficialDomainsIsRefused() {
        val names = (1..150).joinToString("\n") { "brand$it.com" }
        val officials = (1..150).map { "brand$it.com" }.toSet()
        val local = PublisherDownloader(server, listOf(first), store, officials, publicSuffixList = { TEST_PSL })
        server.put(first.url, names + "\n" + domainLines(10))
        val outcome = runBlocking { local.download() }.outcomes.single() as SourceOutcome.Rejected
        assertEquals(ListFailure.POISONED, outcome.reason)
    }

    @Test
    fun aFailingSourceDoesNotStopTheOthers() {
        server.putStatus(first.url, 500)
        server.put(second.url, domainLines(15, "other"))
        val report = run(first, second)
        assertTrue(report.outcomes[0] is SourceOutcome.Failed)
        assertTrue(report.outcomes[1] is SourceOutcome.Installed)
        assertEquals(15L, entries())
    }

    @Test
    fun aRefusedSourceKeepsItsOldListWhileTheOtherUpdates() {
        server.put(first.url, domainLines(100))
        server.put(second.url, domainLines(100, "other"))
        run(first, second)
        server.put(first.url, domainLines(3))
        server.put(second.url, domainLines(105, "other"))
        val report = run(first, second)
        assertTrue(report.outcomes[0] is SourceOutcome.Rejected)
        assertTrue(report.outcomes[1] is SourceOutcome.Installed)
        assertEquals(205L, entries())
    }

    @Test
    fun anUnknownSourceIsNeverFetchedAndEmptySourceListIsAnEmptyReport() {
        val none = PublisherDownloader(server, emptyList(), store, emptySet(), publicSuffixList = { TEST_PSL })
        assertTrue(runBlocking { none.download() }.outcomes.isEmpty())
        assertTrue(server.requests.isEmpty())
    }
}
