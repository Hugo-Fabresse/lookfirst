package com.lookfirst.ui.screens.result

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lookfirst.R

private val MinTarget = 48.dp
private val ExpandedContentMaxHeight = 240.dp

/**
 * The neutral "not analysed" banner (ux.md 3.4). Grey, with a "?" icon: it can never be taken for the
 * "no risk signal" level. Announced once when the screen appears and given the initial focus.
 */
@Composable
internal fun NotAnalysedBanner(modifier: Modifier = Modifier) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val title = stringResource(R.string.not_analysed_banner)
    val body = stringResource(R.string.not_analysed_body)
    val spoken = stringResource(R.string.a11y_banner, title, body)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .focusRequester(focus)
                .focusable()
                .semantics(mergeDescendants = true) {
                    heading()
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = spoken
                },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_help),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Text(text = body, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Host of a web link: the only large text. For `user@host` it is the real host, the one a browser connects to. */
@Composable
internal fun UrlHost(host: String, nonLatin: Boolean, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.link_will_open),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        TokenizedText(
            text = host,
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground,
        )
        if (nonLatin) NonLatinHostWarning()
    }
}

/** Marker under a host with non-ASCII characters: it may imitate another site. Bold text and icon, not colour alone. */
@Composable
private fun NonLatinHostWarning() {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(modifier = Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                painter = painterResource(R.drawable.ic_warning),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
            Text(
                text = stringResource(R.string.host_non_latin_warning),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            )
        }
    }
}

/** A labelled value. Secrets are masked and need an explicit "show"; warnings carry their own container. */
@Composable
internal fun FieldRow(field: ResultField, modifier: Modifier = Modifier) {
    val label = stringResource(field.label)
    val warning = field.kind == FieldKind.WARNING
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = if (warning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.background,
        contentColor = if (warning) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onBackground
        },
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = label, style = MaterialTheme.typography.labelLarge)
            if (field.kind == FieldKind.SECRET) {
                SecretValue(field.value.value, key = field.label)
            } else {
                TokenizedText(
                    text = field.value.value,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = if (warning) FontWeight.Bold else FontWeight.Normal,
                    ),
                )
            }
            if (warning) {
                Text(text = stringResource(R.string.userinfo_note), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun SecretValue(value: String, key: Int) {
    var revealed by rememberSaveable(key) { mutableStateOf(false) }
    val masked = stringResource(R.string.secret_masked)
    val maskedSpoken = stringResource(R.string.a11y_masked)
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (revealed) {
            TokenizedText(text = value, modifier = Modifier.weight(1f), monospace = true)
        } else {
            Text(
                text = masked,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f).semantics { contentDescription = maskedSpoken },
            )
        }
        TextButton(onClick = { revealed = !revealed }, modifier = Modifier.heightIn(min = MinTarget)) {
            Text(stringResource(if (revealed) R.string.secret_hide else R.string.secret_show))
        }
    }
}

/**
 * The exact scanned text (ux.md 4.3): monospace, left-to-right, not selectable, invisible characters drawn as
 * tokens. Over 120 characters it is folded to two lines; a text that holds a secret stays masked until the user
 * asks, because the secret is part of it.
 */
@Composable
internal fun ExactContent(text: String, containsSecret: Boolean, modifier: Modifier = Modifier) {
    val long = text.length > COLLAPSE_ABOVE_CHARS
    val collapsible = long || containsSecret
    var expanded by rememberSaveable { mutableStateOf(false) }
    val collapsedSpoken = stringResource(R.string.a11y_content_collapsed)
    val expandedSpoken = stringResource(R.string.a11y_content_expanded)
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.exact_content_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            val inner = Modifier.padding(12.dp)
            when {
                containsSecret && !expanded -> MaskedBlock(inner)
                expanded -> TokenizedText(
                    text = text,
                    monospace = true,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = inner.heightIn(max = ExpandedContentMaxHeight).verticalScroll(rememberScrollState()),
                )
                else -> TokenizedText(
                    text = text,
                    monospace = true,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (long) 2 else Int.MAX_VALUE,
                    modifier = inner,
                )
            }
        }
        if (collapsible) {
            TextButton(
                onClick = { expanded = !expanded },
                modifier = Modifier
                    .heightIn(min = MinTarget)
                    .semantics { contentDescription = if (expanded) expandedSpoken else collapsedSpoken },
            ) {
                Text(stringResource(if (expanded) R.string.hide_content else R.string.show_content))
            }
        }
    }
}

@Composable
private fun MaskedBlock(modifier: Modifier) {
    val spoken = stringResource(R.string.a11y_masked)
    Text(
        text = stringResource(R.string.secret_masked),
        style = MaterialTheme.typography.bodyMedium,
        fontFamily = FontFamily.Monospace,
        modifier = modifier.semantics { contentDescription = spoken },
    )
}

@Composable
internal fun HiddenCharsNote(count: Int, modifier: Modifier = Modifier) {
    Text(
        text = pluralStringResource(R.plurals.reason_hidden_chars, count, count),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = modifier.fillMaxWidth(),
    )
}
