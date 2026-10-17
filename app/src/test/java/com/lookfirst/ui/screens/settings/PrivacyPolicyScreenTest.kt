package com.lookfirst.ui.screens.settings

import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
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
import java.io.File
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class PrivacyPolicyScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val headers = listOf(
        R.string.privacy_never_header,
        R.string.privacy_where_header,
        R.string.privacy_who_header,
        R.string.privacy_clipboard_header,
        R.string.privacy_permissions_header,
        R.string.privacy_local_header,
        R.string.privacy_contact_header,
    )
    private val texts = listOf(
        R.string.privacy_updated,
        R.string.privacy_intro,
        R.string.privacy_never_body,
        R.string.privacy_where_body,
        R.string.privacy_who_lists_title,
        R.string.privacy_who_lists_body,
        R.string.privacy_who_redirects_title,
        R.string.privacy_who_redirects_body,
        R.string.privacy_who_rdap_title,
        R.string.privacy_who_rdap_body,
        R.string.privacy_clipboard_body,
        R.string.privacy_permissions_body,
        R.string.privacy_local_body,
        R.string.privacy_contact_body,
    )

    private fun text(id: Int): String = RuntimeEnvironment.getApplication().getString(id)

    private fun showFlow() {
        composeRule.setContent {
            LookFirstTheme {
                var onPolicy by remember { mutableStateOf(false) }
                if (onPolicy) {
                    PrivacyPolicyScreen(onBack = { onPolicy = false })
                } else {
                    AboutScreen(onBack = {}, onOpenPrivacyPolicy = { onPolicy = true })
                }
            }
        }
    }

    private fun showPolicy() {
        composeRule.setContent { LookFirstTheme { PrivacyPolicyScreen(onBack = {}) } }
    }

    private fun assertEverythingShown() {
        (headers + texts).forEach { composeRule.onNodeWithText(text(it)).performScrollTo().assertExists() }
    }

    @Test
    fun opensFromAboutAndBackReturnsToAbout() {
        showFlow()
        composeRule.onNodeWithText(text(R.string.privacy_link)).performScrollTo().performClick()
        composeRule.onNodeWithText(text(R.string.privacy_updated)).assertExists()
        composeRule.onNodeWithContentDescription(text(R.string.a11y_back)).performClick()
        composeRule.onNodeWithText(text(R.string.about_licences_body)).performScrollTo().assertExists()
    }

    @Test
    fun showsEverySectionInEnglish() {
        showPolicy()
        assertEverythingShown()
        assertTrue(text(R.string.privacy_who_lists_body).contains("CERT Polska"))
        assertEquals("Privacy policy", text(R.string.privacy_title))
    }

    @Test
    @Config(qualifiers = "fr")
    fun showsEverySectionInFrench() {
        showPolicy()
        assertEverythingShown()
        assertTrue(text(R.string.privacy_who_lists_body).contains("CERT Polska"))
        assertEquals("Politique de confidentialité", text(R.string.privacy_title))
    }

    @Test
    fun sectionTitlesAreSemanticHeadings() {
        showPolicy()
        val heading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
        // The top bar title plus the seven section titles.
        composeRule.onAllNodes(heading).assertCountEquals(headers.size + 1)
    }

    @Test
    fun staysReadableAtTheLargestFontScale() {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                LookFirstTheme { PrivacyPolicyScreen(onBack = {}) }
            }
        }
        assertEverythingShown()
    }

    @Test
    fun neverSaysSafeInEnglish() {
        (headers + texts + R.string.privacy_link).forEach {
            assertFalse(text(it).contains("safe", ignoreCase = true))
        }
    }

    @Test
    @Config(qualifiers = "fr")
    fun neverSaysSafeInFrench() {
        (headers + texts + R.string.privacy_link).forEach {
            assertFalse(text(it).contains("sûr", ignoreCase = true))
            assertFalse(text(it).contains("safe", ignoreCase = true))
        }
    }

    @Test
    fun theDocsCopyHoldsTheSameTextInBothLanguages() {
        val doc = File("../docs/privacy.md").readText()
        val ids = headers + texts
        assertTrue(ids.all { text(it) in doc })
        val app = RuntimeEnvironment.getApplication()
        val french = Configuration(app.resources.configuration).apply { setLocale(Locale.FRENCH) }
        val frenchContext = app.createConfigurationContext(french)
        assertTrue(ids.all { frenchContext.getString(it) in doc })
    }
}
