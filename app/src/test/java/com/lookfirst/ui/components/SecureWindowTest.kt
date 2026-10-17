package com.lookfirst.ui.components

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SecureWindowTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun secure(): Boolean =
        composeRule.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

    @Test
    fun theWindowIsSecureOnlyWhileEnabled() {
        var enabled by mutableStateOf(false)
        composeRule.setContent { SecureWindow(enabled) }
        assertEquals(false, secure())
        enabled = true
        composeRule.waitForIdle()
        assertEquals(true, secure())
        enabled = false
        composeRule.waitForIdle()
        assertEquals(false, secure())
    }

    @Test
    fun leavingTheScreenRestoresTheWindow() {
        var shown by mutableStateOf(true)
        composeRule.setContent { if (shown) SecureWindow(enabled = true) }
        composeRule.waitForIdle()
        assertEquals(true, secure())
        shown = false
        composeRule.waitForIdle()
        assertEquals(false, secure())
    }
}
