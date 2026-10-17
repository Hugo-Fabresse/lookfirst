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
import androidx.compose.ui.unit.dp
import com.lookfirst.R

/**
 * Privacy policy (ux.md 5.15): static text from string resources, no network, no state. Each section title is a
 * semantic heading; the text wraps and scrolls at any font scale.
 */
@Composable
fun PrivacyPolicyScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.safeDrawingPadding()) {
            SettingsTopBar(title = R.string.privacy_title, onBack = onBack)
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(text = stringResource(R.string.privacy_updated), style = MaterialTheme.typography.labelLarge)
                Text(text = stringResource(R.string.privacy_intro), style = MaterialTheme.typography.bodyLarge)
                Section(R.string.privacy_never_header, R.string.privacy_never_body)
                Section(R.string.privacy_where_header, R.string.privacy_where_body)
                Section(R.string.privacy_who_header, null)
                NetworkRow(R.string.privacy_who_lists_title, R.string.privacy_who_lists_body)
                NetworkRow(R.string.privacy_who_redirects_title, R.string.privacy_who_redirects_body)
                NetworkRow(R.string.privacy_who_rdap_title, R.string.privacy_who_rdap_body)
                Section(R.string.privacy_clipboard_header, R.string.privacy_clipboard_body)
                Section(R.string.privacy_permissions_header, R.string.privacy_permissions_body)
                Section(R.string.privacy_local_header, R.string.privacy_local_body)
                Section(R.string.privacy_contact_header, R.string.privacy_contact_body)
            }
        }
    }
}
