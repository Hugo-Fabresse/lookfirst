package com.lookfirst.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * While [enabled], the window cannot be captured: no screenshot, no screen recording, and a blank thumbnail in
 * the recent apps list (`FLAG_SECURE`, plus `setRecentsScreenshotEnabled(false)` on API 33+). Used while a content
 * that holds a secret is on screen, because the user can reveal it. Restored when [enabled] turns false or the
 * composable leaves.
 */
@Composable
fun SecureWindow(enabled: Boolean) {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity, enabled) {
        if (activity == null || !enabled) return@DisposableEffect onDispose {}
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) activity.setRecentsScreenshotEnabled(false)
        onDispose {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) activity.setRecentsScreenshotEnabled(true)
        }
    }
}
