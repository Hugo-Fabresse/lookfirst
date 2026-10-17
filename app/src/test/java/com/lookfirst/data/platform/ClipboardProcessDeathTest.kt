package com.lookfirst.data.platform

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** The pending label survives a process death (audit S-10): a new writer on the same preferences clears the secret. */
@RunWith(RobolectricTestRunner::class)
class ClipboardProcessDeathTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private fun newProcess() = AndroidClipboardWriter(context)

    private fun clipText(): String? = manager.primaryClip?.getItemAt(0)?.text?.toString()

    private fun storedLabel(): String? = context.getSharedPreferences("lookfirst_pending_clip", Context.MODE_PRIVATE)
        .getString("label", null)

    @Test
    fun aNewProcessClearsTheSecretWhenFocusReturns() {
        newProcess().copy("hunter2", sensitive = true)
        val reborn = newProcess()
        assertEquals("hunter2", clipText())
        reborn.onWindowFocused()
        assertEquals(null, clipText())
        assertEquals(null, storedLabel())
    }

    @Test
    fun aForeignClipSetAfterwardsIsNeverCleared() {
        newProcess().copy("hunter2", sensitive = true)
        manager.setPrimaryClip(ClipData.newPlainText("", "user data"))
        val reborn = newProcess()
        reborn.onWindowFocused()
        assertEquals("user data", clipText())
        assertEquals(null, storedLabel())
    }

    @Test
    fun theStoredLabelIsNeverTheSecretAndIsForgottenAfterUse() {
        val writer = newProcess()
        writer.copy("hunter2", sensitive = true)
        val label = storedLabel()
        assertTrue(!label.isNullOrEmpty() && !label.contains("hunter2"))
        writer.copy("plain", sensitive = false)
        assertEquals(null, storedLabel())
    }
}
