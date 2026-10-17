package com.lookfirst.ui.screens.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.lookfirst.R
import com.lookfirst.ui.theme.LookFirstTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SettingsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var updates = 0
    private var toggles = mutableListOf<Boolean>()
    private var abouts = 0
    private var backs = 0

    private fun text(id: Int, vararg args: Any): String = RuntimeEnvironment.getApplication().getString(id, *args)

    private fun show(state: SettingsUiState, appVersion: String = "0.1.0") {
        composeRule.setContent {
            LookFirstTheme {
                SettingsScreen(
                    state = state,
                    appVersion = appVersion,
                    onBack = { backs++ },
                    onUpdateNow = { updates++ },
                    onAutoUpdateChanged = { toggles += it },
                    onOpenAbout = { abouts++ },
                )
            }
        }
    }

    private val loaded = SettingsUiState(listsVersion = 128, listsAgeDays = 3, lastAttempt = LastAttemptLine.SUCCEEDED)

    @Test
    fun showsVersionAgeLastAttemptAndAppVersion() {
        show(loaded, appVersion = "9.8.7")
        composeRule.onNodeWithText(text(R.string.settings_lists_header)).assertExists()
        composeRule.onNodeWithText("Version 128 · 3 days ago").assertExists()
        composeRule.onNodeWithText(text(R.string.settings_last_attempt_ok)).assertExists()
        composeRule.onNodeWithText(text(R.string.settings_unmetered_only)).assertExists()
        composeRule.onNodeWithText("9.8.7").assertExists()
    }

    @Test
    fun listsYoungerThanADayReadToday() {
        show(loaded.copy(listsAgeDays = 0))
        composeRule.onNodeWithText("Version 128 · today").assertExists()
    }

    @Test
    fun saysSoWhenNoListIsLoaded() {
        show(SettingsUiState())
        composeRule.onNodeWithText(text(R.string.lists_none)).assertExists()
        composeRule.onNodeWithText(text(R.string.settings_last_attempt_none)).assertExists()
    }

    @Test
    fun updateNowAndTheToggleAndAboutCallBack() {
        show(loaded)
        composeRule.onNodeWithText(text(R.string.settings_update_now)).performClick()
        assertEquals(1, updates)
        composeRule.onNodeWithText(text(R.string.settings_auto_update)).performClick()
        assertEquals(listOf(false), toggles)
        composeRule.onNodeWithText(text(R.string.settings_about_link)).performScrollTo().performClick()
        assertEquals(1, abouts)
    }

    @Test
    fun toggleOffTurnsOn() {
        show(loaded.copy(autoUpdate = false))
        composeRule.onNodeWithText(text(R.string.settings_auto_update)).performClick()
        assertEquals(listOf(true), toggles)
    }

    @Test
    fun whileUpdatingTheButtonIsDisabledAndTheProgressIsAnnounced() {
        show(loaded.copy(notice = UpdateNotice.UPDATING))
        composeRule.onNodeWithText(text(R.string.lists_updating)).assertExists()
        composeRule.onNodeWithText(text(R.string.settings_update_now)).assertIsNotEnabled()
    }

    @Test
    fun eachOutcomeHasItsOwnMessageAndTheButtonStaysAvailable() {
        val expected = mapOf(
            UpdateNotice.FAILED to R.string.lists_update_failed,
            UpdateNotice.REJECTED to R.string.lists_rejected,
            UpdateNotice.OFFLINE to R.string.lists_offline,
            UpdateNotice.UP_TO_DATE to R.string.lists_up_to_date,
        )
        var current by mutableStateOf(loaded)
        composeRule.setContent { LookFirstTheme { SettingsScreen(current, "1", {}, {}, {}, {}) } }
        expected.forEach { (notice, message) ->
            current = loaded.copy(notice = notice)
            composeRule.waitForIdle()
            composeRule.onNodeWithText(text(message)).assertExists()
            composeRule.onNodeWithText(text(R.string.settings_update_now)).assertIsEnabled()
        }
    }

    @Test
    fun rejectionMessageGivesNoTechnicalDetail() {
        show(loaded.copy(lastAttempt = LastAttemptLine.FAILED, notice = UpdateNotice.REJECTED))
        composeRule.onNodeWithText(text(R.string.lists_rejected)).assertExists()
        composeRule.onNodeWithText(text(R.string.settings_last_attempt_failed)).assertExists()
    }
}
