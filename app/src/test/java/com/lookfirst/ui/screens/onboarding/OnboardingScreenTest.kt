package com.lookfirst.ui.screens.onboarding

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.lookfirst.R
import com.lookfirst.ui.theme.LookFirstTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class OnboardingScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val choices = mutableListOf<Boolean>()

    private fun text(id: Int): String = RuntimeEnvironment.getApplication().getString(id)

    private fun show() {
        composeRule.setContent { LookFirstTheme { OnboardingScreen(onStart = { choices += it }) } }
    }

    @Test
    fun showsTheThreeFactsAndAsksNothingBeforeTheButton() {
        show()
        listOf(
            R.string.onboarding_nothing_opens,
            R.string.onboarding_privacy,
            R.string.onboarding_lists,
            R.string.onboarding_no_guarantee,
        ).forEach { composeRule.onNodeWithText(text(it)).assertExists() }
        assertEquals(emptyList<Boolean>(), choices)
    }

    @Test
    fun automaticUpdateIsOnByDefault() {
        show()
        composeRule.onNodeWithText(text(R.string.onboarding_start)).performClick()
        assertEquals(listOf(true), choices)
    }

    @Test
    fun theToggleCanBeSwitchedOffBeforeStarting() {
        show()
        composeRule.onNodeWithText(text(R.string.onboarding_auto_update)).performClick()
        composeRule.onNodeWithText(text(R.string.onboarding_start)).performClick()
        assertEquals(listOf(false), choices)
    }
}
