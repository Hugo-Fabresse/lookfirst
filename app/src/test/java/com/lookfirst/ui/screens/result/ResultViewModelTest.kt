package com.lookfirst.ui.screens.result

import app.cash.turbine.test
import com.lookfirst.R
import com.lookfirst.data.platform.ClipboardWriter
import com.lookfirst.data.platform.PayloadIdGenerator
import com.lookfirst.data.platform.RegionHintProvider
import com.lookfirst.data.reputation.NoReputationLists
import com.lookfirst.domain.model.SignalCode
import com.lookfirst.domain.model.Untrusted
import com.lookfirst.domain.usecase.AnalyzeScan
import com.lookfirst.domain.verdict.Verdict
import com.lookfirst.domain.verdict.VerdictLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Robolectric only for the main looper behind `viewModelScope`; parsing runs on `Dispatchers.Unconfined`. */
@RunWith(RobolectricTestRunner::class)
class ResultViewModelTest {
    private class FakeClipboard : ClipboardWriter {
        val copies = mutableListOf<Pair<String, Boolean>>()

        override fun copy(text: String, sensitive: Boolean) {
            copies += text to sensitive
        }

        override fun onAppStopped() = Unit

        override fun onWindowFocused() = Unit
    }

    private val clipboard = FakeClipboard()
    private var region: String? = null
    private var nextId = 0

    private fun viewModel() = ResultViewModel(
        clipboard = clipboard,
        analyzeScan = AnalyzeScan(NoReputationLists()),
        regionHints = RegionHintProvider { region },
        payloadIds = PayloadIdGenerator { "id-${++nextId}" },
        workDispatcher = Dispatchers.Unconfined,
    )

    private fun ResultViewModel.verdict(): Verdict = checkNotNull((state.value as ResultUiState.Content).verdict)

    private fun verdictOf(raw: String): Verdict = viewModel().also { it.show(Untrusted(raw)) }.verdict()

    private fun ResultViewModel.content(): ResultContent = (state.value as ResultUiState.Content).content

    @Test
    fun startsWithNothingToShow() {
        assertEquals(ResultUiState.NoContent, viewModel().state.value)
    }

    @Test
    fun aCapturedUrlIsParsedAndShownWithItsRealHost() = runBlocking {
        val vm = viewModel()
        vm.state.test {
            assertEquals(ResultUiState.NoContent, awaitItem())
            vm.show(Untrusted("https://paypal.com@evil.example:8443/login"))
            val content = (expectMostRecentItem() as ResultUiState.Content).content
            assertEquals(R.string.type_url_https, content.typeLabel)
            assertEquals("evil.example", content.host?.shown?.value)
            assertEquals(listOf(R.string.field_userinfo, R.string.field_port), content.fields.map { it.label })
            assertEquals(FieldKind.WARNING, content.fields[0].kind)
            assertEquals("paypal.com", content.fields[0].value.value)
            assertFalse(content.containsSecret)
        }
    }

    @Test
    fun multipleCodesFromAnImageAreFlaggedOnTheContent() {
        val vm = viewModel()
        vm.show(Untrusted("https://first.example"), multipleCodes = true)
        assertTrue(vm.content().multipleCodes)
        vm.clear()
        vm.show(Untrusted("https://first.example"))
        assertFalse(vm.content().multipleCodes)
    }

    @Test
    fun theContentNeverChangesWhileOpen() {
        val vm = viewModel()
        vm.show(Untrusted("https://first.example"))
        val shown = vm.state.value
        vm.show(Untrusted("https://second.example"))
        assertEquals(shown, vm.state.value)
        vm.onCopy()
        assertEquals(listOf("https://first.example" to false), clipboard.copies)
    }

