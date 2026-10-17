package com.lookfirst.ui.screens.result

import androidx.annotation.StringRes
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lookfirst.R
import com.lookfirst.domain.rdap.RdapOutcome
import com.lookfirst.domain.rdap.RdapUnavailable
import com.lookfirst.ui.components.HardenDialogWindow
import com.lookfirst.ui.components.guarded
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private val MinTarget = 48.dp
private val SpinnerSize = 24.dp

/** What the "Optional checks" section can ask of the screen's owner. */
class ChecksActions(
    val onTrace: () -> Unit,
    val onConfirmTrace: () -> Unit,
    val onCancelTrace: () -> Unit,
    val onAge: () -> Unit = {},
    val onConfirmAge: () -> Unit = {},
    val onCancelAge: () -> Unit = {},
) {
    companion object {
        val None = ChecksActions({}, {}, {})
    }
}

/**
 * "Optional checks" (ux.md 3.7): one text button per check, off by default. A button disappears once its check was
 * made; the domain-age result stays as a line. A level change is announced once, politely (live region), and only then.
 */
@Composable
internal fun OptionalChecksSection(checks: OptionalChecks, actions: ChecksActions, modifier: Modifier = Modifier) {
    checks.levelChangedTo?.let { level ->
        Text(
            text = stringResource(R.string.a11y_level_changed, stringResource(level.label())),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.optional_checks),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        if (checks.trace != CheckState.DONE) TraceCheck(checks, actions.onTrace)
        AgeCheck(checks, actions.onAge)
        val open = checks.trace != CheckState.DONE || checks.age != CheckState.DONE
        if (!checks.online && open) CheckNote(R.string.check_offline_note)
    }
}

@Composable
private fun TraceCheck(checks: OptionalChecks, onTrace: () -> Unit) {
    TextButton(
        onClick = onTrace,
        enabled = checks.traceable && checks.online && checks.trace != CheckState.RUNNING,
        modifier = Modifier.heightIn(min = MinTarget),
    ) { Text(stringResource(R.string.trace_redirects)) }
    val note = when {
        checks.httpsOnly -> R.string.trace_https_only
        checks.unusualPort -> R.string.trace_port_only
        checks.trace == CheckState.FAILED -> R.string.check_failed
        else -> null
    }
    note?.let { CheckNote(it) }
}

/** The domain-age check: its button and note while it is open, its result line once it is done. */
@Composable
private fun AgeCheck(checks: OptionalChecks, onAge: () -> Unit) {
    if (checks.age == CheckState.DONE) {
        AgeResult(checks.ageOutcome)
        return
    }
    TextButton(
        onClick = onAge,
        enabled = checks.ageTarget != null && checks.online && checks.age != CheckState.RUNNING,
        modifier = Modifier.heightIn(min = MinTarget),
    ) { Text(stringResource(R.string.age_check)) }
    val note = when {
        checks.ageUnavailable != null -> checks.ageUnavailable.noteRes()
        checks.age == CheckState.FAILED -> R.string.check_failed
        else -> null
    }
    note?.let { CheckNote(it) }
}

@StringRes
private fun RdapUnavailable.noteRes(): Int = when (this) {
    RdapUnavailable.NO_BOOTSTRAP -> R.string.age_note_no_registry_list
    RdapUnavailable.NOT_A_DOMAIN -> R.string.age_note_no_domain
    RdapUnavailable.NO_SERVER -> R.string.age_note_no_server
}

@Composable
private fun AgeResult(outcome: RdapOutcome?) {
    when (outcome) {
        is RdapOutcome.Registered -> CheckLine(
            pluralStringResource(
                R.plurals.age_result_registered,
                outcome.ageDays,
                registrationDate(outcome.registeredAtMillis),
                outcome.ageDays,
            ),
        )
        RdapOutcome.NotFound -> CheckNote(R.string.age_result_unknown)
        else -> Unit
    }
}

/** The registration day in the reader's language, in UTC (the registry's own clock, not the phone's time zone). */
private fun registrationDate(millis: Long): String {
    val format = DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault())
    format.timeZone = TimeZone.getTimeZone("UTC")
    return format.format(Date(millis))
}

@Composable
private fun CheckNote(@StringRes note: Int) = CheckLine(stringResource(note))

@Composable
private fun CheckLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onBackground,
    )
}

/**
 * The warning sheet (ux.md 5.12) and the loading sheet of the redirect check, one window: shown every time a trace is
 * asked for, never remembered. See [CheckSheet].
 */
@Composable
internal fun TraceSheet(state: CheckState?, actions: ChecksActions, onBlocked: () -> Unit) {
    CheckSheet(
        state = state,
        title = stringResource(R.string.warn_trace_title),
        body = stringResource(R.string.warn_trace_body),
        onConfirm = actions.onConfirmTrace,
        onCancel = actions.onCancelTrace,
        onBlocked = onBlocked,
    )
}

/** The same for the domain-age check: names the registry and the only thing that is sent. */
@Composable
internal fun AgeSheet(checks: OptionalChecks?, actions: ChecksActions, onBlocked: () -> Unit) {
    val target = checks?.ageTarget
    CheckSheet(
        state = if (checks == null || target == null) null else checks.age,
        title = stringResource(R.string.warn_rdap_title),
        body = if (target == null) "" else stringResource(R.string.warn_rdap_body, target.domain, target.server),
        onConfirm = actions.onConfirmAge,
        onCancel = actions.onCancelAge,
        onBlocked = onBlocked,
    )
}

/**
 * Initial focus and the filled style are on Cancel; Continue ignores a click that arrives under an overlay
 * (tapjacking, T-UI-3). Cancel stays available while the request runs.
 */
@Composable
private fun CheckSheet(
    state: CheckState?,
    title: String,
    body: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onBlocked: () -> Unit,
) {
    if (state != CheckState.WARNING && state != CheckState.RUNNING) return
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(state) { runCatching { cancelFocus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = {
            HardenDialogWindow()
            if (state == CheckState.WARNING) {
                Text(body)
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
            if (state == CheckState.WARNING) {
                TextButton(onClick = guarded(onBlocked = onBlocked, action = onConfirm)) {
                    Text(stringResource(R.string.warn_continue))
                }
            }
        },
    )
}
