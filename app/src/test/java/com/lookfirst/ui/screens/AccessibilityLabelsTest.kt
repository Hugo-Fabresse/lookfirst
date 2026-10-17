package com.lookfirst.ui.screens

import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import com.lookfirst.R
import com.lookfirst.data.reputation.NoReputationLists
import com.lookfirst.domain.decoder.PayloadParser
import com.lookfirst.domain.usecase.AnalyzeScan
import com.lookfirst.ui.screens.result.ResultScreen
import com.lookfirst.ui.screens.result.ResultUiState
import com.lookfirst.ui.screens.result.toUiState
import com.lookfirst.ui.screens.scan.ScanScreen
import com.lookfirst.ui.screens.scan.ScanUiState
import com.lookfirst.ui.theme.LookFirstTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** TalkBack: a title on the scan screen, and "label in name" (the spoken name contains the visible label). */
@RunWith(RobolectricTestRunner::class)
class AccessibilityLabelsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun text(id: Int, vararg args: Any): String = RuntimeEnvironment.getApplication().getString(id, *args)

    private val isHeading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

    private fun showScan(state: ScanUiState) {
        composeRule.setContent {
            LookFirstTheme {
                ScanScreen(
                    state = state,
                    cameraContent = { _, _ -> Text("camera-slot") },
                    onRequestPermission = {},
                    onOpenAndroidSettings = {},
                    onTorchToggle = {},
                    onOpenSettings = {},
                    onCameraRetry = {},
                    onPickImage = {},
                    onBackToCamera = {},
                )
            }
        }
    }

    private fun showResult(raw: String) {
        val state = PayloadParser.parse(raw).toUiState() as ResultUiState.Content
        composeRule.setContent {
            LookFirstTheme {
                ResultScreen(
                    state = state.copy(verdict = AnalyzeScan(NoReputationLists())(raw, "test")),
                    snackbarHostState = SnackbarHostState(),
                    onCancel = {},
                    onCopy = {},
                    onConfirmSecretCopy = {},
                    onDismissSecretCopy = {},
                    onOpen = {},
                    onConfirmOpen = {},
                    onDismissOpen = {},
                )
            }
        }
    }

    private fun spokenNames(): List<String> =
        composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription))
            .fetchSemanticsNodes()
            .flatMap { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }

    private fun checkScanTitle(state: ScanUiState) {
        showScan(state)
        composeRule.onAllNodes(isHeading and hasText(text(R.string.app_name))).assertCountEquals(1)
    }

    private fun checkResultButtons() {
        showResult("https://example.com/")
        val names = spokenNames()
        for (label in listOf(text(R.string.action_cancel), text(R.string.action_copy))) {
            assertTrue("no spoken name contains \"$label\": $names", names.any { it.contains(label) })
        }
    }

    @Test
    fun scanScreenHasATitleHeadingWhileScanning() = checkScanTitle(ScanUiState.Scanning())

    @Test
    fun scanScreenHasATitleHeadingOnThePermissionScreen() = checkScanTitle(ScanUiState.PermissionRationale)

    @Test
    @Config(qualifiers = "fr")
    fun scanScreenHasATitleHeadingInFrench() = checkScanTitle(ScanUiState.Scanning())

    @Test
    fun cancelAndCopySpokenNamesContainTheirVisibleLabelInEnglish() = checkResultButtons()

    @Test
    @Config(qualifiers = "fr")
    fun cancelAndCopySpokenNamesContainTheirVisibleLabelInFrench() = checkResultButtons()
}
