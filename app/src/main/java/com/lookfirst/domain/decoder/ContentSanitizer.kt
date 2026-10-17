package com.lookfirst.domain.decoder

import com.lookfirst.domain.model.HiddenChar
import com.lookfirst.domain.model.HiddenCharKind
import java.text.Normalizer

sealed interface SanitizeResult {
    data class Clean(val text: String, val hiddenChars: List<HiddenChar>) : SanitizeResult

    data class TooLarge(val length: Int) : SanitizeResult

    data object Empty : SanitizeResult
}

/**
 * First step of decoding: bounds the size, normalizes to NFC and lists invisible characters.
 * The length is checked before any other work, so a huge input costs O(1).
 */
object ContentSanitizer {
    /** A QR code holds at most ~2953 bytes; 4096 UTF-16 chars leaves room and stays cheap (T-CNT-1). */
    const val MAX_CHARS = 4096

    fun sanitize(raw: String): SanitizeResult {
        if (raw.length > MAX_CHARS) return SanitizeResult.TooLarge(raw.length)
        val form = Normalizer.Form.NFC
        val text = if (Normalizer.isNormalized(raw, form)) raw else Normalizer.normalize(raw, form)
        return when {
            text.length > MAX_CHARS -> SanitizeResult.TooLarge(text.length)
            text.all { it == ' ' || it == '\t' || it == '\r' || it == '\n' } -> SanitizeResult.Empty
            else -> SanitizeResult.Clean(text, findHiddenChars(text))
        }
    }

    internal fun findHiddenChars(text: String): List<HiddenChar> {
        val found = ArrayList<HiddenChar>()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            classify(cp)?.let { found += HiddenChar(i, cp, it) }
            i += Character.charCount(cp)
        }
        return found
    }

    private fun classify(cp: Int): HiddenCharKind? = when {
        cp in BIDI_CONTROLS -> HiddenCharKind.BIDI
        cp in ZERO_WIDTH -> HiddenCharKind.ZERO_WIDTH
        else -> classifyByCategory(cp)
    }

    private fun classifyByCategory(cp: Int): HiddenCharKind? = when (Character.getType(cp)) {
        Character.CONTROL.toInt() -> HiddenCharKind.CONTROL
        Character.FORMAT.toInt() -> HiddenCharKind.FORMAT
        Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt() -> HiddenCharKind.LINE_SEPARATOR
        Character.SPACE_SEPARATOR.toInt() -> if (cp == ' '.code) null else HiddenCharKind.UNUSUAL_SPACE
        Character.PRIVATE_USE.toInt() -> HiddenCharKind.PRIVATE_USE
        Character.SURROGATE.toInt() -> HiddenCharKind.INVALID_SURROGATE
        else -> if (isDefaultIgnorable(cp) || cp == BRAILLE_BLANK) HiddenCharKind.INVISIBLE else null
    }

    private const val BRAILLE_BLANK = 0x2800

    /** Unicode Default_Ignorable_Code_Point (hard-coded ranges): rendered as nothing by most fonts. */
    internal fun isDefaultIgnorable(cp: Int): Boolean = DEFAULT_IGNORABLE.any { cp in it }

    private val DEFAULT_IGNORABLE: List<IntRange> =
        listOf(
            0x00AD..0x00AD, 0x034F..0x034F, 0x061C..0x061C, 0x115F..0x1160, 0x17B4..0x17B5, 0x180B..0x180F,
            0x200B..0x200F, 0x202A..0x202E, 0x2060..0x206F, 0x3164..0x3164, 0xFE00..0xFE0F, 0xFEFF..0xFEFF,
            0xFFA0..0xFFA0, 0xFFF0..0xFFF8, 0x1BCA0..0x1BCA3, 0x1D173..0x1D17A, 0xE0000..0xE0FFF,
        )

    private val BIDI_CONTROLS: Set<Int> =
        buildSet {
            add(0x061C)
            add(0x200E)
            add(0x200F)
            addAll(0x202A..0x202E)
            addAll(0x2066..0x2069)
        }

    private val ZERO_WIDTH: Set<Int> = setOf(0x200B, 0x200C, 0x200D, 0x2060, 0xFEFF, 0x180E)
}

/** NFC form of [s], unchanged (same instance) when already normalized. */
internal fun nfc(s: String): String =
    if (Normalizer.isNormalized(s, Normalizer.Form.NFC)) s else Normalizer.normalize(s, Normalizer.Form.NFC)
