package com.lookfirst.data.platform

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lookfirst.data.reputation.ListUpdateRunner
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** The daily background update of the reputation lists. A seam over WorkManager. */
interface UpdateScheduler {
    /** Daily, unmetered network, battery not low. Idempotent: an existing schedule is kept. */
    fun schedulePeriodic()

    fun cancelPeriodic()
}

class WorkManagerUpdateScheduler @Inject constructor(@ApplicationContext private val context: Context) :
    UpdateScheduler {
    override fun schedulePeriodic() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .setRequiresBatteryNotLow(true)
            .build()
        val request = PeriodicWorkRequestBuilder<ListUpdateWorker>(
            REPEAT_HOURS,
            TimeUnit.HOURS,
            FLEX_HOURS,
            TimeUnit.HOURS,
        )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_HOURS, TimeUnit.HOURS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    override fun cancelPeriodic() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    private companion object {
        const val WORK_NAME = "reputation-list-update"
        const val REPEAT_HOURS = 24L
        const val FLEX_HOURS = 6L
        const val BACKOFF_HOURS = 1L
    }
}

/**
 * Runs one update through [ListUpdateRunner] (single download at a time, shared with "Update now"). Network,
 * 5xx and storage errors retry with WorkManager's exponential backoff, at most [MAX_ATTEMPTS] attempts per period;
 * a refused list is a success (the same bytes cannot become valid). Not exported: only WorkManager starts it.
 */
class ListUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun runner(): ListUpdateRunner
    }

    override suspend fun doWork(): Result {
        val runner = EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java).runner()
        val result = runner.runAutomatic()
        return if (shouldRetry(result.retry, runAttemptCount)) Result.retry() else Result.success()
    }

    companion object {
        const val MAX_ATTEMPTS = 4

        /** [runAttemptCount] counts the earlier attempts of this period: the fourth attempt is the last. */
        internal fun shouldRetry(retry: Boolean, runAttemptCount: Int): Boolean =
            retry && runAttemptCount + 1 < MAX_ATTEMPTS
    }
}
