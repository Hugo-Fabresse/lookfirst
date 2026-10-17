package com.lookfirst.ui.screens.result

import android.os.Looper
import com.lookfirst.data.network.RedirectTracer
import com.lookfirst.data.platform.ActionExecutor
import com.lookfirst.data.platform.ClipboardWriter
import com.lookfirst.data.platform.OpenOutcome
import com.lookfirst.data.platform.PayloadIdGenerator
import com.lookfirst.data.platform.RegionHintProvider
import com.lookfirst.domain.analysis.ReputationLookup
import com.lookfirst.domain.analysis.ReputationResult
import com.lookfirst.domain.model.ScanPayload
import com.lookfirst.domain.model.SignalCode
import com.lookfirst.domain.model.Untrusted
import com.lookfirst.domain.open.CanonicalUrl
import com.lookfirst.domain.redirect.RedirectTrace
import com.lookfirst.domain.redirect.TraceEnd
import com.lookfirst.domain.redirect.TraceFailure
import com.lookfirst.domain.redirect.TraceOutcome
import com.lookfirst.domain.usecase.AnalyzeScan
import com.lookfirst.domain.usecase.ApplyRedirectTrace
import com.lookfirst.domain.verdict.VerdictLevel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** The optional "Follow redirects" check: warning, request, new verdict (ux.md 3.7, ADR-0004). */
@RunWith(RobolectricTestRunner::class)
class ResultViewModelTraceTest {
    private class FakeTracer : RedirectTracer {
        val requested = mutableListOf<CanonicalUrl>()
        var outcome: (
            CanonicalUrl,
        ) -> TraceOutcome = { TraceOutcome.Completed(RedirectTrace(it, emptyList(), TraceEnd.Final)) }
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun trace(start: CanonicalUrl): TraceOutcome {
            requested += start
            gate?.await()
            return outcome(start)
        }
    }

    private class FakeActions : ActionExecutor {
        val opened = mutableListOf<ScanPayload>()

        override fun hasNonBrowserHandler(url: ScanPayload.Url): Boolean = false

        override fun open(payload: ScanPayload): OpenOutcome {
            opened += payload
            return OpenOutcome.Started
        }
    }

    private val tracer = FakeTracer()
    private val actions = FakeActions()
    private var online = true
    private var nextId = 0
    private val lookup = ReputationLookup {
        if (it.ascii == "evil.example") ReputationResult.Listed("test") else ReputationResult.NotListed
    }

    private fun viewModel(): ResultViewModel {
        val analyze = AnalyzeScan(lookup)
        return ResultViewModel(
            clipboard = object : ClipboardWriter {
                override fun copy(text: String, sensitive: Boolean) = Unit

                override fun onAppStopped() = Unit

                override fun onWindowFocused() = Unit
            },
            actions = actions,
            analyzeScan = analyze,
            regionHints = RegionHintProvider { null },
            payloadIds = PayloadIdGenerator { "id-${++nextId}" },
            redirectTracer = tracer,
            applyRedirectTrace = ApplyRedirectTrace(analyze),
            networkStatus = { online },
            workDispatcher = Dispatchers.Unconfined,
        )
    }

    private fun shown(raw: String) = viewModel().also { it.show(Untrusted(raw)) }

    private fun ResultViewModel.content() = state.value as ResultUiState.Content

    private fun ResultViewModel.checks() = content().checks

    private fun trace(start: CanonicalUrl, vararg hops: String, end: TraceEnd = TraceEnd.Final): TraceOutcome =
        TraceOutcome.Completed(RedirectTrace(start, hops.map { h -> canonical(h) }, end))

    private fun canonical(raw: String): CanonicalUrl {
        val payload = com.lookfirst.domain.decoder.PayloadParser.parse(raw)
        return checkNotNull(
            CanonicalUrl.of((payload as com.lookfirst.domain.model.ParseResult.Ok).payload as ScanPayload.Url),
        )
    }

    @Test
    fun `only web links get the optional checks`() {
        assertNotNull(shown("https://a.example/").checks())
        assertNotNull(shown("http://a.example/").checks())
        assertNull(shown("tel:+33123456789").checks())
        assertNull(shown("WIFI:T:WPA;S:Net;P:hunter2222;;").checks())
        assertNull(shown("some text").checks())
    }

    @Test
    fun `a plain http link cannot be traced`() {
        val vm = shown("http://a.example/")
        assertTrue(vm.checks()!!.httpsOnly)
        vm.onTraceRequested()
        assertEquals(TraceState.IDLE, vm.checks()!!.trace)
    }

    @Test
    fun `a link on another port cannot be traced`() {
        val vm = shown("https://a.example:8443/")
        assertTrue(vm.checks()!!.unusualPort)
        vm.onTraceRequested()
        assertEquals(TraceState.IDLE, vm.checks()!!.trace)
        assertTrue(tracer.requested.isEmpty())
    }

    @Test
    fun `asking opens the warning and nothing is sent`() {
        val vm = shown("https://a.example/")
        assertEquals(TraceState.IDLE, vm.checks()!!.trace)
        vm.onTraceRequested()
        assertEquals(TraceState.WARNING, vm.checks()!!.trace)
        assertTrue(tracer.requested.isEmpty())
    }

    @Test
    fun `cancelling the warning sends nothing and changes nothing`() {
        val vm = shown("https://a.example/")
        val before = vm.content().verdict
        vm.onTraceRequested()
        vm.onTraceCancelled()
        assertEquals(TraceState.IDLE, vm.checks()!!.trace)
        assertTrue(tracer.requested.isEmpty())
        assertEquals(before, vm.content().verdict)
    }

