package com.lookfirst.ui.screens.result

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lookfirst.data.network.RedirectTracer
import com.lookfirst.data.platform.ActionExecutor
import com.lookfirst.data.platform.ClipboardWriter
import com.lookfirst.data.platform.NetworkStatus
import com.lookfirst.data.platform.OpenOutcome
import com.lookfirst.data.platform.PayloadIdGenerator
import com.lookfirst.data.platform.RegionHintProvider
import com.lookfirst.data.platform.SystemDialogRequest
import com.lookfirst.di.DefaultDispatcher
import com.lookfirst.domain.analysis.AnalysisContext
import com.lookfirst.domain.decoder.PayloadParser
import com.lookfirst.domain.model.ParseResult
import com.lookfirst.domain.model.ScanPayload
import com.lookfirst.domain.model.Signal
import com.lookfirst.domain.model.SignalCode
import com.lookfirst.domain.model.Untrusted
import com.lookfirst.domain.open.CanonicalUrl
import com.lookfirst.domain.rdap.RdapClient
import com.lookfirst.domain.rdap.RdapFailure
import com.lookfirst.domain.rdap.RdapOutcome
import com.lookfirst.domain.rdap.RdapPlan
import com.lookfirst.domain.rdap.RdapPlanner
import com.lookfirst.domain.rdap.RdapTarget
import com.lookfirst.domain.rdap.RdapUnavailable
import com.lookfirst.domain.redirect.TraceFailure
import com.lookfirst.domain.redirect.TraceOutcome
import com.lookfirst.domain.usecase.AnalyzeScan
import com.lookfirst.domain.usecase.ApplyDomainAge
import com.lookfirst.domain.usecase.ApplyRedirectTrace
import com.lookfirst.domain.verdict.Verdict
import com.lookfirst.domain.verdict.VerdictEngine
import com.lookfirst.domain.verdict.VerdictLevel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject

sealed interface ResultEvent {
    /** The text is on the clipboard: the screen shows the "Copied" snackbar. */
    data object Copied : ResultEvent

    /** An app was started from an explicit Open: the screen closes, like Cancel, and scanning resumes. */
    data object Opened : ResultEvent

    /** Nothing was started: the screen says why ([unsafe]: the content cannot be opened safely; else no app). */
    data class OpenFailed(val unsafe: Boolean) : ResultEvent

    /** The screen must start this system dialog for a result and report it with [ResultViewModel.onSystemDialogResult]. */
    data class LaunchSystemDialog(val request: SystemDialogRequest) : ResultEvent
}

/**
 * Holds the scanned text for as long as the Result screen is open. The text is never logged and never put in a
 * navigation argument. Once a text is shown, [show] ignores every other one: a second code in front of the
 * camera can never replace the content under the user's finger (threat model T-UI-4).
 */
