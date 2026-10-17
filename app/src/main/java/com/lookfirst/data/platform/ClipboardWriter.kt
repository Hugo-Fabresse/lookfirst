package com.lookfirst.data.platform

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Writes to the system clipboard. Only called after an explicit tap on Copy (threat model T-SCH-11). */
interface ClipboardWriter {
    /** Copies [text] unchanged. A [sensitive] text is flagged so that Android hides its preview (API 33+). */
    fun copy(text: String, sensitive: Boolean)

    /**
     * Empties the clipboard if, and only if, it still holds the sensitive text this writer copied (API 28+).
     * Does nothing when the user has copied something else since.
     */
    fun clearSensitive()
}

class AndroidClipboardWriter @Inject constructor(@ApplicationContext private val context: Context) :
    ClipboardWriter {
    private var lastSensitive: String? = null

    private val manager: ClipboardManager?
        get() = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    override fun copy(text: String, sensitive: Boolean) {
        val clip = ClipData.newPlainText("", text)
        if (sensitive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        manager?.setPrimaryClip(clip)
        lastSensitive = if (sensitive) text else null
    }

    override fun clearSensitive() {
        val secret = lastSensitive ?: return
        lastSensitive = null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val clipboard = manager ?: return
        val current = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        if (current == secret) clipboard.clearPrimaryClip()
    }
}
