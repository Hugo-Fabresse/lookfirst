package com.lookfirst.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lookfirst.R

/**
 * Settings (ux.md 3.8, 5.13): the reputation lists (version, age, last attempt, "Update now", automatic update),
 * the link to About and privacy, the app version. Stateless: state in, lambdas out.
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    appVersion: String,
    onBack: () -> Unit,
    onUpdateNow: () -> Unit,
    onAutoUpdateChanged: (Boolean) -> Unit,
    onOpenAbout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.safeDrawingPadding()) {
            SettingsTopBar(title = R.string.settings_title, onBack = onBack)
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ListsBlock(state, onUpdateNow, onAutoUpdateChanged)
                LinkRow(label = R.string.settings_about_link, onClick = onOpenAbout)
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text(
                        text = stringResource(R.string.settings_app_version),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Text(text = appVersion, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@Composable
internal fun SettingsTopBar(title: Int, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_back),
                contentDescription = stringResource(R.string.a11y_back),
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.semantics { heading() },
        )
    }
}

@Composable
private fun ListsBlock(state: SettingsUiState, onUpdateNow: () -> Unit, onAutoUpdateChanged: (Boolean) -> Unit) {
    Text(
        text = stringResource(R.string.settings_lists_header),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() },
    )
    Text(text = versionLine(state), style = MaterialTheme.typography.bodyLarge)
    Text(text = stringResource(R.string.lists_coverage_note), style = MaterialTheme.typography.bodyMedium)
    Text(
        text = stringResource(
            when (state.lastAttempt) {
                LastAttemptLine.NONE -> R.string.settings_last_attempt_none
                LastAttemptLine.SUCCEEDED -> R.string.settings_last_attempt_ok
                LastAttemptLine.FAILED -> R.string.settings_last_attempt_failed
            },
        ),
        style = MaterialTheme.typography.bodyMedium,
    )
    state.notice?.let { NoticeLine(it) }
    OutlinedButton(
        onClick = onUpdateNow,
        enabled = !state.updating,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) { Text(stringResource(R.string.settings_update_now)) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = state.autoUpdate, role = Role.Switch, onValueChange = onAutoUpdateChanged),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_auto_update), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.settings_unmetered_only), style = MaterialTheme.typography.bodyMedium)
        }
        Switch(checked = state.autoUpdate, onCheckedChange = null)
    }
}

@Composable
private fun versionLine(state: SettingsUiState): String {
    val version = state.listsEntries
    val days = state.listsAgeDays
    if (version == null || days == null) return stringResource(R.string.lists_none)
    val age = if (days <= 0) {
        stringResource(R.string.age_today)
    } else {
        pluralStringResource(R.plurals.age_days, days, days)
    }
    return stringResource(R.string.settings_lists_version, version, age)
}

@Composable
private fun NoticeLine(notice: UpdateNotice) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (notice == UpdateNotice.UPDATING) CircularProgressIndicator(modifier = Modifier.size(20.dp))
        Text(
            text = stringResource(
                when (notice) {
                    UpdateNotice.UPDATING -> R.string.lists_updating
                    UpdateNotice.FAILED -> R.string.lists_update_failed
                    UpdateNotice.REJECTED -> R.string.lists_rejected
                    UpdateNotice.OFFLINE -> R.string.lists_offline
                    UpdateNotice.UP_TO_DATE -> R.string.lists_up_to_date
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
internal fun LinkRow(label: Int, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Icon(
            painter = painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onBackground,
        )
    }
}
