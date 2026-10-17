package com.lookfirst.ui.screens.result

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.lookfirst.R
import com.lookfirst.ui.components.HardenDialogWindow
import com.lookfirst.ui.components.guarded

/**
 * "Open anyway?" before opening content rated SUSPICIOUS or DANGEROUS (ux.md 4.6, D17, threat model T-UI-2).
 * Initial focus and the filled style are on Cancel; no delay, no checkbox. The dialog window is protected against
 * overlays and the confirming button ignores a click that arrives under one (T-UI-3).
 */
@Composable
fun OpenAnywayDialog(
    dangerous: Boolean,
    host: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onBlocked: () -> Unit,
) {
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_open_title)) },
        text = {
            HardenDialogWindow()
            Column {
                Text(
                    stringResource(
                        if (dangerous) R.string.dialog_open_danger_body else R.string.dialog_open_suspicious_body,
                    ),
                )
                host?.let { Text(text = it, fontWeight = FontWeight.Bold) }
            }
        },
        dismissButton = {
            Button(onClick = onDismiss, modifier = Modifier.focusRequester(cancelFocus)) {
                Text(stringResource(R.string.action_cancel))
            }
        },
        confirmButton = {
            TextButton(onClick = guarded(onBlocked = onBlocked, action = onConfirm)) {
                Text(stringResource(R.string.action_open_anyway))
            }
        },
    )
}