    @Test
    fun `continue without the warning does nothing`() {
        val vm = shown("https://a.example/")
        vm.onTraceConfirmed()
        assertTrue(tracer.requested.isEmpty())
        assertEquals(TraceState.IDLE, vm.checks()!!.trace)
    }

    @Test
    fun `offline the warning does not open and the section says so`() {
        online = false
        val vm = shown("https://a.example/")
        assertFalse(vm.checks()!!.online)
        vm.onTraceRequested()
        assertEquals(TraceState.IDLE, vm.checks()!!.trace)
        assertTrue(tracer.requested.isEmpty())
    }

    @Test
    fun `going offline after the screen opened is noticed when asking`() {
        val vm = shown("https://a.example/")
        online = false
        vm.onTraceRequested()
        assertFalse(vm.checks()!!.online)
        assertEquals(TraceState.IDLE, vm.checks()!!.trace)
    }

    @Test
    fun `the scanned address in canonical form is the one traced, once`() {
        val vm = shown("https://paypal.com@a.example/p?q=1")
        vm.onTraceRequested()
        vm.onTraceConfirmed()
        assertEquals(listOf("https://a.example/p?q=1"), tracer.requested.map { it.value })
    }

    @Test
    fun `a trace that raises the level yields a new verdict for the same payload`() {
        val vm = shown("https://a.example/")
        val before = vm.content().verdict!!
        assertEquals(VerdictLevel.NO_RISK_SIGNAL, before.level)
        tracer.outcome = { trace(it, "https://evil.example/login") }
        vm.onTraceRequested()
        vm.onTraceConfirmed()
        val after = vm.content().verdict!!
        assertEquals(VerdictLevel.DANGEROUS, after.level)
        assertEquals(before.payloadId, after.payloadId)
        assertTrue(SignalCode.REDIRECT_FINAL in after.reasons.map { it.code })
        assertEquals(TraceState.DONE, vm.checks()!!.trace)
        assertEquals(VerdictLevel.DANGEROUS, vm.checks()!!.levelChangedTo)
    }

    @Test
    fun `an unchanged level is not announced`() {
        val vm = shown("https://a.example/")
        vm.onTraceRequested()
        vm.onTraceConfirmed()
        assertEquals(TraceState.DONE, vm.checks()!!.trace)
        assertNull(vm.checks()!!.levelChangedTo)
        assertTrue(SignalCode.REDIRECT_FINAL in vm.content().verdict!!.reasons.map { it.code })
    }

    @Test
    fun `a heavier local verdict never falls after a clean trace`() {
        val vm = shown("https://evil.example/")
        val before = vm.content().verdict!!
        vm.onTraceRequested()
        vm.onTraceConfirmed()
        val after = vm.content().verdict!!
        assertEquals(VerdictLevel.DANGEROUS, before.level)
        assertEquals(VerdictLevel.DANGEROUS, after.level)
        assertNull(vm.checks()!!.levelChangedTo)
    }

    @Test
    fun `the content on screen and the open target stay the scanned address`() {
        val vm = shown("https://a.example/start")
        val content = vm.content().content
        tracer.outcome = { trace(it, "https://b.example/elsewhere") }
        vm.onTraceRequested()
        vm.onTraceConfirmed()
        assertEquals(content, vm.content().content)
        vm.onOpen()
        val opened = actions.opened.single() as ScanPayload.Url
        assertEquals("a.example", opened.rawHost.value)
        assertEquals("/start", opened.path.value)
    }

    @Test
    fun `a failed trace leaves the verdict alone, says so and can be asked again with the warning`() {
        val vm = shown("https://a.example/")
        val before = vm.content().verdict
        tracer.outcome = { TraceOutcome.Failed(TraceFailure.TIMEOUT) }
        vm.onTraceRequested()
        vm.onTraceConfirmed()
        assertEquals(TraceState.FAILED, vm.checks()!!.trace)
        assertEquals(before, vm.content().verdict)
        vm.onTraceRequested()
        assertEquals(TraceState.WARNING, vm.checks()!!.trace)
        assertEquals(1, tracer.requested.size)
    }

    @Test
    fun `a trace is not offered again once it is done`() {
        val vm = shown("https://a.example/")
        vm.onTraceRequested()
        vm.onTraceConfirmed()
        vm.onTraceRequested()
        assertEquals(TraceState.DONE, vm.checks()!!.trace)
        assertEquals(1, tracer.requested.size)
    }

    @Test
    fun `cancelling while it runs drops the answer`() {
        val vm = shown("https://a.example/")
        val gate = CompletableDeferred<Unit>()
        tracer.gate = gate
        tracer.outcome = { trace(it, "https://evil.example/") }
        val before = vm.content().verdict
        vm.onTraceRequested()
        vm.onTraceConfirmed()
        assertEquals(TraceState.RUNNING, vm.checks()!!.trace)
        vm.onTraceCancelled()
        gate.complete(Unit)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(TraceState.IDLE, vm.checks()!!.trace)
        assertEquals(before, vm.content().verdict)
    }

    @Test
    fun `closing the screen while it runs drops the answer`() {
        val vm = shown("https://a.example/")
        val gate = CompletableDeferred<Unit>()
        tracer.gate = gate
        vm.onTraceRequested()
        vm.onTraceConfirmed()
        vm.clear()
        gate.complete(Unit)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(ResultUiState.NoContent, vm.state.value)
    }

    @Test
    fun `a second tap while it runs sends nothing more`() {
        val vm = shown("https://a.example/")
        tracer.gate = CompletableDeferred()
        vm.onTraceRequested()
        vm.onTraceConfirmed()
        vm.onTraceRequested()
        vm.onTraceConfirmed()
        assertEquals(1, tracer.requested.size)
    }
}
