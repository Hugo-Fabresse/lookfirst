package com.lookfirst.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lookfirst.BuildConfig

/** Wires [SettingsViewModel] to [SettingsScreen]; re-reads the lists each time the screen comes back. */
@Composable
fun SettingsRoute(viewModel: SettingsViewModel, onBack: () -> Unit, onOpenAbout: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResume() }
    SettingsScreen(
        state = state,
        appVersion = BuildConfig.VERSION_NAME,
        onBack = onBack,
        onUpdateNow = viewModel::onUpdateNow,
        onAutoUpdateChanged = viewModel::onAutoUpdateChanged,
        onOpenAbout = onOpenAbout,
    )
}
