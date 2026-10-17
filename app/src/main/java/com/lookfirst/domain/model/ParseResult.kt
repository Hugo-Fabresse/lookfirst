package com.lookfirst.domain.model

/** Hidden characters found in one decoded field (percent-decoded or unescaped); indexes are inside that field. */
data class FieldHiddenChars(val field: String, val chars: List<HiddenChar>)

/** Outcome of decoding a scanned string. Never an exception: every input maps to one of these. */
sealed interface ParseResult {
    /**
     * [payload] is the typed content, [hiddenChars] the invisible characters of its sanitized text, and
     * [fieldHiddenChars] those that only appear once a field is decoded (`issuer=Goo%E2%80%8Bgle`).
     */
    data class Ok(
        val payload: ScanPayload,
        val hiddenChars: List<HiddenChar>,
        val fieldHiddenChars: List<FieldHiddenChars> = emptyList(),
    ) : ParseResult

    /** The content exceeded [maxChars]; nothing was parsed. */
    data class TooLarge(val length: Int, val maxChars: Int) : ParseResult

    /** Nothing but whitespace. */
    data object Empty : ParseResult
}
