package com.lookfirst.data.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AppPreferencesTest {
    private fun preferences() = SharedPreferencesAppPreferences(RuntimeEnvironment.getApplication())

    @Test
    fun defaultsAreAutomaticUpdateOnNoAttemptAndFirstLaunchNotDone() {
        val p = preferences()
        assertTrue(p.autoUpdate())
        assertNull(p.lastAttempt())
        assertFalse(p.firstLaunchDone())
    }

    @Test
    fun choicesAndTheLastAttemptSurviveANewInstance() {
        preferences().apply {
            setAutoUpdate(false)
            recordAttempt(LastAttempt(1234L, UpdateOutcome.OFFLINE))
            setFirstLaunchDone()
        }
        val again = preferences()
        assertFalse(again.autoUpdate())
        assertEquals(1234L, again.lastAttempt()?.timeMillis)
        assertEquals(UpdateOutcome.OFFLINE, again.lastAttempt()?.outcome)
        assertTrue(again.firstLaunchDone())
    }
}
