package com.lookfirst.data.reputation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ListsStartupTest {
    @get:Rule
    val folder = TemporaryFolder()

    private class CountingControl : ListUpdateControl {
        override val status: StateFlow<ListsStatus> = MutableStateFlow(ListsStatus(null, null, true, null, false))
        var refreshes = 0
        var schedules = 0

        override fun refresh() {
            refreshes++
        }

        override fun setAutoUpdate(enabled: Boolean) = Unit

        override fun updateNow() = Unit

        override fun completeFirstLaunch(autoUpdate: Boolean) = Unit

        override fun applySchedule() {
            schedules++
        }
    }

    private val root by lazy { File(folder.root, "reputation") }

    private val source = testSource()

    private fun store() = ListStore(root, listOf(source), { TEST_NOW }, {})

    @Test
    fun startLoadsTheInstalledListsInTheBackgroundAndAppliesTheSchedule() = runTest {
        val first = store()
        first.install(source, parsed(*Array(10) { "site$it.com" }), null)
        val restarted = store()
        val control = CountingControl()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val startup = ListsStartup(restarted, control, CoroutineScope(SupervisorJob() + dispatcher), dispatcher)
        assertNull(restarted.snapshot())
        startup.start()
        assertNull(restarted.snapshot())
        advanceUntilIdle()
        assertNotNull(restarted.snapshot())
        assertEquals(1, control.schedules)
        assertEquals(1, control.refreshes)
    }
}
