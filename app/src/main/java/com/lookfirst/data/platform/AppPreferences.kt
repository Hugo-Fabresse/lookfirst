package com.lookfirst.data.platform

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** How the last list update attempt ended (settings screen). Never carries a server-provided string. */
enum class UpdateOutcome { UPDATED, UP_TO_DATE, FAILED, REJECTED, OFFLINE }

class LastAttempt(val timeMillis: Long, val outcome: UpdateOutcome)

/**
 * The few user choices and facts the app remembers outside the reputation store: automatic update on/off, the last
 * update attempt, whether the first-launch screen was seen. Nothing about a scan is ever stored here.
 */
interface AppPreferences {
    /** On by default (ux.md 3.1). */
    fun autoUpdate(): Boolean

    fun setAutoUpdate(enabled: Boolean)

    fun lastAttempt(): LastAttempt?

    fun recordAttempt(attempt: LastAttempt)

    fun firstLaunchDone(): Boolean

    fun setFirstLaunchDone()
}

/** `SharedPreferences`, excluded from every backup and transfer by `data_extraction_rules.xml`. */
class SharedPreferencesAppPreferences @Inject constructor(@ApplicationContext context: Context) : AppPreferences {
    private val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun autoUpdate(): Boolean = preferences.getBoolean(KEY_AUTO_UPDATE, true)

    override fun setAutoUpdate(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_AUTO_UPDATE, enabled).apply()
    }

    override fun lastAttempt(): LastAttempt? {
        val time = preferences.getLong(KEY_ATTEMPT_TIME, NONE)
        val outcome = UpdateOutcome.entries.firstOrNull { it.name == preferences.getString(KEY_ATTEMPT_OUTCOME, null) }
        return if (time != NONE && outcome != null) LastAttempt(time, outcome) else null
    }

    override fun recordAttempt(attempt: LastAttempt) {
        preferences.edit()
            .putLong(KEY_ATTEMPT_TIME, attempt.timeMillis)
            .putString(KEY_ATTEMPT_OUTCOME, attempt.outcome.name)
            .apply()
    }

    override fun firstLaunchDone(): Boolean = preferences.getBoolean(KEY_FIRST_LAUNCH_DONE, false)

    override fun setFirstLaunchDone() {
        preferences.edit().putBoolean(KEY_FIRST_LAUNCH_DONE, true).apply()
    }

    private companion object {
        const val FILE = "lookfirst_preferences"
        const val KEY_AUTO_UPDATE = "auto_update"
        const val KEY_ATTEMPT_TIME = "attempt_time"
        const val KEY_ATTEMPT_OUTCOME = "attempt_outcome"
        const val KEY_FIRST_LAUNCH_DONE = "first_launch_done"
        const val NONE = -1L
    }
}
