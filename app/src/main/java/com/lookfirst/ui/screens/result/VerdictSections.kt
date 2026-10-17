package com.lookfirst.ui.screens.result

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.lookfirst.R
import com.lookfirst.domain.model.Severity
import com.lookfirst.domain.model.Signal
import com.lookfirst.domain.model.SignalCode
import com.lookfirst.domain.verdict.Verdict
import com.lookfirst.domain.verdict.VerdictLevel
import com.lookfirst.ui.theme.LocalVerdictPalette

private val MinTarget = 48.dp
private val BannerMinHeight = 56.dp
private val IconSize = 28.dp
private const val FIRST_ROWS_SHOWN = 3

@StringRes
internal fun VerdictLevel.label(): Int = when (this) {
    VerdictLevel.DANGEROUS -> R.string.level_dangerous
    VerdictLevel.SUSPICIOUS -> R.string.level_suspicious
    VerdictLevel.CAUTION -> R.string.level_caution
    VerdictLevel.NO_RISK_SIGNAL -> R.string.level_no_risk_signal
}

/** One silhouette per level (octagon, triangle, diamond, eye): the levels differ without colour (ux.md 4.1). */
@DrawableRes
internal fun VerdictLevel.icon(): Int = when (this) {
    VerdictLevel.DANGEROUS -> R.drawable.ic_level_dangerous
    VerdictLevel.SUSPICIOUS -> R.drawable.ic_level_suspicious
    VerdictLevel.CAUTION -> R.drawable.ic_level_caution
    VerdictLevel.NO_RISK_SIGNAL -> R.drawable.ic_level_no_risk
}

@DrawableRes
private fun Severity.icon(): Int = when (this) {
    Severity.DANGEROUS -> R.drawable.ic_level_dangerous
    Severity.SUSPICIOUS -> R.drawable.ic_level_suspicious
    Severity.CAUTION -> R.drawable.ic_level_caution
    Severity.INFO -> R.drawable.ic_help
}

/**
 * The sentence under the banner: the heaviest reason, or, at the lowest level, the "guarantees nothing" sentence.
 * Null when there is nothing to say.
 */
internal fun Verdict.mainReason(): Signal? = if (level == VerdictLevel.NO_RISK_SIGNAL) null else reasons.firstOrNull()

/** The reasons shown as rows: all of them at the lowest level (info only), else those after the main one. */
internal fun Verdict.rowReasons() = if (mainReason() == null) reasons else reasons.drop(1)

/** What the user is told about the lists: none installed, or how old they are (ux.md 4.2 item 6). */
internal sealed interface ListsInfo {
    data object None : ListsInfo

    data class Age(val days: Int) : ListsInfo
}

internal fun Verdict.listsInfo(): ListsInfo? = reasons.firstNotNullOfOrNull {
    when (it.code) {
        SignalCode.LISTS_MISSING -> ListsInfo.None
        SignalCode.LISTS_STALE -> ListsInfo.Age(it.count)
        else -> null
    }
}

/** Neutral banner shown for the instant the analysis runs: same place and size as the verdict banner. */
@Composable
internal fun AnalysingBanner(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier.heightIn(min = BannerMinHeight).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(painterResource(R.drawable.ic_help), contentDescription = null, modifier = Modifier.size(IconSize))
            Text(text = stringResource(R.string.analysing_banner), style = MaterialTheme.typography.titleMedium)
        }
    }
}

/**
 * Level banner (icon, label, container colour) and the main sentence under it (ux.md 4.1, 4.2). One focus group
 * read as a whole: it takes the initial focus and is announced once per displayed payload, not on every recomposition.
 */
