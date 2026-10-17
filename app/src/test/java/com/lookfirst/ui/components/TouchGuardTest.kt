package com.lookfirst.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchGuardTest {
    @Test
    fun aClickDuringAnObscuredTouchIsIgnored() {
        var clicks = 0
        val onClick = guarded { clicks++ }
        TouchGuard.track(partiallyObscured = true) { onClick() }
        assertEquals(0, clicks)
    }

    @Test
    fun aClickDuringAnUnobscuredTouchGoesThrough() {
        var clicks = 0
        val onClick = guarded { clicks++ }
        TouchGuard.track(partiallyObscured = false) { onClick() }
        assertEquals(1, clicks)
    }

    @Test
    fun aClickWithoutATouchIsNotBlocked() {
        var clicks = 0
        guarded { clicks++ }()
        assertEquals(1, clicks)
    }

    @Test
    fun theStateIsRestoredAfterTheTouchEvenOnFailure() {
        assertFalse(TouchGuard.isObscured)
        TouchGuard.track(partiallyObscured = true) { assertTrue(TouchGuard.isObscured) }
        assertFalse(TouchGuard.isObscured)
        runCatching { TouchGuard.track(partiallyObscured = true) { error("boom") } }
        assertFalse(TouchGuard.isObscured)
    }
}
