package com.lookfirst.ui.screens.result

import android.os.Looper
import com.lookfirst.data.platform.ActionExecutor
import com.lookfirst.data.platform.ClipboardWriter
import com.lookfirst.data.platform.OpenOutcome
import com.lookfirst.data.platform.PayloadIdGenerator
import com.lookfirst.data.platform.RegionHintProvider
import com.lookfirst.domain.analysis.ReputationLookup
import com.lookfirst.domain.analysis.ReputationResult
import com.lookfirst.domain.decoder.PayloadParser
import com.lookfirst.domain.model.ParseResult
import com.lookfirst.domain.model.ScanPayload
import com.lookfirst.domain.model.SignalCode
import com.lookfirst.domain.model.Untrusted
import com.lookfirst.domain.open.CanonicalUrl
import com.lookfirst.domain.rdap.RdapClient
import com.lookfirst.domain.rdap.RdapFailure
import com.lookfirst.domain.rdap.RdapOutcome
import com.lookfirst.domain.rdap.RdapPlan
import com.lookfirst.domain.rdap.RdapPlanner
import com.lookfirst.domain.rdap.RdapServer
import com.lookfirst.domain.rdap.RdapTarget
import com.lookfirst.domain.rdap.RdapUnavailable
import com.lookfirst.domain.redirect.RedirectTrace
import com.lookfirst.domain.redirect.TraceEnd
import com.lookfirst.domain.redirect.TraceOutcome
import com.lookfirst.domain.usecase.AnalyzeScan
import com.lookfirst.domain.usecase.ApplyDomainAge
import com.lookfirst.domain.usecase.ApplyRedirectTrace
import com.lookfirst.domain.verdict.VerdictLevel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
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
import kotlin.coroutines.CoroutineContext

/** The optional "Check domain age" check: warning, request, new verdict (ux.md 3.7, 5.12, ADR-0004). */
@RunWith(RobolectricTestRunner::class)
class ResultViewModelAgeTest {
    private class FakeClient : RdapClient {
        val requested = mutableListOf<RdapTarget>()
        var outcome: RdapOutcome = RdapOutcome.Registered(0L, 3)
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun lookup(target: RdapTarget): RdapOutcome {
            requested += target
            gate?.await()
            return outcome
        }
    }

    private class FakeActions : ActionExecutor {
        override fun hasNonBrowserHandler(url: ScanPayload.Url): Boolean = false

        override fun open(payload: ScanPayload): OpenOutcome = OpenOutcome.Started
    }

    private val server = RdapServer("rdap.registry.example", "/v1/")
    private val client = FakeClient()
    private var online = true
    private var nextId = 0
    private val planned = mutableListOf<String>()
    private var plan: (String) -> RdapPlan = { host ->
        RdapPlan.Ready(RdapTarget(host.removePrefix("www."), server))
    }
    private val lookup = ReputationLookup {
        if (it.ascii == "evil.example") ReputationResult.Listed("test") else ReputationResult.NotListed
    }
    private var traceGate: CompletableDeferred<Unit>? = null
    private var traceOutcome: (CanonicalUrl) -> TraceOutcome =
        { TraceOutcome.Completed(RedirectTrace(it, emptyList(), TraceEnd.Final)) }

    private fun viewModel(work: CoroutineDispatcher = Dispatchers.Unconfined): ResultViewModel {
        val analyze = AnalyzeScan(lookup)
        return ResultViewModel(
            clipboard = object : ClipboardWriter {
                override fun copy(text: String, sensitive: Boolean) = Unit

                override fun onAppStopped() = Unit

                override fun onWindowFocused() = Unit
            },
            actions = FakeActions(),
            analyzeScan = analyze,
            regionHints = RegionHintProvider { null },
            payloadIds = PayloadIdGenerator { "id-${++nextId}" },
            redirectTracer = {
                traceGate?.await()
                traceOutcome(it)
            },
            applyRedirectTrace = ApplyRedirectTrace(analyze),
            rdapPlanner = RdapPlanner { host ->
                planned += host
                plan(host)
            },
            rdapClient = client,
            applyDomainAge = ApplyDomainAge(),
            networkStatus = { online },
            workDispatcher = work,
        )
    }

