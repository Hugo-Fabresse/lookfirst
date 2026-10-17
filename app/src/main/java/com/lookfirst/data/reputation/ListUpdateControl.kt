package com.lookfirst.data.reputation

import com.lookfirst.data.platform.AppPreferences
import com.lookfirst.data.platform.LastAttempt
import com.lookfirst.data.platform.UpdateOutcome
import com.lookfirst.data.platform.UpdateScheduler
import com.lookfirst.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val MANUAL_COOLDOWN_MILLIS = 15 * 60_000L

/** What the settings screen shows about the lists. [entries] and [ageDays] are null while nothing is loaded. */
data class ListsStatus(
    val entries: Long?,
    val ageDays: Int?,
    val autoUpdate: Boolean,
    val lastAttempt: LastAttempt?,
    val updating: Boolean,
)

/** The settings and first-launch view of list updates. Nothing here touches a scanned address. */
interface ListUpdateControl {
    val status: StateFlow<ListsStatus>

    /** Re-reads the installed list, the preferences and the last attempt. */
    fun refresh()

    fun setAutoUpdate(enabled: Boolean)

    /** "Update now": one run on any connected network, after an explicit tap. */
    fun updateNow()

    /** The first-launch button: remembers the choice, then applies it (scheduling only starts here). */
    fun completeFirstLaunch(autoUpdate: Boolean)

    /** Applies the stored choice to the daily job (called at every start; the job is kept if it already exists). */
    fun applySchedule()
}

@Singleton
class DefaultListUpdateControl internal constructor(
    private val store: ListStore,
    private val preferences: AppPreferences,
    private val scheduler: UpdateScheduler,
    private val runner: ListUpdateRunner,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
) : ListUpdateControl {
    @Inject
    internal constructor(
        store: ListStore,
        preferences: AppPreferences,
        scheduler: UpdateScheduler,
        runner: ListUpdateRunner,
        @ApplicationScope scope: CoroutineScope,
    ) : this(store, preferences, scheduler, runner, scope, System::currentTimeMillis)

    private val mutableStatus = MutableStateFlow(read())
    override val status: StateFlow<ListsStatus> = mutableStatus.asStateFlow()

    init {
        scope.launch { runner.running.collect { refresh() } }
    }

    override fun refresh() {
        mutableStatus.value = read()
    }

    private fun read(): ListsStatus {
        val snapshot = store.snapshot()
        return ListsStatus(
            entries = snapshot?.entries,
            ageDays = snapshot?.ageDays,
            autoUpdate = preferences.autoUpdate(),
            lastAttempt = preferences.lastAttempt(),
            updating = runner.running.value,
        )
    }

    override fun setAutoUpdate(enabled: Boolean) {
        preferences.setAutoUpdate(enabled)
        applySchedule()
        refresh()
    }

    override fun updateNow() {
        if (runner.running.value || withinCooldown()) return
        scope.launch {
            runner.runNow()
            refresh()
        }
    }

    /** A successful or refused attempt less than 15 minutes ago is not repeated; a failed one can be retried. */
    private fun withinCooldown(): Boolean {
        val last = preferences.lastAttempt() ?: return false
        return last.outcome != UpdateOutcome.FAILED &&
            last.outcome != UpdateOutcome.OFFLINE &&
            clock() - last.timeMillis in 0 until MANUAL_COOLDOWN_MILLIS
    }

    override fun completeFirstLaunch(autoUpdate: Boolean) {
        preferences.setAutoUpdate(autoUpdate)
        preferences.setFirstLaunchDone()
        applySchedule()
        refresh()
    }

    /** Daily job on when the first-launch screen was passed and the toggle is on; otherwise off. */
    override fun applySchedule() {
        if (preferences.firstLaunchDone() && preferences.autoUpdate()) {
            scheduler.schedulePeriodic()
        } else {
            scheduler.cancelPeriodic()
        }
    }
}
