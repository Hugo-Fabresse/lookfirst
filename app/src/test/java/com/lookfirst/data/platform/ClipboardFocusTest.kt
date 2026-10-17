package com.lookfirst.data.platform

import android.content.ClipData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The focus rules (API 29+: the clipboard is unreadable without the window focus), with a fake clipboard. */
@RunWith(RobolectricTestRunner::class)
class ClipboardFocusTest {
    private class FakeStore : ClipStore {
        var clip: ClipData? = null
        var readable = true
        var clears = 0

        override fun set(clip: ClipData) {
            this.clip = clip
        }

        override fun hasClip() = readable && clip != null

        override fun label(): CharSequence? = if (readable) clip?.description?.label else null

        override fun clear() {
            clip = null
            clears++
        }
    }

    private val store = FakeStore()
    private val writer = AndroidClipboardWriter(store)

    @Test
    fun staysPendingWhileTheClipboardIsUnreadableThenClearsWhenFocusReturns() {
        writer.copy("hunter2", sensitive = true)
        writer.onAppStopped()
        store.readable = false
        writer.onWindowFocused()
        writer.onWindowFocused()
        assertEquals(0, store.clears)
        assertTrue(store.clip != null)

        store.readable = true
        writer.onWindowFocused()
        assertEquals(1, store.clears)
    }

    @Test
    fun aVerifiedForeignClipEndsTheWaitWithoutBeingTouched() {
        writer.copy("hunter2", sensitive = true)
        writer.onAppStopped()
        store.clip = ClipData.newPlainText("other", "mine")
        writer.onWindowFocused()
        assertEquals(0, store.clears)
        store.clip = ClipData.newPlainText("another", "later")
        writer.onWindowFocused()
        assertEquals(0, store.clears)
    }

    @Test
    fun focusBeforeLeavingTheAppDoesNothing() {
        writer.copy("hunter2", sensitive = true)
        writer.onWindowFocused()
        assertEquals(0, store.clears)
    }

    @Test
    fun aNewPlainCopyCancelsThePendingClear() {
        writer.copy("hunter2", sensitive = true)
        writer.copy("plain", sensitive = false)
        writer.onAppStopped()
        writer.onWindowFocused()
        assertEquals(0, store.clears)
    }

    @Test
    fun noFocusYetThenFocusClearsTheSecret() {
        writer.copy("hunter2", sensitive = true)
        writer.onAppStopped()
        store.readable = false
        writer.onWindowFocused()
        assertEquals(0, store.clears)
        store.readable = true
        writer.onWindowFocused()
        assertEquals(1, store.clears)
    }
}
