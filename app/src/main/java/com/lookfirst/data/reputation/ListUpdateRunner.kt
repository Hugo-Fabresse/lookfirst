package com.lookfirst.data.reputation

import com.lookfirst.data.platform.AppPreferences
import com.lookfirst.data.platform.LastAttempt
import com.lookfirst.data.platform.UpdateOutcome
import com.lookfirst.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** What one run did. [outcome] is null when nothing ran (another update is in progress, or it is switched off). */
class RunResult(val outcome: UpdateOutcome?, val retry: Boolean)

/**
 * The one place that runs a list update, for the daily WorkManager job and for "Update now" alike. A single
 * download at a time (a second request while one runs does nothing), which also keeps the store's candidate
 * directory of the running download safe. Records the outcome of every attempt for the settings screen.
 */
@Singleton
class ListUpdateRunner internal constructor(
    private val downloader: PublisherDownloader,
    private val preferences: AppPreferences,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long,
) {
    @Inject
    internal constructor(
        downloader: PublisherDownloader,
        preferences: AppPreferences,
        @IoDispatcher io: CoroutineDispatcher,
    ) : this(downloader, preferences, io, System::currentTimeMillis)

    private val gate = Mutex()
    private val mutableRunning = MutableStateFlow(false)
    val running: StateFlow<Boolean> = mutableRunning.asStateFlow()

    /** The daily job. Does nothing if the user switched automatic update off after it was scheduled. */
    suspend fun runAutomatic(): RunResult = if (preferences.autoUpdate()) run() else RunResult(null, retry = false)

    /** "Update now": same run, any connected network (the daily job's unmetered rule is a scheduling constraint). */
    suspend fun runNow(): RunResult = run()

    private suspend fun run(): RunResult {
        if (!gate.tryLock()) return RunResult(null, retry = false)
        try {
            mutableRunning.value = true
            val report = withContext(io) { downloader.download() }
            val result = toResult(report)
            result.outcome?.let { preferences.recordAttempt(LastAttempt(clock(), it)) }
            return result
        } finally {
            mutableRunning.value = false
            gate.unlock()
        }
    }

    /**
     * One outcome for the settings screen: any installed source is an update; all current is "up to date"; a network
     * trouble is "offline" only if every failure was; a refusal alone is "refused". A retry is asked for whenever
     * some source failed in a way that trying again can fix.
     */
    private fun toResult(report: UpdateReport): RunResult {
        val outcomes = report.outcomes
        val failures = outcomes.filterIsInstance<SourceOutcome.Failed>()
        val retry = failures.any { it.retry }
        val outcome = when {
            outcomes.isEmpty() -> UpdateOutcome.FAILED
            outcomes.any { it is SourceOutcome.Installed } -> UpdateOutcome.UPDATED
            outcomes.all { it == SourceOutcome.UpToDate } -> UpdateOutcome.UP_TO_DATE
            failures.isNotEmpty() ->
                if (failures.all { it.offline } &&
                    failures.size == outcomes.size
                ) {
                    UpdateOutcome.OFFLINE
                } else {
                    UpdateOutcome.FAILED
                }
            else -> UpdateOutcome.REJECTED
        }
        return RunResult(outcome, retry)
    }
}
