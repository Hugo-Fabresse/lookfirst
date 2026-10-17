package com.lookfirst.ui.screens.result

import android.os.Build
import androidx.annotation.StringRes
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
import com.lookfirst.domain.model.SignalCode
import com.lookfirst.domain.verdict.Verdict
import com.lookfirst.domain.verdict.VerdictLevel
import com.lookfirst.ui.components.guarded
import kotlinx.coroutines.launch

private val MinTarget = 48.dp

/**
 * Verdict of a scan (ux.md 4, 5.7 to 5.11): level banner, main reason, the host to be opened, the other reasons,
 * the exact content and the Cancel / Copy / Open stack pinned at the bottom. Back is Cancel. Open is shown but
 * stays disabled until LF-19 (nothing is ever opened from here).
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
                    actions = (state as? ResultUiState.Content)?.toActions(onCopy),
                    onCancel = onCancel,
                    onBlocked = notifyOverlay,
                )
            },
        ) { padding ->
            Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                when (state) {
                    ResultUiState.NoContent, ResultUiState.Parsing -> Loading()
                    is ResultUiState.Unreadable -> UnreadableContent(state.reason)
                    is ResultUiState.Content -> ContentBody(state.content, state.verdict)
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
private fun ContentBody(content: ResultContent, verdict: Verdict?) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (verdict == null) AnalysingBanner() else VerdictHeader(verdict)
        if (content.multipleCodes) {
            Text(
                text = stringResource(R.string.err_multi_qr),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        Text(
            text = stringResource(content.typeLabel),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        content.host?.let { UrlHost(it) }
        content.fields.forEach { FieldRow(it) }
        if (content.copyOnly) {
            Text(
                text = stringResource(R.string.not_openable),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        if (verdict?.level == VerdictLevel.NO_RISK_SIGNAL) ChecksLine(webLink = content.isWebLink)
        if (content.hiddenCharCount > 0 && verdict?.mainReason()?.code != SignalCode.HIDDEN_CHARS) {
            HiddenCharsNote(content.hiddenCharCount)
        }
        verdict?.let { ReasonList(it) }
        ExactContent(text = content.exact.value, containsSecret = content.containsSecret)
        verdict?.listsInfo()?.let { ListsAgeLine(it) }
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

/** Open as shown for now: its label and whether it is the emphasised button. Never clickable before LF-19. */
private class OpenAction(@StringRes val label: Int, val filled: Boolean)

/** What the stack holds for one content: [cancelFilled] unless the level makes Open the main action. */
private class Actions(val copy: CopyAction, val open: OpenAction?, val cancelFilled: Boolean)

private fun ResultUiState.Content.toActions(onCopy: () -> Unit): Actions {
    val level = verdict?.level
    val heavy = level == VerdictLevel.DANGEROUS || level == VerdictLevel.SUSPICIOUS
    val open = content.openKind?.let { kind ->
        OpenAction(label = if (heavy) R.string.action_open_anyway else kind.label(), filled = !heavy && level != null)
    }
    // Cancel is the filled button when the level is heavy, while the analysis runs, and whenever there is no Open.
    return Actions(CopyAction(content.copyOnly, onCopy), open, cancelFilled = open == null || !open.filled)
}

@StringRes
private fun OpenKind.label(): Int = when (this) {
    OpenKind.BROWSER -> R.string.open_in_browser
    OpenKind.DIALER -> R.string.open_dialer
    OpenKind.SMS -> R.string.write_sms
    OpenKind.MAIL -> R.string.write_email
    OpenKind.MAPS -> R.string.open_in_maps
    OpenKind.WIFI -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        R.string.add_wifi_network
    } else {
        R.string.open_wifi_settings
    }
}

/**
 * Pinned actions, in the fixed order Cancel, Copy, Open (ux.md 4.1); only the emphasis changes with the level.
 * [actions] is null when there is nothing trustworthy to copy. Copy is guarded: a click arriving while another
 * window covers ours is ignored and explained (T-UI-3); Cancel stays available. Open is disabled until LF-19.
 */
@Composable
private fun ActionStack(actions: Actions?, onCancel: () -> Unit, onBlocked: () -> Unit) {
    val cancelSpoken = stringResource(R.string.a11y_cancel_resume)
    val copySpoken = stringResource(R.string.a11y_copy_exact)
    val openSpoken = stringResource(R.string.a11y_open_unavailable)
    val cancelModifier = Modifier
        .fillMaxWidth()
        .heightIn(min = MinTarget)
        .semantics { contentDescription = cancelSpoken }
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (actions == null || actions.cancelFilled) {
            Button(onClick = onCancel, modifier = cancelModifier) { Text(stringResource(R.string.action_cancel)) }
        } else {
            OutlinedButton(onClick = onCancel, modifier = cancelModifier) {
                Text(stringResource(R.string.action_cancel))
            }
        }
        if (actions != null) {
            OutlinedButton(
                onClick = guarded(onBlocked = onBlocked, action = actions.copy.onClick),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = MinTarget)
                    .semantics { contentDescription = copySpoken },
            ) { Text(stringResource(if (actions.copy.copyOnly) R.string.copy_only else R.string.action_copy)) }
            actions.open?.let { OpenButton(it, openSpoken) }
        }
    }
}

@Composable
private fun OpenButton(open: OpenAction, spoken: String) {
    val modifier = Modifier
        .fillMaxWidth()
        .heightIn(min = MinTarget)
        .semantics { contentDescription = spoken }
    if (open.filled) {
        Button(onClick = {}, enabled = false, modifier = modifier) { Text(stringResource(open.label)) }
    } else {
        OutlinedButton(onClick = {}, enabled = false, modifier = modifier) { Text(stringResource(open.label)) }
    }
}