    private fun shown(raw: String) = viewModel().also { it.show(Untrusted(raw)) }

    private fun ResultViewModel.content() = state.value as ResultUiState.Content

    private fun ResultViewModel.checks() = checkNotNull(content().checks)

    private fun ResultViewModel.askAndConfirm() {
        onAgeRequested()
        onAgeConfirmed()
    }

    @Test
    fun `a web link offers the check with the domain and the registry named`() {
        val vm = shown("https://www.a.example/login?x=1")
        assertEquals(AgeTarget("a.example", "rdap.registry.example"), vm.checks().ageTarget)
        assertNull(vm.checks().ageUnavailable)
        assertEquals(CheckState.IDLE, vm.checks().age)
        assertEquals(listOf("www.a.example"), planned)
    }

    @Test
    fun `content that is not a web link has no checks and plans nothing`() {
        assertNull(shown("tel:+33123456789").content().checks)
        assertNull(shown("hello").content().checks)
        assertTrue(planned.isEmpty())
    }

    @Test
    fun `an http link or one on another port can still be checked, only the domain is sent`() {
        assertNotNull(shown("http://a.example/").checks().ageTarget)
        assertNotNull(shown("https://a.example:8443/").checks().ageTarget)
    }

    @Test
    fun `no registry to ask says why and the button does nothing`() {
        plan = { RdapPlan.Unavailable(RdapUnavailable.NO_SERVER) }
        val vm = shown("https://a.example/")
        assertNull(vm.checks().ageTarget)
        assertEquals(RdapUnavailable.NO_SERVER, vm.checks().ageUnavailable)
        vm.onAgeRequested()
        vm.onAgeConfirmed()
        assertEquals(CheckState.IDLE, vm.checks().age)
        assertTrue(client.requested.isEmpty())
    }

    @Test
    fun `a planner that fails means no registry, never a crash`() {
        plan = { error("boom") }
        val vm = shown("https://a.example/")
        assertEquals(RdapUnavailable.NO_BOOTSTRAP, vm.checks().ageUnavailable)
    }

    @Test
    fun `asking opens the warning and nothing is sent`() {
        val vm = shown("https://a.example/")
        vm.onAgeRequested()
        assertEquals(CheckState.WARNING, vm.checks().age)
        assertTrue(client.requested.isEmpty())
    }

    @Test
    fun `continue without the warning does nothing`() {
        val vm = shown("https://a.example/")
        vm.onAgeConfirmed()
        assertTrue(client.requested.isEmpty())
        assertEquals(CheckState.IDLE, vm.checks().age)
    }

    @Test
    fun `cancelling the warning sends nothing and changes nothing`() {
        val vm = shown("https://a.example/")
        val before = vm.content().verdict
        vm.onAgeRequested()
        vm.onAgeCancelled()
        assertEquals(CheckState.IDLE, vm.checks().age)
        assertTrue(client.requested.isEmpty())
        assertEquals(before, vm.content().verdict)
    }

    @Test
    fun `offline the warning does not open`() {
        online = false
        val vm = shown("https://a.example/")
        assertFalse(vm.checks().online)
        vm.onAgeRequested()
        assertEquals(CheckState.IDLE, vm.checks().age)
        assertTrue(client.requested.isEmpty())
    }

    @Test
    fun `only the registrable domain is sent, once`() {
        val vm = shown("https://paypal.com@www.a.example/p?q=secret#frag")
        vm.askAndConfirm()
        assertEquals(listOf("a.example"), client.requested.map { it.domain })
        assertEquals(server, client.requested.single().server)
    }

    @Test
    fun `a recent domain raises the level, announces it once and keeps the payload`() {
        val vm = shown("https://a.example/")
        val before = vm.content().verdict!!
        assertEquals(VerdictLevel.NO_RISK_SIGNAL, before.level)
        client.outcome = RdapOutcome.Registered(registeredAtMillis = 123L, ageDays = 4)
        vm.askAndConfirm()
        val after = vm.content().verdict!!
        assertEquals(VerdictLevel.SUSPICIOUS, after.level)
        assertEquals(before.payloadId, after.payloadId)
        assertEquals(SignalCode.DOMAIN_RECENT, after.reasons.first().code)
        assertEquals(CheckState.DONE, vm.checks().age)
        assertEquals(client.outcome, vm.checks().ageOutcome)
        assertEquals(VerdictLevel.SUSPICIOUS, vm.checks().levelChangedTo)
    }

