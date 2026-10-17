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
    private val downloader: ListDownloader,
    private val preferences: AppPreferences,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long,
) {
    @Inject
    internal constructor(
        downloader: ListDownloader,
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
            val outcome = withContext(io) { downloader.download() }
            val result = toResult(outcome)
            result.outcome?.let { preferences.recordAttempt(LastAttempt(clock(), it)) }
            return result
        } finally {
            mutableRunning.value = false
            gate.unlock()
        }
    }

    private fun toResult(outcome: DownloadOutcome): RunResult = when (outcome) {
        is DownloadOutcome.Installed -> RunResult(UpdateOutcome.UPDATED, retry = false)
        DownloadOutcome.UpToDate -> RunResult(UpdateOutcome.UP_TO_DATE, retry = false)
        DownloadOutcome.AlreadyRejected,
        is DownloadOutcome.Rejected,
        -> RunResult(UpdateOutcome.REJECTED, retry = false)
        is DownloadOutcome.Failed ->
            RunResult(if (outcome.offline) UpdateOutcome.OFFLINE else UpdateOutcome.FAILED, outcome.retry)
    }
}
