package com.lookfirst.ui.screens.result

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import com.lookfirst.R
import com.lookfirst.ui.components.HardenDialogWindow
import com.lookfirst.ui.components.guarded

/**
 * Confirmation before a secret (otpauth seed, Wi-Fi password) goes to the clipboard (ux.md 4.6, T-SCH-11).
 * Initial focus on Cancel; the dialog window itself is protected against overlays (T-UI-3).
 */
@Composable
fun CopySecretDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_copy_secret_title)) },
        text = {
            HardenDialogWindow()
            Text(stringResource(R.string.dialog_copy_secret_body))
        },
        dismissButton = {
            TextButton(
                onClick = guarded(onDismiss),
                modifier = Modifier.focusRequester(cancelFocus),
            ) { Text(stringResource(R.string.action_cancel)) }
        },
        confirmButton = {
            TextButton(onClick = guarded(onConfirm)) { Text(stringResource(R.string.action_copy)) }
        },
    )
}