    @Test
    fun `an old domain keeps the level, announces nothing and shows its date`() {
        val vm = shown("https://a.example/")
        client.outcome = RdapOutcome.Registered(registeredAtMillis = 1L, ageDays = 5_000)
        vm.askAndConfirm()
        assertEquals(VerdictLevel.NO_RISK_SIGNAL, vm.content().verdict!!.level)
        assertNull(vm.checks().levelChangedTo)
        assertEquals(CheckState.DONE, vm.checks().age)
        assertEquals(client.outcome, vm.checks().ageOutcome)
    }

    @Test
    fun `a 404 is information only`() {
        val vm = shown("https://a.example/")
        client.outcome = RdapOutcome.NotFound
        vm.askAndConfirm()
        val verdict = vm.content().verdict!!
        assertEquals(VerdictLevel.NO_RISK_SIGNAL, verdict.level)
        assertTrue(SignalCode.DOMAIN_UNKNOWN in verdict.reasons.map { it.code })
        assertNull(vm.checks().levelChangedTo)
    }

    @Test
    fun `a heavier local verdict never falls after the answer`() {
        val vm = shown("https://evil.example/")
        client.outcome = RdapOutcome.Registered(1L, 9_000)
        vm.askAndConfirm()
        assertEquals(VerdictLevel.DANGEROUS, vm.content().verdict!!.level)
        assertNull(vm.checks().levelChangedTo)
    }

    @Test
    fun `a failure leaves the verdict alone, says so and can be asked again with the warning`() {
        val vm = shown("https://a.example/")
        val before = vm.content().verdict
        client.outcome = RdapOutcome.Failed(RdapFailure.TIMEOUT)
        vm.askAndConfirm()
        assertEquals(CheckState.FAILED, vm.checks().age)
        assertEquals(before, vm.content().verdict)
        assertNull(vm.checks().ageOutcome)
        vm.onAgeRequested()
        assertEquals(CheckState.WARNING, vm.checks().age)
        assertEquals(1, client.requested.size)
    }

    @Test
    fun `a check is not offered again once it is done`() {
        val vm = shown("https://a.example/")
        vm.askAndConfirm()
        vm.onAgeRequested()
        vm.onAgeConfirmed()
        assertEquals(CheckState.DONE, vm.checks().age)
        assertEquals(1, client.requested.size)
    }

    @Test
    fun `cancelling while it runs drops the answer`() {
        val vm = shown("https://a.example/")
        val gate = CompletableDeferred<Unit>()
        client.gate = gate
        val before = vm.content().verdict
        vm.askAndConfirm()
        assertEquals(CheckState.RUNNING, vm.checks().age)
        vm.onAgeCancelled()
        gate.complete(Unit)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(CheckState.IDLE, vm.checks().age)
        assertEquals(before, vm.content().verdict)
    }

    @Test
    fun `closing the screen while it runs drops the answer`() {
        val vm = shown("https://a.example/")
        val gate = CompletableDeferred<Unit>()
        client.gate = gate
        vm.askAndConfirm()
        vm.clear()
        gate.complete(Unit)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(ResultUiState.NoContent, vm.state.value)
    }

    @Test
    fun `a second tap while it runs sends nothing more`() {
        val vm = shown("https://a.example/")
        client.gate = CompletableDeferred()
        vm.askAndConfirm()
        vm.askAndConfirm()
        assertEquals(1, client.requested.size)
    }

    @Test
    fun `the two checks add up in either order and neither drops the other's signals`() {
        val vm = shown("https://a.example/")
        client.outcome = RdapOutcome.Registered(1L, 10)
        vm.askAndConfirm()
        vm.onTraceRequested()
        vm.onTraceConfirmed()
        val codes = vm.content().verdict!!.reasons.map { it.code }
        assertTrue(SignalCode.DOMAIN_RECENT in codes)
        assertTrue(SignalCode.REDIRECT_FINAL in codes)
        assertEquals(VerdictLevel.SUSPICIOUS, vm.content().verdict!!.level)
        // The announcement of the first level change is still there: nothing changed it again.
        assertEquals(VerdictLevel.SUSPICIOUS, vm.checks().levelChangedTo)
    }

