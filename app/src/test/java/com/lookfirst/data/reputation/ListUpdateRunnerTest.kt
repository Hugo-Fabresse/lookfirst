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

    private val server = FakeListServer()
    private val preferences = FakeAppPreferences()
    private val clockValue = NOW
    private val store by lazy {
        ReputationStore(File(folder.root, "reputation"), null, { NOW }, directorySync = {}, acceptTestKeys = true)
    }
    private val runner by lazy {
        ListUpdateRunner(
            ListDownloader(server, server.endpoint, store, pause = {
            }),
            preferences,
            Dispatchers.IO,
        ) { clockValue }
    }

    private val day10 =
        BundleSpec(generatedAt = "2026-10-10T03:12:00Z", phishingKeys = BundleBuilder.syntheticKeys(100, 1))

    private fun publish(spec: BundleSpec) = server.publish(BundleBuilder.build(File(folder.root, "pub"), spec))

    @Test
    fun successInstallsAndRecordsTheAttempt() = runBlocking {
        publish(day10)
        val result = runner.runNow()
        assertEquals(UpdateOutcome.UPDATED, result.outcome)
        assertFalse(result.retry)
        assertEquals(LastAttempt(clockValue, UpdateOutcome.UPDATED).outcome, preferences.last?.outcome)
        assertEquals(clockValue, preferences.last?.timeMillis)
        assertEquals(UpdateOutcome.UP_TO_DATE, runner.runNow().outcome)
    }

    @Test
    fun securityRejectionIsASuccessForWorkManagerAndIsNotRetried() = runBlocking {
        publish(day10.copy(signer = TestKeys.other))
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
        server.putStatus(server.urlOf("latest", "manifest.sig"), 503)
        assertTrue(runner.runAutomatic().retry)
        server.putStatus(server.urlOf("latest", "manifest.sig"), 404)
        val notFound = runner.runAutomatic()
        assertEquals(UpdateOutcome.FAILED, notFound.outcome)
        assertFalse(notFound.retry)
    }

    @Test
    fun theDailyRunDoesNothingOnceAutomaticUpdateIsSwitchedOff() = runBlocking {
        publish(day10)
        preferences.auto = false
        val result = runner.runAutomatic()
        assertNull(result.outcome)
        assertTrue(server.requests.isEmpty())
        assertNull(preferences.last)
    }

    @Test
    fun onlyOneDownloadRunsAtATime() {
        publish(day10)
        val entered = CountDownLatch(1)
        val blocker = CountDownLatch(1)
        server.onRequest = {
            entered.countDown()
            blocker.await(5, TimeUnit.SECONDS)
        }
        runBlocking {
            val first = async(Dispatchers.Default) { runner.runNow() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(runner.running.value)
            val second = runner.runNow()
            val daily = runner.runAutomatic()
            blocker.countDown()
            assertNull(second.outcome)
            assertNull(daily.outcome)
            assertEquals(UpdateOutcome.UPDATED, first.await().outcome)
            assertFalse(runner.running.value)
        }
        // One manifest signature, one manifest and three files: nothing was fetched twice.
        assertEquals(5, server.requests.size)
    }

    @Test
    fun aRejectedBundleIsNotDownloadedAgainByTheNextRun() = runBlocking {
        val bundle = BundleBuilder.build(File(folder.root, "pub"), day10)
        server.publish(bundle)
        val tag = BundleBuilder.version(day10.generatedAt).toString()
        server.put(server.urlOf(tag, BundleFile.ALLOWLIST.path), ByteArray(80))
        assertEquals(UpdateOutcome.REJECTED, runner.runAutomatic().outcome)
        server.requests.clear()
        assertEquals(UpdateOutcome.REJECTED, runner.runAutomatic().outcome)
        assertEquals(2, server.requests.size)
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
