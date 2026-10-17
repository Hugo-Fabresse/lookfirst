package com.lookfirst.data.platform

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ClipboardWriterTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val writer = AndroidClipboardWriter(context)

    private fun clipText(): String? = manager.primaryClip?.getItemAt(0)?.text?.toString()

    @Test
    fun copiesTheExactTextWithoutTheSensitiveFlag() {
        val text = "https://example.com/a?b=1&c=é"
        writer.copy(text, sensitive = false)
        assertEquals(text, clipText())
        assertNull(manager.primaryClip?.description?.extras)
    }

    @Test
    fun aSecretIsFlaggedSensitiveAndCarriesARandomLabel() {
        writer.copy("hunter2", sensitive = true)
        assertEquals("hunter2", clipText())
        val extras = manager.primaryClip?.description?.extras
        assertNotNull(extras)
        assertTrue(extras!!.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE))
        val first = manager.primaryClipDescription?.label.toString()
        writer.copy("hunter2", sensitive = true)
        assertTrue(first.isNotEmpty())
        assertFalse(first == manager.primaryClipDescription?.label.toString())
    }

    @Test
    fun clearsTheSecretOnlyAfterTheAppWasLeftAndFocusCameBack() {
        writer.copy("hunter2", sensitive = true)
        writer.onWindowFocused()
        assertEquals("hunter2", clipText())
        writer.onAppStopped()
        writer.onWindowFocused()
        assertNull(clipText())
    }

    @Test
    fun keepsWhatTheUserCopiedAfterwards() {
        writer.copy("hunter2", sensitive = true)
        manager.setPrimaryClip(ClipData.newPlainText("", "something else"))
        writer.onAppStopped()
        writer.onWindowFocused()
        assertEquals("something else", clipText())
    }

    @Test
    fun neverClearsAPlainCopy() {
        writer.copy("not a secret", sensitive = false)
        writer.onAppStopped()
        writer.onWindowFocused()
        assertEquals("not a secret", clipText())
    }
}