    @Test
    fun `a trace first and then the age keeps the redirect signals`() {
        val vm = shown("https://a.example/")
        vm.onTraceRequested()
        vm.onTraceConfirmed()
        client.outcome = RdapOutcome.Registered(1L, 50)
        vm.askAndConfirm()
        val verdict = vm.content().verdict!!
        assertTrue(SignalCode.REDIRECT_FINAL in verdict.reasons.map { it.code })
        assertEquals(VerdictLevel.CAUTION, verdict.level)
        assertEquals(VerdictLevel.CAUTION, vm.checks().levelChangedTo)
    }

    /** Runs the work handed to it one task at a time, when the test says so. */
    private class StepDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queue.addLast(block)
        }

        fun runOne(): Boolean = queue.removeFirstOrNull()?.also { it.run() } != null
    }

    private fun canonical(raw: String): CanonicalUrl {
        val payload = (PayloadParser.parse(raw) as ParseResult.Ok).payload as ScanPayload.Url
        return checkNotNull(CanonicalUrl.of(payload))
    }

    /**
     * Both checks are under way when their answers arrive, in the given order, and every composition is run one task
     * at a time. Returns the final verdict and every level seen on the way.
     */
    private fun interleaved(traceFirst: Boolean): Pair<com.lookfirst.domain.verdict.Verdict, List<VerdictLevel>> {
        val work = StepDispatcher()
        val vm = viewModel(work)
        vm.show(Untrusted("https://a.example/"))
        drain(vm, work, mutableListOf())
        val tGate = CompletableDeferred<Unit>().also { traceGate = it }
        val aGate = CompletableDeferred<Unit>().also { client.gate = it }
        traceOutcome = {
            TraceOutcome.Completed(RedirectTrace(it, listOf(canonical("https://evil.example/login")), TraceEnd.Final))
        }
        client.outcome = RdapOutcome.Registered(1L, 5)
        vm.onTraceRequested()
        vm.onTraceConfirmed()
        vm.onAgeRequested()
        vm.onAgeConfirmed()
        val levels = mutableListOf(vm.content().verdict!!.level)
        val (first, second) = if (traceFirst) tGate to aGate else aGate to tGate
        first.complete(Unit)
        shadowOf(Looper.getMainLooper()).idle()
        second.complete(Unit)
        shadowOf(Looper.getMainLooper()).idle()
        drain(vm, work, levels)
        assertEquals(CheckState.DONE, vm.checks().trace)
        assertEquals(CheckState.DONE, vm.checks().age)
        return vm.content().verdict!! to levels
    }

    private fun drain(vm: ResultViewModel, work: StepDispatcher, levels: MutableList<VerdictLevel>) {
        var guard = 0
        while (guard++ < 50) {
            val ran = work.runOne()
            shadowOf(Looper.getMainLooper()).idle()
            (vm.state.value as? ResultUiState.Content)?.verdict?.let { levels += it.level }
            if (!ran) return
        }
    }

    @Test
    fun `the two answers give the same verdict in either order and no level falls on the way`() {
        val (a, levelsA) = interleaved(traceFirst = true)
        val (b, levelsB) = interleaved(traceFirst = false)
        assertEquals(VerdictLevel.DANGEROUS, a.level)
        assertEquals(a.level, b.level)
        assertEquals(a.reasons.map { it.code }.toSet(), b.reasons.map { it.code }.toSet())
        assertTrue(SignalCode.DOMAIN_RECENT in a.reasons.map { it.code })
        assertTrue(SignalCode.REDIRECT_FINAL in a.reasons.map { it.code })
        for (levels in listOf(levelsA, levelsB)) {
            // VerdictLevel is declared heaviest first: a smaller ordinal is a heavier level.
            assertTrue("levels fell: $levels", levels.zipWithNext().all { (x, y) -> y.ordinal <= x.ordinal })
        }
    }
}
