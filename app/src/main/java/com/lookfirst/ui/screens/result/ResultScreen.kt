package com.lookfirst.ui.screens.result

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lookfirst.R
import com.lookfirst.ui.components.guarded
import kotlinx.coroutines.launch

private val MinTarget = 48.dp

/**
 * Result of a scan (ux.md 5.5, 5.6): the decoded content and the Cancel / Copy actions, nothing else.
 * There is no Open button and no verdict yet: the banner says "not analysed". Back is Cancel.
 */
@Composable
fun ResultScreen(
    state: ResultUiState,
    snackbarHostState: SnackbarHostState,
    onCancel: () -> Unit,
    onCopy: () -> Unit,
    onConfirmSecretCopy: () -> Unit,
    onDismissSecretCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val overlayMessage = stringResource(R.string.overlay_blocked)
    val notifyOverlay: () -> Unit = {
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(overlayMessage)
        }
    }
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold(
            modifier = Modifier.safeDrawingPadding(),
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0),
            topBar = { ResultTopBar(unreadable = state is ResultUiState.Unreadable, onBack = onCancel) },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                ActionStack(
                    copy = (state as? ResultUiState.Content)?.content?.let { CopyAction(it.copyOnly, onCopy) },
                    onCancel = onCancel,
                    onBlocked = notifyOverlay,
                )
            },
        ) { padding ->
            Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                when (state) {
                    ResultUiState.NoContent, ResultUiState.Parsing -> Loading()
                    is ResultUiState.Unreadable -> UnreadableContent(state.reason)
                    is ResultUiState.Content -> ContentBody(state.content)
                }
            }
        }
        if (state is ResultUiState.Content && state.confirmingSecretCopy) {
            CopySecretDialog(
                onConfirm = onConfirmSecretCopy,
                onDismiss = onDismissSecretCopy,
                onBlocked = notifyOverlay,
            )
        }
    }
}

@Composable
private fun ResultTopBar(unreadable: Boolean, onBack: () -> Unit) {
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
            text = stringResource(if (unreadable) R.string.unreadable_title else R.string.result_scan_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Composable
private fun Loading() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
private fun ContentBody(content: ResultContent) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        NotAnalysedBanner()
        Text(
            text = stringResource(content.typeLabel),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        content.host?.let { UrlHost(it.value, nonLatin = content.nonLatinHost) }
        content.fields.forEach { FieldRow(it) }
        if (content.copyOnly) {
            Text(
                text = stringResource(R.string.not_openable),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        if (content.hiddenCharCount > 0) HiddenCharsNote(content.hiddenCharCount)
        ExactContent(text = content.exact.value, containsSecret = content.containsSecret)
    }
}

@Composable
private fun UnreadableContent(reason: UnreadableReason) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_warning),
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = stringResource(reason.message),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.err_not_a_green_light),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
    }
}

private class CopyAction(val copyOnly: Boolean, val onClick: () -> Unit)

/**
 * Pinned actions, in the fixed order Cancel (filled), Copy (outlined). [copy] is null when there is nothing
 * trustworthy to copy. Only Copy is guarded: a click arriving while another window covers ours is ignored and
 * explained (T-UI-3); Cancel stays available.
 */
@Composable
private fun ActionStack(copy: CopyAction?, onCancel: () -> Unit, onBlocked: () -> Unit) {
    val cancelSpoken = stringResource(R.string.a11y_cancel_resume)
    val copySpoken = stringResource(R.string.a11y_copy_exact)
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = onCancel,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MinTarget)
                .semantics { contentDescription = cancelSpoken },
        ) { Text(stringResource(R.string.action_cancel)) }
        if (copy != null) {
            OutlinedButton(
                onClick = guarded(onBlocked = onBlocked, action = copy.onClick),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = MinTarget)
                    .semantics { contentDescription = copySpoken },
            ) { Text(stringResource(if (copy.copyOnly) R.string.copy_only else R.string.action_copy)) }
        }
    }
}
