package com.lookfirst.ui.screens.result

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lookfirst.R
import com.lookfirst.ui.components.HardenDialogWindow
import com.lookfirst.ui.components.guarded

private val MinTarget = 48.dp
private val SpinnerSize = 24.dp

/** What the "Optional checks" section can ask of the screen's owner. */
class ChecksActions(val onTrace: () -> Unit, val onConfirmTrace: () -> Unit, val onCancelTrace: () -> Unit) {
    companion object {
        val None = ChecksActions({}, {}, {})
    }
}

/**
 * "Optional checks" (ux.md 3.7): one text button per check, off by default. The section is hidden once the check was
 * made. A level change is announced once, politely (live region), and only then.
 */
@Composable
internal fun OptionalChecksSection(checks: OptionalChecks, onTrace: () -> Unit, modifier: Modifier = Modifier) {
    checks.levelChangedTo?.let { level ->
        Text(
            text = stringResource(R.string.a11y_level_changed, stringResource(level.label())),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    if (checks.trace == TraceState.DONE) return
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.optional_checks),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        TextButton(
            onClick = onTrace,
            enabled = !checks.httpsOnly && checks.online && checks.trace != TraceState.RUNNING,
            modifier = Modifier.heightIn(min = MinTarget),
        ) { Text(stringResource(R.string.trace_redirects)) }
        val note = when {
            checks.httpsOnly -> R.string.trace_https_only
            !checks.online -> R.string.check_offline_note
            checks.trace == TraceState.FAILED -> R.string.check_failed
            else -> null
        }
        note?.let {
            Text(
                text = stringResource(it),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
    }
}

/**
 * The warning sheet (ux.md 5.12) and the loading sheet, one window: shown every time a trace is asked for, never
 * remembered. Initial focus and the filled style are on Cancel; Continue ignores a click that arrives under an overlay
 * (tapjacking, T-UI-3). Cancel stays available while the request runs.
 */
@Composable
internal fun TraceSheet(state: TraceState?, actions: ChecksActions, onBlocked: () -> Unit) {
    if (state != TraceState.WARNING && state != TraceState.RUNNING) return
    val onConfirm = actions.onConfirmTrace
    val onCancel = actions.onCancelTrace
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(state) { runCatching { cancelFocus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.warn_trace_title)) },
        text = {
            HardenDialogWindow()
            if (state == TraceState.WARNING) {
                Text(stringResource(R.string.warn_trace_body))
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(SpinnerSize))
                    Text(stringResource(R.string.check_loading))
                }
            }
        },
        dismissButton = {
            Button(onClick = onCancel, modifier = Modifier.focusRequester(cancelFocus)) {
                Text(stringResource(R.string.action_cancel))
            }
        },
        confirmButton = {
            if (state == TraceState.WARNING) {
                TextButton(onClick = guarded(onBlocked = onBlocked, action = onConfirm)) {
                    Text(stringResource(R.string.warn_continue))
                }
            }
        },
    )
}
