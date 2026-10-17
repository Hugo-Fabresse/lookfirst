package com.lookfirst.ui.screens.result

import android.content.res.Resources
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import com.lookfirst.R

/** Text with its invisible characters drawn as tokens, and the description TalkBack reads instead. */
private class TokenText(val display: AnnotatedString, val spoken: String?, val cutOff: Int)

private fun buildTokenText(text: String, tokenStyle: SpanStyle, resources: Resources): TokenText {
    val (shown, cutOff) = capForDisplay(text)
    val segments = segmentExact(shown)
    val display = buildAnnotatedString {
        for (segment in segments) {
            when (segment) {
                is ExactSegment.Plain -> append(segment.text)
                is ExactSegment.Hidden -> withStyle(tokenStyle) { append(segment.label) }
            }
        }
    }
    val hasTokens = segments.any { it is ExactSegment.Hidden }
    val spoken = if (hasTokens) {
        segments.joinToString("") { segment ->
            when (segment) {
                is ExactSegment.Plain -> segment.text
                is ExactSegment.Hidden -> " " + resources.getString(
                    R.string.a11y_invisible_char,
                    segment.code,
                    resources.getString(segment.description),
                ) + " "
            }
        }
    } else {
        null
    }
    return TokenText(display, spoken, cutOff)
}

/**
 * Draws [text] left-to-right in the given monospace-or-not [style], with every invisible character replaced by a
 * visible token (distinct background, bold) and a spoken description. Never selectable. Content past
 * [MAX_DISPLAY_CHARS] is replaced by the "N more characters" line.
 */
@Composable
internal fun TokenizedText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = Color.Unspecified,
    monospace: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
) {
    val resources = LocalContext.current.resources
    val tokenStyle = SpanStyle(
        background = MaterialTheme.colorScheme.tertiaryContainer,
        color = MaterialTheme.colorScheme.onTertiaryContainer,
        fontWeight = FontWeight.Bold,
    )
    val tokens = remember(text, tokenStyle, resources) { buildTokenText(text, tokenStyle, resources) }
    val baseStyle = style.merge(
        TextStyle(
            textDirection = TextDirection.Ltr,
            fontFamily = if (monospace) FontFamily.Monospace else style.fontFamily,
        ),
    )
    Column(modifier = modifier) {
        Text(
            text = tokens.display,
            style = baseStyle,
            color = color,
            maxLines = maxLines,
            overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
            modifier = Modifier.semantics { tokens.spoken?.let { contentDescription = it } },
        )
        if (tokens.cutOff > 0) {
            Text(
                text = pluralStringResource(R.plurals.chars_more, tokens.cutOff, tokens.cutOff),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
