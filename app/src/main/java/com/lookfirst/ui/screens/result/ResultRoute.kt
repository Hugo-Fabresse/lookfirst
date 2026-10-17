package com.lookfirst.ui.screens.result

import androidx.activity.compose.BackHandler
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lookfirst.R
import kotlinx.coroutines.launch

/**
 * Wires [ResultViewModel] to [ResultScreen]. [onClose] leaves the screen (Cancel and system back are the same
 * action). If the screen is restored with nothing to show (process death), it closes by itself.
 */
@Composable
fun ResultRoute(viewModel: ResultViewModel, onClose: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val copied = stringResource(R.string.copied)

    BackHandler(onBack = onClose)
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.onReturned() }
    LaunchedEffect(state is ResultUiState.NoContent) { if (state is ResultUiState.NoContent) onClose() }
    LaunchedEffect(viewModel) {
        val scope = this
        viewModel.events.collect { event ->
            when (event) {
                ResultEvent.Copied -> scope.launch {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    snackbarHostState.showSnackbar(copied)
                }
            }
        }
    }

    ResultScreen(
        state = state,
        snackbarHostState = snackbarHostState,
        onCancel = onClose,
        onCopy = viewModel::onCopy,
        onConfirmSecretCopy = viewModel::onConfirmSecretCopy,
        onDismissSecretCopy = viewModel::onDismissSecretCopy,
    )
}
