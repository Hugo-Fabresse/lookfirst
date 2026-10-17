package com.lookfirst.data.reputation

import com.lookfirst.data.platform.AppPreferences
import com.lookfirst.data.platform.LastAttempt
import com.lookfirst.data.platform.ListUpdateWorker
import com.lookfirst.data.platform.UpdateOutcome
import com.lookfirst.data.platform.UpdateScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal class FakeAppPreferences(
    var auto: Boolean = true,
    var last: LastAttempt? = null,
    var firstLaunch: Boolean = true,
) : AppPreferences {
    override fun autoUpdate() = auto

    override fun setAutoUpdate(enabled: Boolean) {
        auto = enabled
    }

    override fun lastAttempt() = last

    override fun recordAttempt(attempt: LastAttempt) {
        last = attempt
    }

    override fun firstLaunchDone() = firstLaunch

    override fun setFirstLaunchDone() {
        firstLaunch = true
    }
}

internal class FakeScheduler : UpdateScheduler {
    var scheduled = 0
    var cancelled = 0

    override fun schedulePeriodic() {
        scheduled++
    }

    override fun cancelPeriodic() {
        cancelled++
    }
}

class ListUpdateRunnerTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val server = FakePublisher()
    private val preferences = FakeAppPreferences()
    private val clockValue = TEST_NOW
    private val first = testSource(id = SourceIds.CERT_PL, url = URI("https://lists.example.org/domains.txt"))
    private val second = testSource(id = OTHER_SOURCE_ID, url = URI("https://other.example.net/p.txt"))
    private val store by lazy {
        ListStore(File(folder.root, "reputation"), listOf(first, second), { clockValue }, {})
    }

    private fun runnerFor(vararg sources: ListSource) = ListUpdateRunner(
        PublisherDownloader(server, sources.toList(), store, emptySet(), publicSuffixList = { TEST_PSL }),
        preferences,
        Dispatchers.IO,
    ) { clockValue }

    private val runner by lazy { runnerFor(first) }

    @Test
    fun successInstallsAndRecordsTheAttempt() = runBlocking {
        server.put(first.url, domainLines(20), TEST_LAST_MODIFIED)
        val result = runner.runNow()
        assertEquals(UpdateOutcome.UPDATED, result.outcome)
        assertFalse(result.retry)
        assertEquals(clockValue, preferences.last?.timeMillis)
        server.putStatus(first.url, 304)
        assertEquals(UpdateOutcome.UP_TO_DATE, runner.runNow().outcome)
    }

    @Test
    fun securityRejectionIsASuccessForWorkManagerAndIsNotRetried() = runBlocking {
        server.put(first.url, "<html>nope</html>\n".repeat(20))
        val result = runner.runAutomatic()
        assertEquals(UpdateOutcome.REJECTED, result.outcome)
        assertFalse(result.retry)
        assertEquals(UpdateOutcome.REJECTED, preferences.last?.outcome)
    }

    @Test
    fun networkErrorAsksForARetry() = runBlocking {
        server.failure = IOException("reset")
        val result = runner.runAutomatic()
        assertEquals(UpdateOutcome.FAILED, result.outcome)
        assertTrue(result.retry)
    }

    @Test
    fun noRouteIsReportedAsOffline() = runBlocking {
        server.failure = UnknownHostException()
        val result = runner.runNow()
        assertEquals(UpdateOutcome.OFFLINE, result.outcome)
        assertTrue(result.retry)
        assertEquals(UpdateOutcome.OFFLINE, preferences.last?.outcome)
    }

    @Test
    fun serverErrorRetriesButNotFoundDoesNot() = runBlocking {
        server.putStatus(first.url, 503)
        assertTrue(runner.runAutomatic().retry)
        server.putStatus(first.url, 404)
        val notFound = runner.runAutomatic()
        assertEquals(UpdateOutcome.FAILED, notFound.outcome)
        assertFalse(notFound.retry)
    }

    @Test
    fun oneSourceUpdatingIsAnUpdateEvenIfAnotherFailedAndTheFailureAsksForARetry() = runBlocking {
        server.put(first.url, domainLines(20))
        server.putStatus(second.url, 503)
        val result = runnerFor(first, second).runNow()
        assertEquals(UpdateOutcome.UPDATED, result.outcome)
        assertTrue(result.retry)
    }

    @Test
    fun aRefusedSourceNextToAFailedOneIsFailed() = runBlocking {
        server.put(first.url, "<html>nope</html>\n".repeat(20))
        server.putStatus(second.url, 503)
        assertEquals(UpdateOutcome.FAILED, runnerFor(first, second).runNow().outcome)
    }

    @Test
    fun theDailyRunDoesNothingOnceAutomaticUpdateIsSwitchedOff() = runBlocking {
        server.put(first.url, domainLines(20))
        preferences.auto = false
        val result = runner.runAutomatic()
        assertNull(result.outcome)
        assertTrue(server.requests.isEmpty())
        assertNull(preferences.last)
    }

    @Test
    fun onlyOneDownloadRunsAtATime() {
        server.put(first.url, domainLines(20))
        val entered = CountDownLatch(1)
        val blocker = CountDownLatch(1)
        server.onRequest = {
            entered.countDown()
            blocker.await(5, TimeUnit.SECONDS)
        }
        runBlocking {
            val firstRun = async(Dispatchers.Default) { runner.runNow() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(runner.running.value)
            val second = runner.runNow()
            val daily = runner.runAutomatic()
            blocker.countDown()
            assertNull(second.outcome)
            assertNull(daily.outcome)
            assertEquals(UpdateOutcome.UPDATED, firstRun.await().outcome)
            assertFalse(runner.running.value)
        }
        assertEquals(1, server.requests.size)
    }

    @Test
    fun workerStopsAskingForRetriesAtTheFourthAttempt() {
        val limit = ListUpdateWorker
        assertTrue(limit.shouldRetry(retry = true, runAttemptCount = 0))
        assertTrue(limit.shouldRetry(retry = true, runAttemptCount = 2))
        assertFalse(limit.shouldRetry(retry = true, runAttemptCount = 3))
        assertFalse(limit.shouldRetry(retry = false, runAttemptCount = 0))
    }
}
