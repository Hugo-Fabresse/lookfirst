package com.lookfirst.ui.screens.result

import androidx.annotation.StringRes
import com.lookfirst.R
import com.lookfirst.domain.model.Untrusted

/** State of the Result screen (ux.md 5.5, 5.6). The content shown never changes once it is [Content]. */
sealed interface ResultUiState {
    /** Nothing was handed over yet (or it was closed): the screen leaves. */
    data object NoContent : ResultUiState

    /** The scanned text is being parsed off the UI thread. */
    data object Parsing : ResultUiState

    /** Too large or empty: nothing trustworthy to show or copy (ux.md 5.5). */
    data class Unreadable(val reason: UnreadableReason) : ResultUiState

    /** Decoded content. [confirmingSecretCopy] is the only part that changes: the dialog of ux.md 4.6. */
    data class Content(val content: ResultContent, val confirmingSecretCopy: Boolean = false) : ResultUiState
}

enum class UnreadableReason(@StringRes val message: Int) {
    TOO_LARGE(R.string.err_too_big),
    EMPTY(R.string.err_empty),
}

/** What the screen shows for one decoded payload. All scanned text stays wrapped in [Untrusted]. */
data class ResultContent(
    @StringRes val typeLabel: Int,
    /** The real host of a web link, shown large. Null for any other type. */
    val host: Untrusted?,
    val fields: List<ResultField>,
    /** The sanitized text, shown in the monospace container (secret ones only after "show"). */
    val exact: Untrusted,
    /** Number of invisible characters found in the content and in its decoded fields. */
    val hiddenCharCount: Int,
    /** Copy needs the confirmation dialog: the text contains a secret (otpauth seed, Wi-Fi password). */
    val containsSecret: Boolean,
    /** Deep links and dangerous schemes: Copy is labelled "Copy only" and cannot be opened. */
    val copyOnly: Boolean,
)

enum class FieldKind {
    PLAIN,

    /** Masked until the user taps "show". */
    SECRET,

    /** Drawn attention to (the part before the `@` of a URL). */
    WARNING,
}

data class ResultField(@StringRes val label: Int, val value: Untrusted, val kind: FieldKind = FieldKind.PLAIN)
