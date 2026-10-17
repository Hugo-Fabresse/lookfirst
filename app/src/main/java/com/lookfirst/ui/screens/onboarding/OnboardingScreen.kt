package com.lookfirst.ui.screens.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lookfirst.R

/**
 * First launch (ux.md 3.1, 5.1): three facts, the automatic-update choice (on by default), one button. The camera
 * permission is not asked here. [onStart] receives the choice of the toggle.
 */
@Composable
fun OnboardingScreen(onStart: (autoUpdate: Boolean) -> Unit, modifier: Modifier = Modifier) {
    var autoUpdate by rememberSaveable { mutableStateOf(true) }
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.safeDrawingPadding().padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.semantics { heading() },
                )
                Text(stringResource(R.string.onboarding_tagline), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.onboarding_nothing_opens), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.onboarding_privacy), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.onboarding_lists), style = MaterialTheme.typography.bodyLarge)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .toggleable(value = autoUpdate, role = Role.Switch, onValueChange = { autoUpdate = it }),
                ) {
                    Text(
                        text = stringResource(R.string.onboarding_auto_update),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = autoUpdate, onCheckedChange = null)
                }
                Text(stringResource(R.string.onboarding_changeable), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.onboarding_no_guarantee), style = MaterialTheme.typography.bodyMedium)
            }
            Button(onClick = { onStart(autoUpdate) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.onboarding_start))
            }
        }
    }
}
