package com.lookfirst.data.reputation

import com.lookfirst.data.platform.AppPreferences
import com.lookfirst.data.platform.LastAttempt
import com.lookfirst.data.platform.UpdateOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Injection runs on the main thread: building the control must not read the preferences or the store. */
class ListUpdateControlInjectionTest {
    @get:Rule
    val folder = TemporaryFolder()

    private class CountingPreferences(private val inner: AppPreferences) : AppPreferences by inner {
        var reads = 0

        override fun autoUpdate(): Boolean = inner.autoUpdate().also { reads++ }

        override fun lastAttempt(): LastAttempt? = inner.lastAttempt().also { reads++ }

        override fun firstLaunchDone(): Boolean = inner.firstLaunchDone().also { reads++ }
    }

    private val source = testSource()
    private val attempt = LastAttempt(TEST_NOW, UpdateOutcome.UPDATED)
    private val preferences = CountingPreferences(FakeAppPreferences(auto = false, last = attempt))
    private val store by lazy { ListStore(File(folder.root, "reputation"), listOf(source), { TEST_NOW }, {}) }

    private fun control(scope: CoroutineScope) = DefaultListUpdateControl(
        store,
        preferences,
        FakeScheduler(),
        ListUpdateRunner(
            PublisherDownloader(FakePublisher(), listOf(source), store, emptySet(), publicSuffixList = { TEST_PSL }),
            preferences,
            Dispatchers.Unconfined,
        ) { TEST_NOW },
        scope,
    ) { TEST_NOW }

    @Test
    fun buildingTheControlReadsNothingAndShowsAnEmptyStatus() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))

        val control = control(scope)

        assertEquals(0, preferences.reads)
        assertNull(control.status.value.entries)
        assertNull(control.status.value.lastAttempt)
        assertTrue(control.status.value.autoUpdate)
    }

    @Test
    fun theFirstRefreshFillsTheStatusFromThePreferences() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val control = control(scope)

        control.refresh()

        assertEquals(attempt, control.status.value.lastAttempt)
        assertEquals(false, control.status.value.autoUpdate)
        advanceUntilIdle()
        assertEquals(attempt, control.status.value.lastAttempt)
    }
}
