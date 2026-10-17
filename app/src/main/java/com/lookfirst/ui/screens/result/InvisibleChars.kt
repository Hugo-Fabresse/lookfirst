package com.lookfirst.ui.screens.result

import androidx.annotation.StringRes
import com.lookfirst.R
import com.lookfirst.domain.decoder.ContentSanitizer
import com.lookfirst.domain.model.HiddenCharKind
import java.util.Locale

/** Largest part of a content that is drawn; the rest is announced as "N more characters" (ux.md 4.3). */
internal const val MAX_DISPLAY_CHARS = 2000

/** Above this length the exact content is collapsed to two lines (ux.md 4.3). */
internal const val COLLAPSE_ABOVE_CHARS = 120

internal sealed interface ExactSegment {
    data class Plain(val text: String) : ExactSegment

    /**
     * A character the user cannot see, drawn as the visible [label] (`<U+202E>`, `<LF>`). [code] is the same
     * thing without brackets, used in the spoken description.
     */
    data class Hidden(val label: String, val code: String, @StringRes val description: Int) : ExactSegment
}

/** [text] cut at [MAX_DISPLAY_CHARS] (never inside a surrogate pair) and the number of characters left out. */
internal fun capForDisplay(text: String): Pair<String, Int> {
    if (text.length <= MAX_DISPLAY_CHARS) return text to 0
    var end = MAX_DISPLAY_CHARS
    if (Character.isHighSurrogate(text[end - 1])) end--
    return text.substring(0, end) to text.length - end
}

/**
 * Splits [text] into plain runs and visible tokens for every control, format, bidi, zero-width or odd-space
 * character. The raw character itself is never drawn: a bidi override cannot reorder what the user reads.
 */
internal fun segmentExact(text: String): List<ExactSegment> {
    val hidden = ContentSanitizer.findHiddenChars(text)
    if (hidden.isEmpty()) return listOf(ExactSegment.Plain(text))
    val segments = ArrayList<ExactSegment>()
    var cursor = 0
    for (char in hidden) {
        if (char.index > cursor) segments += ExactSegment.Plain(text.substring(cursor, char.index))
        val code = codeOf(char.codePoint)
        segments += ExactSegment.Hidden("<$code>", code, describe(char.codePoint, char.kind))
        cursor = char.index + Character.charCount(char.codePoint)
    }
    if (cursor < text.length) segments += ExactSegment.Plain(text.substring(cursor))
    return segments
}

private fun codeOf(codePoint: Int): String = when (codePoint) {
    '\n'.code -> "LF"
    '\r'.code -> "CR"
    '\t'.code -> "TAB"
    else -> String.format(Locale.ROOT, "U+%04X", codePoint)
}

@StringRes
private fun describe(codePoint: Int, kind: HiddenCharKind): Int = when {
    codePoint == 0 -> R.string.a11y_null_char
    codePoint == '\n'.code || codePoint == '\r'.code -> R.string.a11y_line_break
    kind == HiddenCharKind.BIDI -> R.string.a11y_bidi_override
    kind == HiddenCharKind.ZERO_WIDTH -> R.string.a11y_zero_width
    else -> R.string.a11y_invisible_other
}