    @Test
    fun tooLargeAndEmptyContentsAreUnreadableAndCannotBeCopied() {
        val big = viewModel()
        big.show(Untrusted("a".repeat(5000)))
        assertEquals(ResultUiState.Unreadable(UnreadableReason.TOO_LARGE), big.state.value)
        big.onCopy()
        val empty = viewModel()
        empty.show(Untrusted("  \n "))
        assertEquals(ResultUiState.Unreadable(UnreadableReason.EMPTY), empty.state.value)
        empty.onCopy()
        assertTrue(clipboard.copies.isEmpty())
    }

    @Test
    fun copyPutsTheExactTextOnTheClipboardAndSaysSo() = runBlocking {
        val vm = viewModel()
        val raw = "https://example.com/a?b=1"
        vm.show(Untrusted(raw))
        vm.events.test {
            vm.onCopy()
            assertEquals(ResultEvent.Copied, awaitItem())
        }
        assertEquals(listOf(raw to false), clipboard.copies)
    }

    @Test
    fun copyingASecretAsksFirstThenFlagsItSensitive() = runBlocking {
        val vm = viewModel()
        val raw = "WIFI:T:WPA;S:HomeNet;P:hunter2;;"
        vm.show(Untrusted(raw))
        vm.events.test {
            vm.onCopy()
            assertTrue((vm.state.value as ResultUiState.Content).confirmingSecretCopy)
            assertTrue(clipboard.copies.isEmpty())
            expectNoEvents()
            vm.onConfirmSecretCopy()
            assertEquals(ResultEvent.Copied, awaitItem())
        }
        assertFalse((vm.state.value as ResultUiState.Content).confirmingSecretCopy)
        assertEquals(listOf(raw to true), clipboard.copies)
    }

    @Test
    fun dismissingTheSecretDialogCopiesNothing() {
        val vm = viewModel()
        vm.show(Untrusted("otpauth://totp/Example:alice?secret=JBSWY3DPEHPK3PXP&issuer=Example"))
        vm.onCopy()
        vm.onDismissSecretCopy()
        assertFalse((vm.state.value as ResultUiState.Content).confirmingSecretCopy)
        vm.onConfirmSecretCopy()
        assertTrue(clipboard.copies.isEmpty())
    }

    @Test
    fun aWifiWithoutPasswordIsNotASecret() {
        val vm = viewModel()
        vm.show(Untrusted("WIFI:T:nopass;S:Cafe;;"))
        assertFalse(vm.content().containsSecret)
        vm.onCopy()
        assertEquals(1, clipboard.copies.size)
        assertFalse(clipboard.copies[0].second)
    }

    @Test
    fun clearForgetsTheContentAndAllowsANewOne() {
        val vm = viewModel()
        vm.show(Untrusted("one"))
        vm.clear()
        assertEquals(ResultUiState.NoContent, vm.state.value)
        vm.onCopy()
        assertTrue(clipboard.copies.isEmpty())
        vm.show(Untrusted("two"))
        assertEquals("two", vm.content().exact.value)
    }

    @Test
    fun clearForgetsTheSecretState() {
        val vm = viewModel()
        vm.show(Untrusted("WIFI:T:WPA;S:HomeNet;P:hunter2;;"))
        vm.clear()
        vm.show(Untrusted("hello"))
        vm.onCopy()
        assertEquals(listOf("hello" to false), clipboard.copies)
    }

    @Test
    fun hiddenCharactersAreCountedIncludingThoseInDecodedFields() {
        val vm = viewModel()
        vm.show(Untrusted("https://example.com/\u202Eevil"))
        assertEquals(1, vm.content().hiddenCharCount)
        val field = viewModel()
        field.show(Untrusted("otpauth://totp/Example:alice?secret=ABC&issuer=Goo%E2%80%8Bgle"))
        assertTrue(field.content().hiddenCharCount >= 1)
    }

