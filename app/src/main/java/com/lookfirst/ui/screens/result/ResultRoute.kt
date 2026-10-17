package com.lookfirst.ui.screens.result

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lookfirst.R
import com.lookfirst.ui.components.SecureWindow
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
    val unsafe = stringResource(R.string.open_unsafe)
    val noApp = stringResource(R.string.open_no_app)

    // The Wi-Fi add-network dialog (API 30+) answers with a result: accepted goes back to scanning, declined stays here.
    val systemDialog = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.onSystemDialogResult(accepted = it.resultCode == Activity.RESULT_OK)
    }

    BackHandler(onBack = onClose)
    SecureWindow(enabled = (state as? ResultUiState.Content)?.content?.containsSecret == true)
    LaunchedEffect(state is ResultUiState.NoContent) { if (state is ResultUiState.NoContent) onClose() }
    LaunchedEffect(viewModel) {
        val scope = this
        viewModel.events.collect { event ->
            when (event) {
                ResultEvent.Copied -> scope.launch { snackbarHostState.showReplacing(copied) }
                ResultEvent.Opened -> onClose()
                is ResultEvent.LaunchSystemDialog -> systemDialog.launch(event.request.intent)
                is ResultEvent.OpenFailed -> scope.launch {
                    snackbarHostState.showReplacing(if (event.unsafe) unsafe else noApp)
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
        onOpen = viewModel::onOpen,
        onConfirmOpen = viewModel::onConfirmOpen,
        onDismissOpen = viewModel::onDismissOpen,
        checkActions = remember(viewModel) {
            ChecksActions(
                onTrace = viewModel::onTraceRequested,
                onConfirmTrace = viewModel::onTraceConfirmed,
                onCancelTrace = viewModel::onTraceCancelled,
                onAge = viewModel::onAgeRequested,
                onConfirmAge = viewModel::onAgeConfirmed,
                onCancelAge = viewModel::onAgeCancelled,
            )
        },
    )
}

private suspend fun SnackbarHostState.showReplacing(message: String) {
    currentSnackbarData?.dismiss()
    showSnackbar(message)
}
