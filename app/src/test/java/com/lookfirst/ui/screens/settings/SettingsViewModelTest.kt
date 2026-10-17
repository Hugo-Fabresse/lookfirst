package com.lookfirst.ui.screens.settings

import com.lookfirst.data.platform.LastAttempt
import com.lookfirst.data.platform.UpdateOutcome
import com.lookfirst.data.reputation.ListUpdateControl
import com.lookfirst.data.reputation.ListsStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
class SettingsViewModelTest {
    private class FakeControl(initial: ListsStatus) : ListUpdateControl {
        override val status = MutableStateFlow(initial)
        var refreshes = 0
        var updates = 0
        var autoChanges = mutableListOf<Boolean>()

        override fun refresh() {
            refreshes++
        }

        override fun setAutoUpdate(enabled: Boolean) {
            autoChanges += enabled
        }

        override fun updateNow() {
            updates++
        }

        override fun completeFirstLaunch(autoUpdate: Boolean) = Unit

        override fun applySchedule() = Unit
    }

    private fun status(outcome: UpdateOutcome? = null, updating: Boolean = false, version: Long? = 128) = ListsStatus(
        entries = version,
        ageDays = version?.let { 3 },
        autoUpdate = true,
        lastAttempt = outcome?.let { LastAttempt(1L, it) },
        updating = updating,
    )

    @Test
    fun mapsEveryOutcomeToOneLineAndOneNotice() {
        val expected = mapOf(
            UpdateOutcome.UPDATED to (LastAttemptLine.SUCCEEDED to null),
            UpdateOutcome.UP_TO_DATE to (LastAttemptLine.SUCCEEDED to UpdateNotice.UP_TO_DATE),
            UpdateOutcome.FAILED to (LastAttemptLine.FAILED to UpdateNotice.FAILED),
            UpdateOutcome.REJECTED to (LastAttemptLine.FAILED to UpdateNotice.REJECTED),
            UpdateOutcome.OFFLINE to (LastAttemptLine.FAILED to UpdateNotice.OFFLINE),
        )
        expected.forEach { (outcome, pair) ->
            val ui = status(outcome).toUiState()
            assertEquals(outcome.name, pair.first, ui.lastAttempt)
            assertEquals(outcome.name, pair.second, ui.notice)
        }
    }

    @Test
    fun updatingWinsOverTheLastOutcome() {
        val ui = status(UpdateOutcome.FAILED, updating = true).toUiState()
        assertEquals(UpdateNotice.UPDATING, ui.notice)
        assertEquals(true, ui.updating)
    }

    @Test
    fun noAttemptAndNoListGiveTheEmptyState() {
        val ui = status(version = null).toUiState()
        assertEquals(null, ui.listsEntries)
        assertEquals(LastAttemptLine.NONE, ui.lastAttempt)
        assertEquals(null, ui.notice)
    }

    @Test
    fun followsTheControlAndForwardsTheUserActions() {
        val control = FakeControl(status())
        val viewModel = SettingsViewModel(control)
        val states: StateFlow<SettingsUiState> = viewModel.state
        assertEquals(128L, states.value.listsEntries)
        control.status.value = status(UpdateOutcome.UPDATED, version = 129)
        ShadowLooper.idleMainLooper()
        assertEquals(129L, states.value.listsEntries)
        viewModel.onUpdateNow()
        viewModel.onAutoUpdateChanged(false)
        viewModel.onResume()
        assertEquals(1, control.updates)
        assertEquals(listOf(false), control.autoChanges)
        assertEquals(2, control.refreshes)
    }
}
