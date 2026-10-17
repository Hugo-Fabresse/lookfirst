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
import java.net.URI

class ListUpdateControlTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val server = FakePublisher()
    private val source = testSource(url = URI("https://lists.example.org/domains.txt"))
    private val preferences = FakeAppPreferences(firstLaunch = false)
    private val scheduler = FakeScheduler()
    private var now = TEST_NOW
    private val store by lazy { ListStore(File(folder.root, "reputation"), listOf(source), { TEST_NOW }, {}) }
    private val runner by lazy {
        ListUpdateRunner(
            PublisherDownloader(server, listOf(source), store, emptySet(), publicSuffixList = { TEST_PSL }),
            preferences,
            Dispatchers.IO,
        ) { now }
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

    private fun publish() = server.put(source.url, domainLines(100))

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
        publish()
        control.completeFirstLaunch(autoUpdate = true)
        assertTrue(preferences.firstLaunch)
        assertTrue(preferences.auto)
        assertEquals(1, scheduler.scheduled)
        assertEquals(UpdateOutcome.UPDATED, waitForAttempt())
        assertEquals(1, server.requests.size)
    }

    @Test
    fun firstLaunchWithTheToggleOffSchedulesNothing() {
        control.completeFirstLaunch(autoUpdate = false)
        assertFalse(preferences.auto)
        assertEquals(0, scheduler.scheduled)
        assertTrue(server.requests.isEmpty())
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
        publish()
        control.updateNow()
        assertEquals(UpdateOutcome.UPDATED, waitForAttempt())
        waitFor { control.status.value.entries != null }
        assertEquals(100L, control.status.value.entries)
        assertEquals(UpdateOutcome.UPDATED, control.status.value.lastAttempt?.outcome)
        assertFalse(control.status.value.updating)
    }

    @Test
    fun aSecondTapWithinFifteenMinutesOfASuccessDoesNotAskTheServerAgain() {
        publish()
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
        assertNull(control.status.value.entries)
        assertNull(control.status.value.ageDays)
        assertNotNull(control.status.value.autoUpdate)
    }
}
