package com.lookfirst.ui.screens.result

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.lookfirst.R
import com.lookfirst.domain.decoder.PayloadParser
import com.lookfirst.ui.components.TouchGuard
import com.lookfirst.ui.components.isPartiallyObscured
import com.lookfirst.ui.theme.LookFirstTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ResultScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var cancels = 0
    private var copies = 0
    private var confirms = 0
    private var dismisses = 0
    private var root: View? = null

    private fun text(id: Int, vararg args: Any): String = RuntimeEnvironment.getApplication().getString(id, *args)

    private fun plural(id: Int, quantity: Int): String =
        RuntimeEnvironment.getApplication().resources.getQuantityString(id, quantity, quantity)

    private fun show(state: ResultUiState) {
        composeRule.setContent {
            LookFirstTheme {
                root = LocalView.current
                ResultScreen(
                    state = state,
                    snackbarHostState = SnackbarHostState(),
                    onCancel = { cancels++ },
                    onCopy = { copies++ },
                    onConfirmSecretCopy = { confirms++ },
                    onDismissSecretCopy = { dismisses++ },
                )
            }
        }
    }

    private fun showScanned(raw: String, confirmingSecretCopy: Boolean = false) {
        val state = PayloadParser.parse(raw).toUiState()
        show(if (state is ResultUiState.Content) state.copy(confirmingSecretCopy = confirmingSecretCopy) else state)
    }

    @Test
    fun severalCodesInAnImageAreSaidOnlyWhenFlagged() {
        val state = PayloadParser.parse("https://first.example/").toUiState(multipleCodes = true)
        show(state)
        composeRule.onNodeWithText(text(R.string.err_multi_qr)).assertExists()
    }

    @Test
    fun aSingleCodeHasNoMultipleCodesLine() {
        showScanned("https://first.example/")
        composeRule.onAllNodesWithText(text(R.string.err_multi_qr)).assertCountEquals(0)
    }

    @Test
    fun urlShowsTheNotAnalysedBannerTheRealHostAndOnlyCancelAndCopy() {
        showScanned("https://paypal.com@evil.example/login")
        composeRule.onNodeWithText(text(R.string.not_analysed_banner), substring = true).assertExists()
        composeRule.onNodeWithText(text(R.string.type_url_https)).assertExists()
        composeRule.onNodeWithText(text(R.string.link_will_open)).assertExists()
        composeRule.onNodeWithText("evil.example").assertExists()
        composeRule.onNodeWithText("paypal.com").assertExists()
        composeRule.onNodeWithText(text(R.string.userinfo_note)).assertExists()
        composeRule.onNodeWithText("https://paypal.com@evil.example/login").assertExists()
        composeRule.onNodeWithText(text(R.string.action_cancel)).assertExists()
        composeRule.onNodeWithText(text(R.string.action_copy)).assertExists()
        composeRule.onAllNodesWithText("Open", substring = true).assertCountEquals(0)
    }

    @Test
    fun cancelAndTheBackArrowBothCancel() {
        showScanned("https://example.com")
        composeRule.onNodeWithText(text(R.string.action_cancel)).performClick()
        composeRule.onNodeWithContentDescription(text(R.string.a11y_back)).performClick()
        assertEquals(2, cancels)
    }

    @Test
    fun copyIsAnExplicitTap() {
        showScanned("https://example.com")
        assertEquals(0, copies)
        composeRule.onNodeWithText(text(R.string.action_copy)).performClick()
        assertEquals(1, copies)
    }

    @Test
    fun wifiPasswordIsMaskedUntilShownAndNeverInTheExactContent() {
        showScanned("WIFI:T:WPA;S:HomeNet;P:hunter2;;")
        composeRule.onNodeWithText(text(R.string.type_wifi)).assertExists()
        composeRule.onNodeWithText("HomeNet").assertExists()
        composeRule.onAllNodesWithText("hunter2", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText(text(R.string.secret_masked)).assertCountEquals(2)

        composeRule.onNodeWithText(text(R.string.secret_show)).performScrollTo().performClick()
        composeRule.onNodeWithText("hunter2").assertExists()
        composeRule.onNodeWithText(text(R.string.secret_hide)).performScrollTo().performClick()
        composeRule.onAllNodesWithText("hunter2", substring = true).assertCountEquals(0)
    }

    @Test
    fun otpauthSecretIsMaskedAndTheExactContentNeedsAnExplicitShow() {
        showScanned("otpauth://totp/Example:alice?secret=JBSWY3DPEHPK3PXP&issuer=Example")
        composeRule.onNodeWithText(text(R.string.type_otpauth)).assertExists()
        composeRule.onNodeWithText("Example").assertExists()
        composeRule.onNodeWithText("alice").assertExists()
        composeRule.onAllNodesWithText("JBSWY3DPEHPK3PXP", substring = true).assertCountEquals(0)

        composeRule.onNodeWithText(text(R.string.show_content)).performScrollTo().performClick()
        composeRule.onNodeWithText(text(R.string.hide_content)).assertExists()
        composeRule.onNodeWithText(
            "otpauth://totp/Example:alice?secret=JBSWY3DPEHPK3PXP&issuer=Example",
        ).assertExists()
    }

    @Test
    fun bidiOverrideIsDrawnAsAVisibleTokenAndNeverAsTheRawCharacter() {
        showScanned("https://example.com/\u202Eevil")
        composeRule.onNodeWithText("<U+202E>", substring = true).assertExists()
        composeRule.onAllNodesWithText("\u202E", substring = true).assertCountEquals(0)
        composeRule.onNodeWithContentDescription(
            text(R.string.a11y_invisible_char, "U+202E", text(R.string.a11y_bidi_override)),
            substring = true,
        ).assertExists()
        composeRule.onNodeWithText(plural(R.plurals.reason_hidden_chars, 1)).assertExists()
    }

    @Test
    fun lineBreakIsDrawnAsALfToken() {
        showScanned("line one\nline two")
        composeRule.onNodeWithText("<LF>", substring = true).assertExists()
    }

    @Test
    fun longContentIsFoldedUntilTheUserAsksForIt() {
        showScanned("https://example.com/" + "a".repeat(200))
        composeRule.onNodeWithText(text(R.string.show_content)).performScrollTo().performClick()
        composeRule.onNodeWithText(text(R.string.hide_content)).assertExists()
        composeRule.onNodeWithText(text(R.string.hide_content)).performClick()
        composeRule.onNodeWithText(text(R.string.show_content)).assertExists()
    }

    @Test
    fun shortContentHasNoFoldButton() {
        showScanned("hello")
        composeRule.onNodeWithText(text(R.string.show_content)).assertDoesNotExist()
    }

    @Test
    fun contentPastTheDisplayCapSaysHowManyCharactersAreLeftOut() {
        showScanned("b".repeat(3000))
        composeRule.onNodeWithText(text(R.string.show_content)).performScrollTo().performClick()
        composeRule.onNodeWithText(plural(R.plurals.chars_more, 1000)).assertExists()
    }

    @Test
    fun tooLargeContentIsNotShownAndCannotBeCopied() {
        showScanned("a".repeat(5000))
        composeRule.onNodeWithText(text(R.string.unreadable_title)).assertExists()
        composeRule.onNodeWithText(text(R.string.err_too_big)).assertExists()
        composeRule.onNodeWithText(text(R.string.err_not_a_green_light)).assertExists()
        composeRule.onAllNodesWithText(text(R.string.action_copy)).assertCountEquals(0)
        composeRule.onNodeWithText(text(R.string.action_cancel)).performClick()
        assertEquals(1, cancels)
    }

    @Test
    fun emptyContentHasTheSameLayoutAndNoCopy() {
        showScanned("   ")
        composeRule.onNodeWithText(text(R.string.err_empty)).assertExists()
        composeRule.onAllNodesWithText(text(R.string.action_copy)).assertCountEquals(0)
    }

    @Test
    fun deepLinkIsCopyOnlyWithAnExplanation() {
        showScanned("monappli://pay?to=bob")
        composeRule.onNodeWithText(text(R.string.type_applink)).assertExists()
        composeRule.onNodeWithText(text(R.string.not_openable)).assertExists()
        composeRule.onNodeWithText(text(R.string.copy_only)).assertExists()
    }

    @Test
    fun copyingASecretShowsTheConfirmationDialogWithCancelAndCopy() {
        showScanned("WIFI:T:WPA;S:HomeNet;P:hunter2;;", confirmingSecretCopy = true)
        composeRule.onNodeWithText(text(R.string.dialog_copy_secret_title)).assertExists()
        composeRule.onNodeWithText(text(R.string.dialog_copy_secret_body)).assertExists()
        val dialogCopy = hasText(text(R.string.action_copy)) and !hasContentDescription(text(R.string.a11y_copy_exact))
        composeRule.onNode(dialogCopy).performClick()
        assertEquals(1, confirms)
        val dialogCancel = hasText(text(R.string.action_cancel)) and
            !hasContentDescription(text(R.string.a11y_cancel_resume))
        composeRule.onNode(dialogCancel).performClick()
        assertEquals(1, dismisses)
        assertEquals(0, copies)
    }

    @Test
    fun aNonLatinHostGetsAWarningAndAnAsciiHostDoesNot() {
        showScanned("https://p\u0430ypal.com/login")
        composeRule.onNodeWithText(text(R.string.host_non_latin_warning)).assertExists()
    }

    @Test
    fun anAsciiHostHasNoHomographWarning() {
        showScanned("https://paypal.com/login")
        composeRule.onAllNodesWithText(text(R.string.host_non_latin_warning)).assertCountEquals(0)
    }

    private fun touch(down: Long, time: Long, action: Int, x: Float, y: Float, flags: Int): MotionEvent {
        val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0 })
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
            },
        )
        return MotionEvent.obtain(down, time, action, 1, properties, coords, 0, 0, 1f, 1f, 0, 0, 0, flags)
    }

    /** Dispatches a real tap on Copy the way MainActivity does, with the overlay flag when [obscured]. */
    private fun tapCopy(obscured: Boolean) {
        val center = composeRule.onNodeWithText(text(R.string.action_copy)).fetchSemanticsNode().boundsInRoot.center
        val start = SystemClock.uptimeMillis()
        val flags = if (obscured) MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED else 0
        val events = listOf(
            touch(start, start, MotionEvent.ACTION_DOWN, center.x, center.y, flags),
            touch(start, start + 50, MotionEvent.ACTION_UP, center.x, center.y, flags),
        )
        for (event in events) {
            TouchGuard.track(event.isPartiallyObscured()) { root!!.dispatchTouchEvent(event) }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun aTapOnCopyUnderAnOverlayCopiesNothingAndSaysWhy() {
        showScanned("https://example.com")
        tapCopy(obscured = true)
        assertEquals(0, copies)
        composeRule.onNodeWithText(text(R.string.overlay_blocked)).assertExists()
    }

    @Test
    fun theSameTapWithoutAnOverlayCopies() {
        showScanned("https://example.com")
        tapCopy(obscured = false)
        assertEquals(1, copies)
    }

    @Test
    fun cancelStaysUsableUnderAnOverlay() {
        showScanned("https://example.com")
        TouchGuard.track(partiallyObscured = true) {
            composeRule.onNodeWithText(text(R.string.action_cancel)).performClick()
        }
        assertEquals(1, cancels)
    }

    @Test
    fun parsingShowsOnlyCancel() {
        show(ResultUiState.Parsing)
        composeRule.onNodeWithText(text(R.string.action_cancel)).assertExists()
        composeRule.onAllNodesWithText(text(R.string.action_copy)).assertCountEquals(0)
    }
}
