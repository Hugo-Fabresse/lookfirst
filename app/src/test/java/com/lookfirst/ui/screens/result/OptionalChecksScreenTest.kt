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
import com.lookfirst.domain.rdap.RdapOutcome
import com.lookfirst.domain.rdap.RdapUnavailable
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
    private var ages = 0
    private var ageConfirms = 0
    private var ageCancels = 0

    private fun text(id: Int, vararg args: Any): String = RuntimeEnvironment.getApplication().getString(id, *args)

    private fun show(
        raw: String = "https://a.example.com/start",
        checks: OptionalChecks?,
        withVerdict: Boolean = true,
    ) {
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
                    checkActions = ChecksActions(
                        { traces++ },
                        { confirms++ },
                        { cancels++ },
                        { ages++ },
                        { ageConfirms++ },
                        { ageCancels++ },
                    ),
                )
            }
        }
    }

    private val traceButton get() = composeRule.onNodeWithText(text(R.string.trace_redirects))

    private val ageButton get() = composeRule.onNodeWithText(text(R.string.age_check))

    private val target = AgeTarget("a.example.com", "rdap.registry.example")

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
        show(raw = "http://a.example.com/", checks = OptionalChecks(httpsOnly = true))
        traceButton.performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithText(text(R.string.trace_https_only)).assertExists()
    }

    @Test
    fun `a link on another port has the button disabled and says why`() {
        show(raw = "https://a.example.com:8443/", checks = OptionalChecks(unusualPort = true))
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
        composeRule.onAllNodesWithText(text(R.string.trace_redirects)).assertCountEquals(0)
        composeRule.onNodeWithText(text(R.string.age_check)).performScrollTo().assertExists()
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
        composeRule.onNodeWithText("a.example.com").assertExists()
        composeRule.onNodeWithContentDescription(text(R.string.open_in_browser)).assertExists()
    }

    @Test
    fun `the domain age button is enabled when a registry can be asked and asks for the warning`() {
        show(checks = OptionalChecks(ageTarget = target))
        ageButton.performScrollTo().assertIsEnabled().performClick()
        assertEquals(1, ages)
        assertEquals(0, ageConfirms)
        assertEquals(0, traces)
    }

    private fun assertUnavailable(reason: RdapUnavailable, note: Int) {
        show(checks = OptionalChecks(ageUnavailable = reason))
        ageButton.performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithText(text(note)).assertExists()
    }

    @Test
    fun `without the list of registries the domain age button says so`() =
        assertUnavailable(RdapUnavailable.NO_BOOTSTRAP, R.string.age_note_no_registry_list)

    @Test
    fun `for an address that is not a domain name the domain age button says so`() =
        assertUnavailable(RdapUnavailable.NOT_A_DOMAIN, R.string.age_note_no_domain)

    @Test
    fun `for a tld without a registry the domain age button says so`() =
        assertUnavailable(RdapUnavailable.NO_SERVER, R.string.age_note_no_server)

    @Test
    fun `offline the domain age button is disabled with the offline note`() {
        show(checks = OptionalChecks(ageTarget = target, online = false))
        ageButton.performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithText(text(R.string.check_offline_note)).assertExists()
    }

    @Test
    fun `a failed domain age check keeps the button and says the verdict is the local analysis`() {
        show(checks = OptionalChecks(ageTarget = target, age = CheckState.FAILED, trace = CheckState.DONE))
        ageButton.performScrollTo().assertIsEnabled()
        composeRule.onNodeWithText(text(R.string.check_failed)).assertExists()
    }

    @Test
    fun `the domain age warning names the registry and the only thing that is sent`() {
        show(checks = OptionalChecks(ageTarget = target, age = CheckState.WARNING))
        composeRule.onNodeWithText(text(R.string.warn_rdap_title)).assertExists()
        val body = text(R.string.warn_rdap_body, "a.example.com", "rdap.registry.example")
        composeRule.onNodeWithText(body).assertExists()
        composeRule.onNode(dialogCancel).assertIsFocused()
        TouchGuard.track(partiallyObscured = true) {
            composeRule.onNodeWithText(text(R.string.warn_continue)).performClick()
        }
        assertEquals(0, ageConfirms)
        composeRule.onNodeWithText(text(R.string.warn_continue)).performClick()
        assertEquals(1, ageConfirms)
        assertEquals(0, confirms)
        composeRule.onNode(dialogCancel).performClick()
        assertEquals(1, ageCancels)
        assertEquals(0, cancels)
    }

    @Test
    fun `while the domain age request runs the sheet shows progress and keeps Cancel`() {
        show(checks = OptionalChecks(ageTarget = target, age = CheckState.RUNNING))
        composeRule.onNodeWithText(text(R.string.check_loading)).assertExists()
        composeRule.onAllNodesWithText(text(R.string.warn_continue)).assertCountEquals(0)
        composeRule.onNode(dialogCancel).assertIsFocused().performClick()
        assertEquals(1, ageCancels)
    }

    @Test
    fun `no domain age sheet when idle or done`() {
        show(checks = OptionalChecks(ageTarget = target))
        composeRule.onAllNodesWithText(text(R.string.warn_rdap_title)).assertCountEquals(0)
    }

    @Test
    fun `a registration date and age stay on screen once the check is done, without the button`() {
        val registered = RdapOutcome.Registered(registeredAtMillis = 1_791_590_400_000L, ageDays = 12)
        show(checks = OptionalChecks(ageTarget = target, age = CheckState.DONE, ageOutcome = registered))
        composeRule.onAllNodesWithText(text(R.string.age_check)).assertCountEquals(0)
        composeRule.onNodeWithText("12 days ago", substring = true).performScrollTo().assertExists()
        composeRule.onNodeWithText("2026", substring = true).assertExists()
    }

    @Test
    fun `a domain unknown to the registry is said plainly`() {
        show(checks = OptionalChecks(ageTarget = target, age = CheckState.DONE, ageOutcome = RdapOutcome.NotFound))
        composeRule.onNodeWithText(text(R.string.age_result_unknown)).performScrollTo().assertExists()
    }

    @Test
    fun `a level raised by the domain age is announced once`() {
        show(
            checks = OptionalChecks(
                age = CheckState.DONE,
                ageOutcome = RdapOutcome.Registered(1L, 3),
                levelChangedTo = VerdictLevel.SUSPICIOUS,
            ),
        )
        composeRule.onNodeWithText(text(R.string.a11y_level_changed, text(R.string.level_suspicious))).assertExists()
    }
}