@Composable
internal fun VerdictHeader(verdict: Verdict, modifier: Modifier = Modifier) {
    val focus = remember { FocusRequester() }
    // Saved across rotation: focus and announcement happen once per payload, not again when the screen is rebuilt.
    var announced by rememberSaveable(verdict.payloadId) { mutableStateOf(false) }
    LaunchedEffect(verdict.payloadId) {
        if (!announced) {
            runCatching { focus.requestFocus() }
            announced = true
        }
    }
    val colors = LocalVerdictPalette.current.of(verdict.level)
    val texts = rememberReasonTexts()
    val sentence = remember(verdict, texts) { headlineSentence(verdict, texts) }
    val disclaimer = stringResource(R.string.no_risk_disclaimer)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focus)
            .focusable()
            .semantics(mergeDescendants = true) {
                heading()
                if (!announced) liveRegion = LiveRegionMode.Polite
            },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = colors.container,
            contentColor = colors.onContainer,
        ) {
            Row(
                modifier = Modifier.heightIn(min = BannerMinHeight).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    painterResource(verdict.level.icon()),
                    contentDescription = null,
                    modifier = Modifier.size(IconSize),
                )
                Text(text = stringResource(verdict.level.label()), style = MaterialTheme.typography.titleLarge)
            }
        }
        Text(
            text = sentence ?: AnnotatedString(disclaimer),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

private fun headlineSentence(verdict: Verdict, texts: ReasonTexts): AnnotatedString? =
    verdict.mainReason()?.let { texts.sentence(it) }

@Composable
private fun rememberReasonTexts(): ReasonTexts {
    val resources = LocalContext.current.resources
    return remember(resources) { ReasonTexts(resources) }
}

/** "Checks run: ..." under a no-risk banner: what was looked at, so the user knows what no signal means (D18). */
@Composable
internal fun ChecksLine(webLink: Boolean, modifier: Modifier = Modifier) {
    Text(
        text = stringResource(if (webLink) R.string.checked_line else R.string.checked_line_content),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
internal fun ListsAgeLine(info: ListsInfo, modifier: Modifier = Modifier) {
    val text = when (info) {
        ListsInfo.None -> stringResource(R.string.lists_none)
        is ListsInfo.Age -> stringResource(
            R.string.lists_age,
            if (info.days <= 0) {
                stringResource(R.string.age_today)
            } else {
                pluralStringResource(R.plurals.age_days, info.days, info.days)
            },
        )
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * The reasons after the main sentence: the first three as short labels that open to the full sentence, the others
 * behind "See N more signals" (ux.md 4.2 item 4).
 */
@Composable
internal fun ReasonList(verdict: Verdict, modifier: Modifier = Modifier) {
    val rows = verdict.rowReasons()
    if (rows.isEmpty()) return
    val texts = rememberReasonTexts()
    val offset = verdict.reasons.size - rows.size
    var showAll by rememberSaveable(verdict.payloadId) { mutableStateOf(false) }
    val visible = if (showAll) rows else rows.take(FIRST_ROWS_SHOWN)
    val hidden = rows.size - visible.size
    Column(modifier = modifier.fillMaxWidth()) {
        visible.forEachIndexed { index, signal ->
            val position = offset + index + 1
            ReasonRow(
                severity = signal.severity,
                shortLabel = texts.shortLabel(signal),
                sentence = remember(signal, texts) { texts.sentence(signal) },
                stateKey = "${verdict.payloadId}/$index",
                position = position,
                total = verdict.reasons.size,
            )
        }
        if (hidden > 0) {
            TextButton(onClick = { showAll = true }, modifier = Modifier.heightIn(min = MinTarget)) {
                Text(pluralStringResource(R.plurals.more_signals, hidden, hidden))
            }
        } else if (showAll && rows.size > FIRST_ROWS_SHOWN) {
            TextButton(onClick = { showAll = false }, modifier = Modifier.heightIn(min = MinTarget)) {
                Text(stringResource(R.string.fewer_signals))
            }
        }
    }
}

@Composable
private fun ReasonRow(
    severity: Severity,
    shortLabel: String,
    sentence: AnnotatedString,
    stateKey: String,
    position: Int,
    total: Int,
) {
    var expanded by rememberSaveable(stateKey) { mutableStateOf(false) }
    val spoken =
        stringResource(R.string.a11y_risk_signal_n, position, total, if (expanded) sentence.text else shortLabel)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTarget)
            .clickable(role = Role.Button) { expanded = !expanded }
            .semantics(mergeDescendants = true) { contentDescription = spoken }
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                painterResource(severity.icon()),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = shortLabel,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            Icon(
                painterResource(if (expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
        if (expanded) {
            Text(
                text = sentence,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = 36.dp),
            )
        }
    }
}
