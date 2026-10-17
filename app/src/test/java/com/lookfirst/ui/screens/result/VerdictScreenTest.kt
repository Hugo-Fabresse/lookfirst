package com.lookfirst.ui.screens.result

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.lookfirst.R
import com.lookfirst.data.reputation.NoReputationLists
import com.lookfirst.domain.decoder.PayloadParser
import com.lookfirst.domain.model.Signal
import com.lookfirst.domain.model.SignalCode
import com.lookfirst.domain.model.Untrusted
import com.lookfirst.domain.usecase.AnalyzeScan
import com.lookfirst.domain.verdict.Verdict
import com.lookfirst.domain.verdict.VerdictLevel
import com.lookfirst.ui.theme.LookFirstTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** The verdict part of the Result screen: banner, sentence, host, reasons, notes and the action stack. */
@RunWith(RobolectricTestRunner::class)
class VerdictScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val resources get() = RuntimeEnvironment.getApplication().resources

    private fun text(id: Int, vararg args: Any): String = resources.getString(id, *args)

    /** A text node whose text equals [expected] once the bidi isolates around the evidence are removed. */
    private fun hasPlainText(expected: String) = SemanticsMatcher("has plain text $expected") { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.any {
            it.text.replace("\u2068", "").replace("\u2069", "") == expected
        } == true
    }

    private fun analysed(raw: String): ResultUiState.Content {
        val state = PayloadParser.parse(raw).toUiState() as ResultUiState.Content
        return state.copy(verdict = AnalyzeScan(NoReputationLists())(raw, "test"))
    }

    private fun show(state: ResultUiState) {
        composeRule.setContent {
            LookFirstTheme {
                ResultScreen(
                    state = state,
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

    private fun showWith(raw: String, reasons: List<Signal>, level: VerdictLevel) {
        val base = analysed(raw)
        show(base.copy(verdict = Verdict(level, reasons, "test")))
    }

    @Test
    fun dangerousShowsItsLabelAndTheHeaviestReasonAsASentence() {
        show(analysed("javascript:alert(1)"))
        composeRule.onNodeWithText(text(R.string.level_dangerous)).assertExists()
        composeRule.onNode(hasPlainText(text(R.string.reason_dangerous_scheme, "javascript"))).assertExists()
        composeRule.onNodeWithText(text(R.string.action_open_anyway)).assertDoesNotExist()
    }

    @Test
    fun suspiciousShowsOpenAnywayAsASecondaryButton() {
        show(analysed("https://192.0.2.1/login"))
        composeRule.onNodeWithText(text(R.string.level_suspicious)).assertExists()
        composeRule.onNodeWithText(text(R.string.action_open_anyway)).assertExists()
        composeRule.onNodeWithContentDescription(text(R.string.action_open_anyway)).assertIsEnabled()
    }

    @Test
    fun cautionShowsTheOpenLabelOfTheType() {
        show(analysed("https://example.com/"))
        composeRule.onNodeWithText(text(R.string.level_caution)).assertExists()
        composeRule.onNodeWithText(text(R.string.open_in_browser)).assertExists()
        composeRule.onNodeWithText(text(R.string.reason_lists_missing)).assertExists()
    }

    @Test
    fun noRiskSignalIsNeverSafeAndAlwaysCarriesTheDisclaimerAndTheChecks() {
        show(analysed("WIFI:T:WPA;S:HomeNet;P:hunter2;;"))
        composeRule.onNodeWithText(text(R.string.level_no_risk_signal)).assertExists()
        composeRule.onNodeWithText(text(R.string.no_risk_disclaimer)).assertExists()
        composeRule.onNodeWithText(text(R.string.checked_line_content)).assertExists()
        composeRule.onAllNodesWithText(text(R.string.checked_line_list_source)).assertCountEquals(0)
        composeRule.onAllNodesWithText("safe", substring = true, ignoreCase = true).assertCountEquals(0)
    }

    @Test
    fun theNoRiskChecksLineOfAWebLinkNamesTheAddressChecksAndTheListRead() {
        showWith("https://example.com/", emptyList(), VerdictLevel.NO_RISK_SIGNAL)
        composeRule.onNodeWithText(text(R.string.checked_line)).assertExists()
        composeRule.onNodeWithText(text(R.string.checked_line_list_source)).assertExists()
        composeRule.onAllNodesWithText("safe", substring = true, ignoreCase = true).assertCountEquals(0)
        composeRule.onNodeWithText(text(R.string.no_risk_disclaimer)).assertExists()
    }

    @Test
    fun theListSourceLineIsAbsentWhenAListSignalOrAnotherRiskIsShown() {
        showWith("https://example.com/", listOf(Signal(SignalCode.LISTS_MISSING)), VerdictLevel.CAUTION)
        composeRule.onAllNodesWithText(text(R.string.checked_line_list_source)).assertCountEquals(0)
    }

    @Test
    fun theListSourceLineIsWordedInBothLanguagesWithoutTheWordSafe() {
        val en = text(R.string.checked_line_list_source)
        assertEquals("List consulted: CERT Polska (centred on Poland).", en)
        val fr = RuntimeEnvironment.getApplication().resources.let {
            val config = android.content.res.Configuration(it.configuration).apply {
                setLocale(java.util.Locale.FRENCH)
            }
            RuntimeEnvironment.getApplication().createConfigurationContext(
                config,
            ).getString(R.string.checked_line_list_source)
        }
        assertEquals("Liste consultée : CERT Polska (centrée sur la Pologne).", fr)
    }

    @Test
    fun anInternationalHostShowsItsUnicodeAndItsTechnicalForm() {
        show(analysed("https://pаypal.com/login"))
        composeRule.onNodeWithText("pаypal.com").assertExists()
        composeRule.onNodeWithText(text(R.string.technical_form, "xn--pypal-4ve.com")).assertExists()
        composeRule.onNodeWithText(text(R.string.technical_form_help)).assertExists()
    }

    @Test
    fun anAsciiHostHasNoTechnicalForm() {
        show(analysed("https://example.com/"))
        composeRule.onAllNodesWithText(text(R.string.technical_form_help)).assertCountEquals(0)
    }

    @Test
    fun theRegistrableDomainIsTheBoldPart() {
        val host = HostDisplay(Untrusted("paypal.com.evil.example"), null, "paypal.com.".length, true)
        val annotated = hostAnnotated(host)
        val bold = annotated.spanStyles.filter { it.item.fontWeight?.weight == 700 }
        assertEquals("evil.example", bold.joinToString("") { annotated.text.substring(it.start, it.end) })
    }

    @Test
    fun theNonAsciiLettersOfAHostAreUnderlined() {
        val annotated = hostAnnotated(HostDisplay(Untrusted("pаypal.com"), "xn--pypal-4ve.com", 0, true))
        val underlined = annotated.spanStyles.filter { it.item.textDecoration != null }
        assertEquals(listOf("а"), underlined.map { annotated.text.substring(it.start, it.end) })
    }

    @Test
    fun reasonsAfterTheMainOneAreShortLabelsThatOpenToTheFullSentence() {
        showWith(
            "https://example.com/",
            listOf(Signal(SignalCode.PLAIN_HTTP), Signal(SignalCode.SHORTENER), Signal(SignalCode.LONG_URL)),
            VerdictLevel.CAUTION,
        )
        composeRule.onNodeWithText(text(R.string.reason_plain_http)).assertExists()
        composeRule.onNodeWithText(text(R.string.reason_shortener_short)).assertExists()
        composeRule.onNodeWithText(text(R.string.reason_shortener)).assertDoesNotExist()
        composeRule.onNodeWithText(text(R.string.reason_shortener_short)).performScrollTo().performClick()
        composeRule.onNodeWithText(text(R.string.reason_shortener)).assertExists()
    }

    @Test
    fun reasonsBeyondTheFirstThreeSitBehindASeeMoreButton() {
        val reasons = listOf(
            Signal(SignalCode.PLAIN_HTTP),
            Signal(SignalCode.SHORTENER),
            Signal(SignalCode.LONG_URL),
            Signal(SignalCode.UNUSUAL_PORT, evidence = Untrusted("8443")),
            Signal(SignalCode.LISTS_MISSING),
            Signal(SignalCode.URL_ODD_FORMAT),
        )
        showWith("https://example.com:8443/", reasons, VerdictLevel.CAUTION)
        val more = resources.getQuantityString(R.plurals.more_signals, 2, 2)
        composeRule.onNodeWithText(text(R.string.reason_unusual_port_short)).assertExists()
        composeRule.onNodeWithText(text(R.string.reason_lists_missing_short)).assertDoesNotExist()
        composeRule.onNodeWithText(more).performScrollTo().performClick()
        composeRule.onNodeWithText(text(R.string.reason_lists_missing_short)).assertExists()
        composeRule.onNodeWithText(text(R.string.reason_url_odd_format_short)).assertExists()
        composeRule.onNodeWithText(text(R.string.fewer_signals)).assertExists()
    }

    @Test
    fun theEvidenceInASentenceIsBoldAndUnderlined() {
        val sentence = ReasonTexts(resources).sentence(
            Signal(SignalCode.USERINFO_AT, evidence = Untrusted("evil.example")),
        )
        val marked = sentence.spanStyles.filter {
            it.item.fontWeight?.weight == 700 && it.item.textDecoration != null
        }
        assertEquals(1, marked.size)
        assertEquals("evil.example", sentence.text.substring(marked[0].start, marked[0].end).trim('⁨', '⁩'))
    }

    @Test
    fun listsLineSaysNoListIsInstalled() {
        show(analysed("https://example.com/"))
        composeRule.onNodeWithText(text(R.string.lists_none)).performScrollTo().assertExists()
    }

    @Test
    fun listsLineGivesTheAgeOfStaleLists() {
        showWith(
            "https://example.com/",
            listOf(Signal(SignalCode.LISTS_STALE, count = 20)),
            VerdictLevel.CAUTION,
        )
        val age = resources.getQuantityString(R.plurals.age_days, 20, 20)
        composeRule.onNodeWithText(text(R.string.lists_age, age)).performScrollTo().assertExists()
    }

    @Test
    fun whileAnalysingTheContentIsThereWithANeutralBannerAndCopyStillWorks() {
        val base = analysed("https://example.com/")
        show(base.copy(verdict = null))
        composeRule.onNodeWithText(text(R.string.analysing_banner)).assertExists()
        composeRule.onNodeWithText("example.com").assertExists()
        composeRule.onNodeWithText(text(R.string.action_copy)).assertExists()
        composeRule.onAllNodesWithText(text(R.string.level_caution)).assertCountEquals(0)
    }

    @Test
    fun theBannerIsTheOnlyHeadingAndIsNotAnnouncedAgainAfterARotation() {
        val tester = StateRestorationTester(composeRule)
        val state = analysed("https://paypal.com@evil.example/login")
        tester.setContent {
            LookFirstTheme {
                ResultScreen(
                    state = state,
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
        composeRule.waitForIdle()
        tester.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()
        composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)).assertCountEquals(0)
        composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).assertCountEquals(1)
    }

    @Test
    fun aCopyOnlyContentHasNoOpenButton() {
        show(analysed("monappli://pay?to=bob"))
        composeRule.onNodeWithText(text(R.string.copy_only)).assertExists()
        composeRule.onAllNodesWithText(text(R.string.open_in_browser)).assertCountEquals(0)
    }
}
