package com.lookfirst.domain.model

/** Why a character is reported by the sanitizer. Reported, never silently removed (T-CNT-2, T-CNT-3). */
enum class HiddenCharKind {
    CONTROL,
    BIDI,
    ZERO_WIDTH,
    FORMAT,
    INVISIBLE,
    LINE_SEPARATOR,
    UNUSUAL_SPACE,
    PRIVATE_USE,
    INVALID_SURROGATE,
}

/** A character that is invisible, reorders text or splits lines. [index] is a UTF-16 index in the sanitized text. */
data class HiddenChar(val index: Int, val codePoint: Int, val kind: HiddenCharKind)
