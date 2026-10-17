package com.lookfirst.ui.screens.result

import androidx.annotation.StringRes
import com.lookfirst.R
import com.lookfirst.domain.model.Untrusted
import com.lookfirst.domain.verdict.Verdict
import com.lookfirst.domain.verdict.VerdictLevel

/** State of the Result screen (ux.md 5.5, 5.6). The content shown never changes once it is [Content]. */
sealed interface ResultUiState {
    /** Nothing was handed over yet (or it was closed): the screen leaves. */
    data object NoContent : ResultUiState

    /** The scanned text is being parsed off the UI thread. */
    data object Parsing : ResultUiState

    /** Too large or empty: nothing trustworthy to show or copy (ux.md 5.5). */
    data class Unreadable(val reason: UnreadableReason) : ResultUiState

    /**
     * Decoded content. [verdict] is null for the instant the analysis runs (the screen shows "Analysing..." in the
     * space of the banner, nothing moves when it arrives). [confirmingSecretCopy] and [confirmingOpen] are the dialogs of ux.md 4.6.
     */
    data class Content(
        val content: ResultContent,
        val verdict: Verdict? = null,
        val confirmingSecretCopy: Boolean = false,
        val confirmingOpen: Boolean = false,
        /** Open was tapped and its action is under way: a second tap does nothing. */
        val opening: Boolean = false,
        /** The "Optional checks" section (ux.md 3.7); null for content that is not a web link. */
        val checks: OptionalChecks? = null,
    ) : ResultUiState
}

enum class UnreadableReason(@StringRes val message: Int) {
    TOO_LARGE(R.string.err_too_big),
    EMPTY(R.string.err_empty),
}

/** What the screen shows for one decoded payload. All scanned text stays wrapped in [Untrusted]. */
data class ResultContent(
    @StringRes val typeLabel: Int,
    /** The real host of a web link, shown large. Null for any other type. */
    val host: HostDisplay?,
    /** A web link: the "checks run" line of the no-risk level names the address checks. */
    val isWebLink: Boolean,
    /** What Open would do for this type, null when there is no Open (deep link, payment, text...). */
    val openKind: OpenKind?,
    val fields: List<ResultField>,
    /** The sanitized text, shown in the monospace container (secret ones only after "show"). */
    val exact: Untrusted,
    /** Number of invisible characters found in the content and in its decoded fields. */
    val hiddenCharCount: Int,
    /** Copy needs the confirmation dialog: the text contains a secret (otpauth seed, Wi-Fi password). */
    val containsSecret: Boolean,
    /** Deep links and dangerous schemes: Copy is labelled "Copy only" and cannot be opened. */
    val copyOnly: Boolean,
    /** The image held several QR codes: only the first is shown, and the screen says so (D14). */
    val multipleCodes: Boolean = false,
)

enum class FieldKind {
    PLAIN,

    /** Masked until the user taps "show". */
    SECRET,

    /** Drawn attention to (the part before the `@` of a URL). */
    WARNING,
}

data class ResultField(@StringRes val label: Int, val value: Untrusted, val kind: FieldKind = FieldKind.PLAIN)

/**
 * A host as the user must read it (ux.md 4.4). [shown] is the Unicode form when it was normalized, with the
 * registrable domain starting at [strongFrom] (drawn bold); [technical] is the punycode form a browser will use,
 * only when it differs. Not normalized ([normalized] false): the host as typed, drawn with its invisible characters
 * as tokens.
 */
data class HostDisplay(val shown: Untrusted, val technical: String?, val strongFrom: Int, val normalized: Boolean)

/** The action behind the Open button. */
enum class OpenKind { BROWSER, DIALER, SMS, MAIL, MAPS, WIFI }

/** Where the redirect check stands. The warning is shown every time, never remembered. */
enum class CheckState {
    IDLE,

    /** The warning sheet (ux.md 5.12) is open. */
    WARNING,

    /** The request is under way (loading sheet, Cancel always available). */
    RUNNING,

    /** No trace could be made: the section says so, the verdict is the local analysis. */
    FAILED,

    /** The trace was made and its signals are in the verdict: the check is not offered again. */
    DONE,
}

/**
 * The optional, network-using checks of a web link (ux.md 3.7). [httpsOnly]: the link is plain `http://`, which the
 * app never contacts. [online] false disables the button. [levelChangedTo] is set once when a check moved the level
 * (TalkBack announces it through a live region, nothing is announced otherwise).
 */
data class OptionalChecks(
    val trace: CheckState = CheckState.IDLE,
    val httpsOnly: Boolean = false,
    /** The link names a port other than 443: the tracer only contacts 443 (T-NET-2). */
    val unusualPort: Boolean = false,
    val online: Boolean = true,
    val levelChangedTo: VerdictLevel? = null,
) {
    /** The link itself allows a trace (https, port 443). */
    val traceable: Boolean get() = !httpsOnly && !unusualPort
}
