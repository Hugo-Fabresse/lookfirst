package com.lookfirst.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lookfirst.R

/**
 * About and privacy (ux.md 5.14, 6): what stays on the phone, what uses the network and who sees what, what the
 * app does not guarantee, licences. Static text; nothing here depends on a scan.
 */
@Composable
fun AboutScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.safeDrawingPadding()) {
            SettingsTopBar(title = R.string.about_title, onBack = onBack)
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Section(R.string.about_local_header, R.string.about_local_body)
                Text(
                    text = stringResource(R.string.about_network_header),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 12.dp).semantics { heading() },
                )
                NetworkRow(R.string.about_net_lists, R.string.about_net_lists_who)
                Text(text = stringResource(R.string.lists_coverage_note), style = MaterialTheme.typography.bodyLarge)
                NetworkRow(R.string.about_net_redirects, R.string.about_net_redirects_who)
                NetworkRow(R.string.about_net_rdap, R.string.about_net_rdap_who)
                Section(R.string.about_no_guarantee_header, R.string.about_no_guarantee_body)
                Section(R.string.about_licences, R.string.about_licences_body)
            }
        }
    }
}

@Composable
private fun Section(header: Int, body: Int) {
    Text(
        text = stringResource(header),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 12.dp).semantics { heading() },
    )
    Text(text = stringResource(body), style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun NetworkRow(use: Int, who: Int) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(text = stringResource(use), style = MaterialTheme.typography.titleSmall)
        Text(text = stringResource(who), style = MaterialTheme.typography.bodyLarge)
    }
}
