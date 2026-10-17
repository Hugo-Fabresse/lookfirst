package com.lookfirst.domain.model

/** Outcome of decoding a scanned string. Never an exception: every input maps to one of these. */
sealed interface ParseResult {
    /** [payload] is the typed content, [hiddenChars] the invisible characters found in its sanitized text. */
    data class Ok(val payload: ScanPayload, val hiddenChars: List<HiddenChar>) : ParseResult

    /** The content exceeded [maxChars]; nothing was parsed. */
    data class TooLarge(val length: Int, val maxChars: Int) : ParseResult

    /** Nothing but whitespace. */
    data object Empty : ParseResult
}