    @Test
    fun anInternationalHostKeepsItsUnicodeFormItsTechnicalFormAndItsRegistrableDomain() {
        val vm = viewModel()
        vm.show(Untrusted("https://www.p\u0430ypal.com/login"))
        val host = checkNotNull(vm.content().host)
        assertEquals("www.p\u0430ypal.com", host.shown.value)
        assertEquals("www.xn--pypal-4ve.com", host.technical)
        assertEquals("www.".length, host.strongFrom)
        assertTrue(host.normalized)
    }

    @Test
    fun anAsciiHostHasNoTechnicalForm() {
        val vm = viewModel()
        vm.show(Untrusted("https://Paypal.com@Evil.Example/login"))
        val host = checkNotNull(vm.content().host)
        assertEquals("evil.example", host.shown.value)
        assertNull(host.technical)
        assertEquals(0, host.strongFrom)
    }

    @Test
    fun theContentShowsFirstWithoutVerdictThenTheVerdictArrives() = runBlocking {
        val vm = viewModel()
        vm.state.test {
            assertEquals(ResultUiState.NoContent, awaitItem())
            vm.show(Untrusted("https://example.com"))
            assertEquals(ResultUiState.Parsing, awaitItem())
            val analysing = awaitItem() as ResultUiState.Content
            assertNull(analysing.verdict)
            val done = awaitItem() as ResultUiState.Content
            assertEquals(analysing.content, done.content)
            assertEquals(VerdictLevel.CAUTION, done.verdict?.level)
        }
    }

    @Test
    fun theVerdictLevelFollowsTheSignals() {
        assertEquals(VerdictLevel.DANGEROUS, verdictOf("javascript:alert(1)").level)
        assertEquals(VerdictLevel.SUSPICIOUS, verdictOf("https://192.0.2.1/login").level)
        assertEquals(VerdictLevel.CAUTION, verdictOf("https://example.com/").level)
        assertEquals(VerdictLevel.NO_RISK_SIGNAL, verdictOf("WIFI:T:WPA;S:HomeNet;P:hunter2;;").level)
    }

    @Test
    fun anUnderstoodUrlWithoutListsIsAtLeastCautionAndSaysWhy() {
        val verdict = verdictOf("https://example.com/")
        assertTrue(verdict.reasons.any { it.code == SignalCode.LISTS_MISSING })
        assertEquals(ListsInfo.None, verdict.listsInfo())
    }

    @Test
    fun eachShownContentGetsItsOwnPayloadId() {
        val vm = viewModel()
        vm.show(Untrusted("https://one.example/"))
        val first = vm.verdict().payloadId
        vm.clear()
        vm.show(Untrusted("https://one.example/"))
        assertEquals("id-1", first)
        assertEquals("id-2", vm.verdict().payloadId)
    }

    @Test
    fun theRegionHintReachesTheAnalysis() {
        region = "FR"
        assertTrue(verdictOf("tel:0899123456").reasons.any { it.code == SignalCode.PREMIUM_NUMBER })
        region = null
        assertTrue(verdictOf("tel:0899123456").reasons.none { it.code == SignalCode.PREMIUM_NUMBER })
    }

    @Test
    fun openKindFollowsTheContentType() {
        assertEquals(
            OpenKind.BROWSER,
            viewModel().also {
                it.show(Untrusted("https://example.com"))
            }.content().openKind,
        )
        assertEquals(OpenKind.DIALER, viewModel().also { it.show(Untrusted("tel:+33123456789")) }.content().openKind)
        assertNull(viewModel().also { it.show(Untrusted("monappli://pay")) }.content().openKind)
        assertNull(viewModel().also { it.show(Untrusted("hello")) }.content().openKind)
    }

    @Test
    fun deepLinksAreCopyOnlyAndNoOtherTypeIs() {
        val link = viewModel()
        link.show(Untrusted("monappli://pay?to=bob"))
        assertTrue(link.content().copyOnly)
        assertNull(link.content().host)
        val text = viewModel()
        text.show(Untrusted("hello"))
        assertFalse(text.content().copyOnly)
        assertEquals(R.string.type_text, text.content().typeLabel)
    }
}
