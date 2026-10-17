package com.lookfirst.data.platform

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Writes to the system clipboard. Only called after an explicit tap on Copy (threat model T-SCH-11). */
interface ClipboardWriter {
    /** Copies [text] unchanged. A [sensitive] text is flagged so that Android hides its preview (API 33+). */
    fun copy(text: String, sensitive: Boolean)

    /** The app went to the background (activity stopped): a pending sensitive copy may now be cleared. */
    fun onAppStopped()

    /**
     * The app window got the focus back. If, since the app was left, the clipboard is readable and still carries
     * the label of our sensitive copy, it is emptied. The text of a clipboard that is not ours is never read.
     * Before API 29 the clipboard is always readable; from API 29 only with the focus, hence this call.
     */
    fun onWindowFocused()
}

/** The few clipboard operations the guard needs; a seam so the focus rules can be tested. */
internal interface ClipStore {
    fun set(clip: ClipData)

    fun hasClip(): Boolean

    /** Label of the primary clip, or null when it cannot be read (no focus) or has none. Never its text. */
    fun label(): CharSequence?

    fun clear()
}

private class SystemClipStore(private val manager: ClipboardManager?) : ClipStore {
    override fun set(clip: ClipData) {
        manager?.setPrimaryClip(clip)
    }

    override fun hasClip(): Boolean = manager?.hasPrimaryClip() == true

    override fun label(): CharSequence? = manager?.primaryClipDescription?.label

    override fun clear() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            manager?.clearPrimaryClip()
        } else {
            manager?.setPrimaryClip(ClipData.newPlainText("", ""))
        }
    }
}

@Singleton
class AndroidClipboardWriter internal constructor(private val store: ClipStore) : ClipboardWriter {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        SystemClipStore(context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager),
    )

    /** Random label of the pending sensitive copy. The secret itself is not kept here. */
    private var pendingLabel: String? = null
    private var leftSinceCopy = false

    override fun copy(text: String, sensitive: Boolean) {
        val label = if (sensitive) UUID.randomUUID().toString() else ""
        val clip = ClipData.newPlainText(label, text)
        if (sensitive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        store.set(clip)
        pendingLabel = if (sensitive) label else null
        leftSinceCopy = false
    }

    override fun onAppStopped() {
        if (pendingLabel != null) leftSinceCopy = true
    }

    override fun onWindowFocused() {
        val label = pendingLabel ?: return
        if (!leftSinceCopy) return
        if (!store.hasClip()) {
            pendingLabel = null
            return
        }
        // Unreadable (no focus yet): keep waiting. A check that was really made ends the wait.
        val current = store.label() ?: return
        pendingLabel = null
        leftSinceCopy = false
        if (current.toString() == label) store.clear()
    }
}
