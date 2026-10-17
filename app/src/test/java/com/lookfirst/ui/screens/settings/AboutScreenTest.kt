package com.lookfirst.ui.screens.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.lookfirst.R
import com.lookfirst.ui.theme.LookFirstTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class AboutScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var backs = 0

    private fun text(id: Int): String = RuntimeEnvironment.getApplication().getString(id)

    private fun show() {
        composeRule.setContent { LookFirstTheme { AboutScreen(onBack = { backs++ }) } }
    }

    @Test
    fun explainsWhatStaysLocalWhatUsesTheNetworkAndWhoSeesWhat() {
        show()
        listOf(
            R.string.about_local_header,
            R.string.about_local_body,
            R.string.about_network_header,
            R.string.about_net_lists,
            R.string.about_net_lists_who,
            R.string.about_net_redirects,
            R.string.about_net_redirects_who,
            R.string.about_net_rdap,
            R.string.about_net_rdap_who,
            R.string.about_no_guarantee_header,
            R.string.about_no_guarantee_body,
            R.string.about_licences,
            R.string.about_licences_body,
        ).forEach { composeRule.onNodeWithText(text(it)).performScrollTo().assertExists() }
    }

    @Test
    fun saysThatScansAreNotRecordedAndThatCodebergSeesTheIpDuringTheUpdate() {
        assertTrue(text(R.string.about_local_body).contains("does not record your scans"))
        assertTrue(text(R.string.about_net_lists_who).contains("Codeberg"))
        assertTrue(text(R.string.about_net_lists_who).contains("IP"))
    }

    @Test
    fun neverSaysSafe() {
        listOf(R.string.about_local_body, R.string.about_no_guarantee_body, R.string.about_licences_body).forEach {
            assertFalse(text(it).contains("safe", ignoreCase = true))
        }
    }

    @Test
    fun backCallsBack() {
        show()
        composeRule.onNodeWithContentDescription(text(R.string.a11y_back)).performClick()
        assertEquals(1, backs)
    }

    @Test
    @Config(qualifiers = "fr")
    fun isAvailableInFrench() {
        show()
        composeRule.onNodeWithText("LookFirst n'enregistre pas vos scans", substring = true).assertExists()
        composeRule.onNodeWithText("Codeberg", substring = true).performScrollTo().assertExists()
    }
}
