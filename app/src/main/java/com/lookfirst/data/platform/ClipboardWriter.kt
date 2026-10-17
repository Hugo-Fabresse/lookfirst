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

/**
 * `ClipDescription.EXTRA_IS_SENSITIVE` (API 33+) has the same value on API 24+, where `setExtras` exists: it is set
 * from API 24 with this literal. Whether the flag is honoured (preview, history) depends on the keyboard / system.
 */
private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

private fun markSensitive(clip: ClipData) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
    val key = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ClipDescription.EXTRA_IS_SENSITIVE
    } else {
        EXTRA_IS_SENSITIVE
    }
    clip.description.extras = PersistableBundle().apply { putBoolean(key, true) }
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

/**
 * Keeps the random label of the pending sensitive copy across a process death (threat model T-SCH-11). Only the
 * label is stored, never the secret. Writes are synchronous: the process may be killed right after the copy.
 */
internal interface PendingLabelStore {
    fun load(): String?

    fun save(label: String?)
}

private class InMemoryPendingLabelStore : PendingLabelStore {
    private var label: String? = null

    override fun load(): String? = label

    override fun save(label: String?) {
        this.label = label
    }
}

/** Private `SharedPreferences` file, excluded from backup and transfer like every other file (data_extraction_rules.xml). */
private class SharedPreferencesPendingLabelStore(context: Context) : PendingLabelStore {
    private val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun load(): String? = preferences.getString(KEY_LABEL, null)

    override fun save(label: String?) {
        val editor = preferences.edit()
        if (label == null) editor.remove(KEY_LABEL) else editor.putString(KEY_LABEL, label)
        editor.commit()
    }

    private companion object {
        const val FILE = "lookfirst_pending_clip"
        const val KEY_LABEL = "label"
    }
}

@Singleton
class AndroidClipboardWriter internal constructor(
    private val store: ClipStore,
    private val labels: PendingLabelStore = InMemoryPendingLabelStore(),
) : ClipboardWriter {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        SystemClipStore(context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager),
        SharedPreferencesPendingLabelStore(context),
    )

    /**
     * Random label of the pending sensitive copy. The secret itself is not kept here. A label found in [labels] at
     * construction comes from a previous process (killed since the copy): the app was necessarily left.
     */
    private var pendingLabel: String? = labels.load()
    private var leftSinceCopy = pendingLabel != null

    private fun forget() {
        pendingLabel = null
        leftSinceCopy = false
        labels.save(null)
    }

    override fun copy(text: String, sensitive: Boolean) {
        val label = if (sensitive) UUID.randomUUID().toString() else ""
        val clip = ClipData.newPlainText(label, text)
        if (sensitive) {
            // Saved before the clip is set: a death between the two must not leave a secret without its label.
            labels.save(label)
            markSensitive(clip)
        }
        store.set(clip)
        if (sensitive) {
            pendingLabel = label
            leftSinceCopy = false
        } else if (pendingLabel != null) {
            forget()
        }
    }

    override fun onAppStopped() {
        if (pendingLabel != null) leftSinceCopy = true
    }

    override fun onWindowFocused() {
        val label = pendingLabel ?: return
        if (!leftSinceCopy) return
        // From API 29 hasPrimaryClip() is false without the focus: that is "cannot know yet", not "empty".
        if (!store.hasClip()) return
        // Unreadable (no focus yet): keep waiting. A check that was really made ends the wait.
        val current = store.label() ?: return
        forget()
        if (current.toString() == label) store.clear()
    }
}
