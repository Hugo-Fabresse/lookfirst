package com.lookfirst.data.reputation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    fun startLoadsTheInstalledListsInTheBackgroundAndAppliesTheSchedule() {
        val first = store()
        first.install(source, parsed(*Array(10) { "site$it.com" }), null)
        val restarted = store()
        val control = CountingControl()
        val startup = ListsStartup(restarted, control, CoroutineScope(SupervisorJob() + Dispatchers.IO), Dispatchers.IO)
        assertNull(restarted.snapshot())
        startup.start()
        val deadline = System.nanoTime() + 5_000_000_000L
        while (restarted.snapshot() == null && System.nanoTime() < deadline) Thread.sleep(10)
        assertNotNull(restarted.snapshot())
        while (control.refreshes == 0 && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals(1, control.schedules)
        assertEquals(1, control.refreshes)
    }
}
