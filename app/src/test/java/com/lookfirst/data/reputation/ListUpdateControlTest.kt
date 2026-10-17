package com.lookfirst.data.reputation

import com.lookfirst.data.platform.UpdateOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

class ListUpdateControlTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val server = FakeListServer()
    private val preferences = FakeAppPreferences(firstLaunch = false)
    private val scheduler = FakeScheduler()
    private var now = NOW
    private val store by lazy {
        ReputationStore(File(folder.root, "reputation"), null, { NOW }, directorySync = {}, acceptTestKeys = true)
    }
    private val runner by lazy {
        ListUpdateRunner(ListDownloader(server, server.endpoint, store, pause = {}), preferences) { now }
    }
    private val control by lazy {
        DefaultListUpdateControl(
            store,
            preferences,
            scheduler,
            runner,
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        ) { now }
    }

    private val day10 =
        BundleSpec(generatedAt = "2026-10-10T03:12:00Z", phishingKeys = BundleBuilder.syntheticKeys(100, 1))

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue("condition not reached in time", condition())
    }

    private fun waitForAttempt(): UpdateOutcome {
        waitFor { preferences.last != null && !runner.running.value }
        return preferences.last!!.outcome
    }

    @Test
    fun nothingIsScheduledBeforeTheFirstLaunchScreenIsPassed() {
        control.applySchedule()
        assertEquals(0, scheduler.scheduled)
        assertEquals(1, scheduler.cancelled)
    }

    @Test
    fun firstLaunchWithTheToggleOnSchedulesTheDailyJob() {
        control.completeFirstLaunch(autoUpdate = true)
        assertTrue(preferences.firstLaunch)
        assertTrue(preferences.auto)
        assertEquals(1, scheduler.scheduled)
    }

    @Test
    fun firstLaunchWithTheToggleOffSchedulesNothing() {
        control.completeFirstLaunch(autoUpdate = false)
        assertFalse(preferences.auto)
        assertEquals(0, scheduler.scheduled)
        assertEquals(1, scheduler.cancelled)
    }

    @Test
    fun theToggleSchedulesAndCancelsTheDailyJob() {
        preferences.firstLaunch = true
        control.setAutoUpdate(false)
        assertEquals(1, scheduler.cancelled)
        control.setAutoUpdate(true)
        assertEquals(1, scheduler.scheduled)
        assertTrue(control.status.value.autoUpdate)
    }

    @Test
    fun updateNowRunsOnceAndShowsTheResult() {
        server.publish(BundleBuilder.build(File(folder.root, "pub"), day10))
        control.updateNow()
        assertEquals(UpdateOutcome.UPDATED, waitForAttempt())
        waitFor { control.status.value.version != null }
        assertEquals(BundleBuilder.version(day10.generatedAt), control.status.value.version)
        assertEquals(UpdateOutcome.UPDATED, control.status.value.lastAttempt?.outcome)
        assertFalse(control.status.value.updating)
    }

    @Test
    fun aSecondTapWithinFifteenMinutesOfASuccessDoesNotAskTheServerAgain() {
        server.publish(BundleBuilder.build(File(folder.root, "pub"), day10))
        control.updateNow()
        waitForAttempt()
        val before = server.requests.size
        now += 14 * 60_000L
        control.updateNow()
        Thread.sleep(100)
        assertEquals(before, server.requests.size)
        now += 2 * 60_000L
        control.updateNow()
        waitFor { server.requests.size > before }
    }

    @Test
    fun aFailedAttemptCanBeRetriedAtOnce() {
        server.failure = IOException("down")
        control.updateNow()
        assertEquals(UpdateOutcome.FAILED, waitForAttempt())
        val before = server.requests.size
        control.updateNow()
        waitFor { server.requests.size > before }
    }

    @Test
    fun statusIsEmptyWhileNothingIsLoaded() {
        control.refresh()
        assertNull(control.status.value.version)
        assertNull(control.status.value.ageDays)
        assertNotNull(control.status.value.autoUpdate)
    }
}
