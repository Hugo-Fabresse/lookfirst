package com.lookfirst.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lookfirst.data.platform.UpdateOutcome
import com.lookfirst.data.reputation.ListUpdateControl
import com.lookfirst.data.reputation.ListsStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** How the last update attempt is summed up in one line (ux.md 5.13). */
enum class LastAttemptLine { NONE, SUCCEEDED, FAILED }

/** The one message under the lists block: an update in progress, or how the last attempt ended. */
enum class UpdateNotice { UPDATING, FAILED, REJECTED, OFFLINE, UP_TO_DATE }

/** [listsEntries] and [listsAgeDays] are null while no list is loaded. */
data class SettingsUiState(
    val listsEntries: Long? = null,
    val listsAgeDays: Int? = null,
    val autoUpdate: Boolean = true,
    val lastAttempt: LastAttemptLine = LastAttemptLine.NONE,
    val notice: UpdateNotice? = null,
) {
    val updating: Boolean get() = notice == UpdateNotice.UPDATING
}

internal fun ListsStatus.toUiState(): SettingsUiState {
    val outcome = lastAttempt?.outcome
    val notice = when {
        updating -> UpdateNotice.UPDATING
        outcome == UpdateOutcome.FAILED -> UpdateNotice.FAILED
        outcome == UpdateOutcome.REJECTED -> UpdateNotice.REJECTED
        outcome == UpdateOutcome.OFFLINE -> UpdateNotice.OFFLINE
        outcome == UpdateOutcome.UP_TO_DATE -> UpdateNotice.UP_TO_DATE
        else -> null
    }
    val line = when (outcome) {
        null -> LastAttemptLine.NONE
        UpdateOutcome.UPDATED, UpdateOutcome.UP_TO_DATE -> LastAttemptLine.SUCCEEDED
        UpdateOutcome.FAILED, UpdateOutcome.REJECTED, UpdateOutcome.OFFLINE -> LastAttemptLine.FAILED
    }
    return SettingsUiState(entries, ageDays, autoUpdate, line, notice)
}

@HiltViewModel
class SettingsViewModel @Inject constructor(private val control: ListUpdateControl) : ViewModel() {
    private val mutableState = MutableStateFlow(control.status.value.toUiState())
    val state: StateFlow<SettingsUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch { control.status.collect { mutableState.value = it.toUiState() } }
        control.refresh()
    }

    fun onResume() = control.refresh()

    fun onUpdateNow() = control.updateNow()

    fun onAutoUpdateChanged(enabled: Boolean) = control.setAutoUpdate(enabled)
}
