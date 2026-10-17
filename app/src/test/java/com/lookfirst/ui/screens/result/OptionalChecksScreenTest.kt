package com.lookfirst.ui.screens.result

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.lookfirst.R
import com.lookfirst.data.reputation.NoReputationLists
import com.lookfirst.domain.decoder.PayloadParser
import com.lookfirst.domain.usecase.AnalyzeScan
import com.lookfirst.domain.verdict.VerdictLevel
import com.lookfirst.ui.components.TouchGuard
import com.lookfirst.ui.theme.LookFirstTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class OptionalChecksScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var traces = 0
    private var confirms = 0
    private var cancels = 0
    private var opens = 0

    private fun text(id: Int, vararg args: Any): String = RuntimeEnvironment.getApplication().getString(id, *args)

    private fun show(raw: String = "https://a.example/start", checks: OptionalChecks?, withVerdict: Boolean = true) {
        val parsed = PayloadParser.parse(raw).toUiState() as ResultUiState.Content
        val state = parsed.copy(
            verdict = if (withVerdict) AnalyzeScan(NoReputationLists())(raw, "test") else null,
            checks = checks,
        )
        composeRule.setContent {
            LookFirstTheme {
                ResultScreen(
                    state = state,
                    snackbarHostState = SnackbarHostState(),
                    onCancel = {},
                    onCopy = {},
                    onConfirmSecretCopy = {},
                    onDismissSecretCopy = {},
                    onOpen = { opens++ },
                    onConfirmOpen = {},
                    onDismissOpen = {},
                    checkActions = ChecksActions({ traces++ }, { confirms++ }, { cancels++ }),
                )
            }
        }
    }

    private val traceButton get() = composeRule.onNodeWithText(text(R.string.trace_redirects))

    private val dialogCancel
        get() = hasText(text(R.string.action_cancel)) and !hasContentDescription(text(R.string.a11y_cancel_resume))

    @Test
    fun `a https link shows the optional checks with an enabled button that asks for the warning`() {
        show(checks = OptionalChecks())
        composeRule.onNodeWithText(text(R.string.optional_checks)).performScrollTo().assertExists()
        traceButton.performScrollTo().assertIsEnabled().performClick()
        assertEquals(1, traces)
        assertEquals(0, confirms)
    }

    @Test
    fun `nothing is shown for content that has no optional checks or no verdict yet`() {
        show(raw = "tel:+33123456789", checks = null)
        composeRule.onAllNodesWithText(text(R.string.optional_checks)).assertCountEquals(0)
    }

    @Test
    fun `the section waits for the verdict`() {
        show(checks = OptionalChecks(), withVerdict = false)
        composeRule.onAllNodesWithText(text(R.string.optional_checks)).assertCountEquals(0)
    }

    @Test
    fun `an http link has the button disabled and says why`() {
        show(raw = "http://a.example/", checks = OptionalChecks(httpsOnly = true))
        traceButton.performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithText(text(R.string.trace_https_only)).assertExists()
    }

    @Test
    fun `a link on another port has the button disabled and says why`() {
        show(raw = "https://a.example:8443/", checks = OptionalChecks(unusualPort = true))
        traceButton.performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithText(text(R.string.trace_port_only)).assertExists()
    }

    @Test
    fun `offline the button is disabled with the offline note`() {
        show(checks = OptionalChecks(online = false))
        traceButton.performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithText(text(R.string.check_offline_note)).assertExists()
    }

    @Test
    fun `a failed check keeps the button and explains that the verdict is the local analysis`() {
        show(checks = OptionalChecks(trace = CheckState.FAILED))
        traceButton.performScrollTo().assertIsEnabled()
        composeRule.onNodeWithText(text(R.string.check_failed)).assertExists()
    }

    @Test
    fun `a done check is not offered again`() {
        show(checks = OptionalChecks(trace = CheckState.DONE))
        composeRule.onAllNodesWithText(text(R.string.optional_checks)).assertCountEquals(0)
        composeRule.onAllNodesWithText(text(R.string.trace_redirects)).assertCountEquals(0)
    }

    @Test
    fun `a level change is announced once and an unchanged level says nothing`() {
        show(checks = OptionalChecks(trace = CheckState.DONE, levelChangedTo = VerdictLevel.DANGEROUS))
        composeRule.onNodeWithText(text(R.string.a11y_level_changed, text(R.string.level_dangerous))).assertExists()
    }

    @Test
    fun `no announcement without a level change`() {
        show(checks = OptionalChecks(trace = CheckState.DONE))
        composeRule.onAllNodesWithText(text(R.string.a11y_level_changed, ""), substring = true).assertCountEquals(0)
    }

    @Test
    fun `the warning explains what is sent, focuses Cancel and continues only on its button`() {
        show(checks = OptionalChecks(trace = CheckState.WARNING))
        composeRule.onNodeWithText(text(R.string.warn_trace_title)).assertExists()
        composeRule.onNodeWithText(text(R.string.warn_trace_body)).assertExists()
        composeRule.onNode(dialogCancel).assertIsFocused()
        TouchGuard.track(partiallyObscured = true) {
            composeRule.onNodeWithText(text(R.string.warn_continue)).performClick()
        }
        assertEquals(0, confirms)
        composeRule.onNodeWithText(text(R.string.warn_continue)).performClick()
        assertEquals(1, confirms)
        composeRule.onNode(dialogCancel).performClick()
        assertEquals(1, cancels)
    }

    @Test
    fun `while it loads the sheet shows progress and keeps Cancel, without Continue`() {
        show(checks = OptionalChecks(trace = CheckState.RUNNING))
        composeRule.onNodeWithText(text(R.string.check_loading)).assertExists()
        composeRule.onAllNodesWithText(text(R.string.warn_continue)).assertCountEquals(0)
        composeRule.onNode(dialogCancel).assertIsFocused().performClick()
        assertEquals(1, cancels)
    }

    @Test
    fun `no sheet when idle`() {
        show(checks = OptionalChecks())
        composeRule.onAllNodesWithText(text(R.string.warn_trace_title)).assertCountEquals(0)
    }

    @Test
    fun `the link will open stays the scanned address and Open is unchanged`() {
        show(checks = OptionalChecks(trace = CheckState.DONE, levelChangedTo = VerdictLevel.CAUTION))
        composeRule.onNodeWithText(text(R.string.link_will_open)).assertExists()
        composeRule.onNodeWithText("a.example").assertExists()
        composeRule.onNodeWithContentDescription(text(R.string.open_in_browser)).assertExists()
    }
}