@HiltViewModel
@Suppress("TooManyFunctions") // one entry per user action of the screen: the contract with the composables
class ResultViewModel @Inject constructor(
    private val clipboard: ClipboardWriter,
    private val actions: ActionExecutor,
    private val analyzeScan: AnalyzeScan,
    private val regionHints: RegionHintProvider,
    private val payloadIds: PayloadIdGenerator,
    private val redirectTracer: RedirectTracer,
    private val applyRedirectTrace: ApplyRedirectTrace,
    private val rdapPlanner: RdapPlanner,
    private val rdapClient: RdapClient,
    private val applyDomainAge: ApplyDomainAge,
    private val networkStatus: NetworkStatus,
    @DefaultDispatcher private val workDispatcher: CoroutineDispatcher,
) : ViewModel() {
    private val mutableState = MutableStateFlow<ResultUiState>(ResultUiState.NoContent)
    val state: StateFlow<ResultUiState> = mutableState.asStateFlow()

    private val eventChannel = Channel<ResultEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    private var scanned: Untrusted? = null
    private var payload: ScanPayload? = null
    private var copyIsSecret = false
    private var parsing: Job? = null
    private var tracing: Job? = null
    private var ageChecking: Job? = null

    /** What the optional redirect check works on: the scanned address in canonical form and the local verdict. */
    private var traceStart: CanonicalUrl? = null
    private var localVerdict: Verdict? = null

    /** What the optional domain-age check asks: the registrable domain and its registry, from the installed lists. */
    private var ageTarget: RdapTarget? = null

    /**
     * The raw answers of the two optional checks. The shown verdict is always `local + trace + age` composed in that
     * fixed order from these, so the order in which the answers arrive changes nothing (no answer overwrites another).
     */
    private var traceResult: TraceOutcome.Completed? = null
    private var ageResult: RdapOutcome? = null

    /** One commit at a time: an answer is composed with the other one as it is when the lock is taken. */
    private val commitLock = Mutex()

    /**
     * Starts showing [content]. Ignored while another content is displayed. [multipleCodes]: the image it comes
     * from held other codes, a line says that only this one is shown (D14).
     */
    fun show(content: Untrusted, multipleCodes: Boolean = false) {
        if (scanned != null) return
        scanned = content
        mutableState.value = ResultUiState.Parsing
        val payloadId = payloadIds.newId()
        parsing = viewModelScope.launch {
            val (parsed, ui) = withContext(workDispatcher) {
                PayloadParser.parse(content.value).let { it to it.toUiState(multipleCodes) }
            }
            payload = (parsed as? ParseResult.Ok)?.payload
            copyIsSecret = payload?.containsSecret() == true
            mutableState.value = ui
            if (parsed is ParseResult.Ok) {
                val verdict = withContext(workDispatcher) { analyze(content.value, payloadId, parsed) }
                val start = (payload as? ScanPayload.Url)?.let { CanonicalUrl.of(it) }
                traceStart = start
                localVerdict = verdict
                val plan = start?.let { withContext(workDispatcher) { planAge(it) } }
                ageTarget = (plan as? RdapPlan.Ready)?.target
                val checks = start?.let {
                    OptionalChecks(
                        httpsOnly = it.scheme != HTTPS,
                        unusualPort = it.port != null && it.port != HTTPS_PORT,
                        online = networkStatus.isOnline(),
                        ageTarget = ageTarget?.let { t -> AgeTarget(t.domain, t.server.host) },
                        ageUnavailable = (plan as? RdapPlan.Unavailable)?.reason,
                    )
                }
                mutableState.update {
                    if (it is ResultUiState.Content) it.copy(verdict = verdict, checks = checks) else it
                }
            }
        }
    }

    /** Never throws: a plan that fails means no registry to ask. */
    @Suppress("TooGenericExceptionCaught")
    private fun planAge(start: CanonicalUrl): RdapPlan = try {
        rdapPlanner.plan(start.host)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (ignored: Exception) {
        RdapPlan.Unavailable(RdapUnavailable.NO_BOOTSTRAP)
    }

    /** Never throws: an analysis that fails is reported as incomplete (CAUTION), not as "no risk signal". */
    @Suppress("TooGenericExceptionCaught")
    private fun analyze(raw: String, payloadId: String, parsed: ParseResult): Verdict = try {
        withHandlerCheck(analyzeScan(raw, payloadId, AnalysisContext(regionHints.regionHint())), parsed)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (ignored: Exception) {
        Verdict(VerdictLevel.CAUTION, listOf(Signal(SignalCode.ANALYSIS_INCOMPLETE)), payloadId)
    }

    /**
     * A link that an app other than the browser would capture (App Link) says so before the user taps Open (T-SCH-12).
     * Open itself targets the default browser; a failed check adds nothing.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun withHandlerCheck(verdict: Verdict, parsed: ParseResult): Verdict {
        val url = (parsed as? ParseResult.Ok)?.payload as? ScanPayload.Url ?: return verdict
        val captured = try {
            actions.hasNonBrowserHandler(url)
        } catch (ignored: RuntimeException) {
            false
        }
        if (!captured || verdict.reasons.any { it.code == SignalCode.NON_BROWSER_HANDLER }) return verdict
        return VerdictEngine.combine(
            verdict.reasons + Signal(SignalCode.NON_BROWSER_HANDLER),
            parsed,
            verdict.payloadId,
        )
    }

    /**
     * Open, only from the button: straight away, or after the "Open anyway?" dialog when the level is SUSPICIOUS or
     * DANGEROUS (ux.md 4.6, D17). Ignored while the analysis runs (the level is not known yet) and for content that
     * has no Open.
     */
    fun onOpen() {
        val current = mutableState.value as? ResultUiState.Content ?: return
        val level = current.verdict?.level ?: return
        if (current.content.openKind == null || current.confirmingSecretCopy || current.opening) return
        if (level == VerdictLevel.DANGEROUS || level == VerdictLevel.SUSPICIOUS) {
            mutableState.value = current.copy(confirmingOpen = true)
        } else {
            startAction()
        }
    }

    fun onConfirmOpen() {
        val current = mutableState.value as? ResultUiState.Content ?: return
        if (!current.confirmingOpen || current.opening) return
        mutableState.value = current.copy(confirmingOpen = false)
        startAction()
    }

    fun onDismissOpen() {
        mutableState.update { if (it is ResultUiState.Content) it.copy(confirmingOpen = false) else it }
    }

    /** [ResultUiState.Content.opening] stops a second tap; it is cleared when nothing was started. */
    private fun startAction() {
        val shown = payload ?: return
        mutableState.update { if (it is ResultUiState.Content) it.copy(opening = true) else it }
        when (val outcome = actions.open(shown)) {
            OpenOutcome.Started -> eventChannel.trySend(ResultEvent.Opened)
            is OpenOutcome.NeedsResult -> eventChannel.trySend(ResultEvent.LaunchSystemDialog(outcome.request))
            OpenOutcome.StartedKeepScreen -> finishOpening(null)
            OpenOutcome.Unsafe -> finishOpening(ResultEvent.OpenFailed(unsafe = true))
            OpenOutcome.NoApp -> finishOpening(ResultEvent.OpenFailed(unsafe = false))
        }
    }

    private fun finishOpening(event: ResultEvent?) {
        mutableState.update { if (it is ResultUiState.Content) it.copy(opening = false) else it }
        event?.let { eventChannel.trySend(it) }
    }

    /** The user answered the system dialog: accepted goes back to scanning, declined keeps the credentials on screen. */
    fun onSystemDialogResult(accepted: Boolean) {
        if (accepted) eventChannel.trySend(ResultEvent.Opened) else finishOpening(null)
    }

    /** Copy: straight away, or after the confirmation dialog when the text contains a secret (ux.md 4.6). */
    fun onCopy() {
        if (mutableState.value !is ResultUiState.Content) return
        if (copyIsSecret) {
            mutableState.update { if (it is ResultUiState.Content) it.copy(confirmingSecretCopy = true) else it }
        } else {
            copyToClipboard()
        }
    }

    fun onConfirmSecretCopy() {
        val current = mutableState.value
        if (current !is ResultUiState.Content || !current.confirmingSecretCopy) return
        mutableState.value = current.copy(confirmingSecretCopy = false)
        copyToClipboard()
    }

    fun onDismissSecretCopy() {
        mutableState.update { if (it is ResultUiState.Content) it.copy(confirmingSecretCopy = false) else it }
    }

    /** "Follow redirects": opens the warning sheet (every time). Offline or http: nothing opens. */
    fun onTraceRequested() {
        val current = mutableState.value as? ResultUiState.Content ?: return
        val checks = current.checks ?: return
        val busy = checks.trace == CheckState.RUNNING || checks.trace == CheckState.DONE
        if (!checks.traceable || busy) return
        val online = networkStatus.isOnline()
        updateChecks { it.copy(online = online, trace = if (online) CheckState.WARNING else it.trace) }
    }

    /** "Continue" in the warning: the one place where a request about the scanned link is made. */
    fun onTraceConfirmed() {
        val checks = (mutableState.value as? ResultUiState.Content)?.checks ?: return
        val start = traceStart
        if (checks.trace != CheckState.WARNING || start == null) return
        updateChecks { it.copy(trace = CheckState.RUNNING) }
        tracing = viewModelScope.launch {
            val outcome = redirectTracer.trace(start)
            if (outcome !is TraceOutcome.Completed) {
                val busy = (outcome as? TraceOutcome.Failed)?.reason == TraceFailure.BUSY
                updateChecks { it.copy(trace = if (busy) CheckState.BUSY else CheckState.FAILED) }
                return@launch
            }
            commit(trace = outcome) { it.copy(trace = CheckState.DONE) }
        }
    }

    /** "Check domain age": opens the warning sheet (every time). Offline, or no registry to ask: nothing opens. */
    fun onAgeRequested() {
        val current = mutableState.value as? ResultUiState.Content ?: return
        val checks = current.checks ?: return
        val busy = checks.age == CheckState.RUNNING || checks.age == CheckState.DONE
        if (checks.ageTarget == null || ageTarget == null || busy) return
        val online = networkStatus.isOnline()
        updateChecks { it.copy(online = online, age = if (online) CheckState.WARNING else it.age) }
    }

    /** "Continue" in the warning: the one place where a request about the scanned domain is made. */
    fun onAgeConfirmed() {
        val checks = (mutableState.value as? ResultUiState.Content)?.checks ?: return
        val target = ageTarget
        if (checks.age != CheckState.WARNING || target == null) return
        updateChecks { it.copy(age = CheckState.RUNNING) }
        ageChecking = viewModelScope.launch {
            val outcome = rdapClient.lookup(target)
            if (outcome is RdapOutcome.Failed) {
                val busy = outcome.reason == RdapFailure.BUSY
                updateChecks { it.copy(age = if (busy) CheckState.BUSY else CheckState.FAILED) }
                return@launch
            }
            commit(age = outcome) { it.copy(age = CheckState.DONE, ageOutcome = outcome) }
        }
    }

    /** Cancel in the warning or while loading: nothing is kept, no verdict changes. */
    fun onAgeCancelled() {
        ageChecking?.cancel()
        ageChecking = null
        updateChecks {
            if (it.age == CheckState.WARNING || it.age == CheckState.RUNNING) it.copy(age = CheckState.IDLE) else it
        }
    }

    /**
     * Records one answer and shows the verdict composed from both answers. Serialised: the composition (off the main
     * thread) reads the other answer as it is under the lock, and the answers and the state are written together
     * without a suspension, so a cancelled check leaves nothing behind and no update is lost.
     */
    private suspend fun commit(
        trace: TraceOutcome.Completed? = null,
        age: RdapOutcome? = null,
        mark: (OptionalChecks) -> OptionalChecks,
    ) {
        commitLock.withLock {
            val local = localVerdict ?: return
            val composed = withContext(workDispatcher) { compose(local, trace ?: traceResult, age ?: ageResult) }
            if (trace != null) traceResult = trace
            if (age != null) ageResult = age
            mutableState.update {
                if (it !is ResultUiState.Content || it.checks == null) return@update it
                it.copy(verdict = composed, checks = mark(it.checks).announcing(it, composed))
            }
        }
    }

    /** local, then the redirect trace, then the domain age: always this order. Each step only adds. */
    private fun compose(local: Verdict, trace: TraceOutcome.Completed?, age: RdapOutcome?): Verdict {
        val traced = if (trace == null) local else merge(local, trace)
        return if (age == null) traced else mergeAge(traced, age)
    }

    /**
     * Sets the level announcement when [updated] is not at the level [shown] had; otherwise an earlier announcement
     * stays as it is (TalkBack reads a changed text once, an unchanged one is not read again).
     */
    private fun OptionalChecks.announcing(shown: ResultUiState.Content, updated: Verdict): OptionalChecks {
        val before = shown.verdict?.level
        return if (before != null && updated.level != before) copy(levelChangedTo = updated.level) else this
    }

    /** Never throws: a merge that fails keeps the verdict as it was. */
    @Suppress("TooGenericExceptionCaught")
    private fun mergeAge(base: Verdict, outcome: RdapOutcome): Verdict = try {
        applyDomainAge(base, outcome)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (ignored: Exception) {
        base
    }

    /** Cancel in the warning or while loading: nothing is kept, no verdict changes. */
    fun onTraceCancelled() {
        tracing?.cancel()
        tracing = null
        updateChecks {
            if (it.trace == CheckState.WARNING ||
                it.trace == CheckState.RUNNING
            ) {
                it.copy(trace = CheckState.IDLE)
            } else {
                it
            }
        }
    }

    /** Never throws: a merge that fails keeps the local verdict and reports the check as failed. */
    @Suppress("TooGenericExceptionCaught")
    private fun merge(base: Verdict, outcome: TraceOutcome.Completed): Verdict = try {
        applyRedirectTrace(base, outcome.trace, AnalysisContext(regionHints.regionHint()))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (ignored: Exception) {
        base
    }

    private fun updateChecks(change: (OptionalChecks) -> OptionalChecks) {
        mutableState.update {
            if (it is ResultUiState.Content && it.checks != null) it.copy(checks = change(it.checks)) else it
        }
    }

    /** Cancel / back: forget the content. */
    fun clear() {
        parsing?.cancel()
        parsing = null
        tracing?.cancel()
        tracing = null
        ageChecking?.cancel()
        ageChecking = null
        ageTarget = null
        traceResult = null
        ageResult = null
        traceStart = null
        localVerdict = null
        scanned = null
        payload = null
        copyIsSecret = false
        mutableState.value = ResultUiState.NoContent
    }

    private fun copyToClipboard() {
        val text = scanned?.value ?: return
        clipboard.copy(text, sensitive = copyIsSecret)
        eventChannel.trySend(ResultEvent.Copied)
    }
}

private const val HTTPS = "https"
private const val HTTPS_PORT = 443
