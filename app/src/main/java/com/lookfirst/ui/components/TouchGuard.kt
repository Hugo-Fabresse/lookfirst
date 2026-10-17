package com.lookfirst.ui.components

import android.os.Build
import android.view.MotionEvent
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

/**
 * Tapjacking guard (threat model T-UI-3). `filterTouchesWhenObscured` only covers a window that is fully covered;
 * a window that is only partly covered (API 29+) reports `FLAG_WINDOW_IS_PARTIALLY_OBSCURED` on its touches.
 * Compose gives no access to the flags of a touch, so the activity and each dialog window report them here
 * while they dispatch the touch, and the buttons ignore a click that is being dispatched under that flag.
 * The flag does not exist before API 29: nothing is detected there (residual risk in the threat model).
 */
object TouchGuard {
    @Volatile
    private var obscured = false

    /** True while a touch that arrives under an overlay is being dispatched. */
    val isObscured: Boolean get() = obscured

    /** Runs [dispatch] with the obscured state of the touch being dispatched; restores it afterwards. */
    fun <T> track(partiallyObscured: Boolean, dispatch: () -> T): T {
        val previous = obscured
        obscured = partiallyObscured
        try {
            return dispatch()
        } finally {
            obscured = previous
        }
    }
}

/** True when another window covers part of ours at the moment of the touch. */
fun MotionEvent.isPartiallyObscured(): Boolean = flags and MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED != 0

/** [action] as a click handler that does nothing for a touch that arrives while an overlay covers the window. */
fun guarded(action: () -> Unit): () -> Unit = {
    if (!TouchGuard.isObscured) action()
}

private class GuardedWindowCallback(private val delegate: Window.Callback) : Window.Callback by delegate {
    override fun dispatchTouchEvent(event: MotionEvent): Boolean =
        TouchGuard.track(event.isPartiallyObscured()) { delegate.dispatchTouchEvent(event) }
}

/**
 * Place inside the content of a Compose dialog: a dialog is its own window, so the protection of MainActivity
 * does not reach it. Drops touches while fully obscured, reports partial obscuring to [TouchGuard] and, on API 31+,
 * hides non-system overlays.
 */
@Composable
fun HardenDialogWindow() {
    val view = LocalView.current
    SideEffect {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
        window.decorView.filterTouchesWhenObscured = true
        val callback = window.callback
        if (callback != null && callback !is GuardedWindowCallback) window.callback = GuardedWindowCallback(callback)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) hideOverlays(window)
    }
}

/** Needs the HIDE_OVERLAY_WINDOWS permission (manifest); without it the other protections above still apply. */
private fun hideOverlays(window: Window) {
    try {
        window.setHideOverlayWindows(true)
    } catch (ignored: SecurityException) {
        // Permission missing in this build or environment: nothing more can be done for this window.
    }
}
