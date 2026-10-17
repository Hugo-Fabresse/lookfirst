package com.lookfirst.domain.host

import com.lookfirst.domain.decoder.HostNumbers
import com.lookfirst.domain.model.HostKind
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

private const val MAX_RAW_HOST_LENGTH = 1024
private const val ESCAPE_LENGTH = 3
private const val HEX_RADIX = 16
private const val ASCII_MAX = 0x7F
private const val FORBIDDEN_ASCII = "\"<>\\^`{|}@[]:#/?%"

/** Why a host could not be normalized. Never carries scanned text. */
enum class HostError {
    EMPTY,
    TOO_LONG,
    BAD_PERCENT_ENCODING,

    /** A character that is not allowed in a host once mapped (space, control, `@`, `/`, `#`...). */
    FORBIDDEN_CHARACTER,
    BAD_PUNYCODE,
    BAD_LABEL,
    BAD_JOINER,
    BAD_BIDI,
    EMPTY_LABEL,
    LABEL_TOO_LONG,

    /** The embedded Unicode data could not be loaded: nothing can be said about the host. */
    DATA_UNAVAILABLE,
}

/**
 * A host after UTS #46 processing. [ascii] (punycode) is what a browser would receive and what is analysed;
 * [unicode] is the same name as a person reads it. Both have no trailing dot ([hadTrailingDot] says there was one)
 * and the same number of labels.
 */
data class NormalizedHost(
    val ascii: String,
    val unicode: String,
    val kind: HostKind,
    val hadTrailingDot: Boolean,
    val wasPercentEncoded: Boolean,
) {
    val asciiLabels: List<String> get() = ascii.split('.')
    val unicodeLabels: List<String> get() = unicode.split('.')

    /** True when the Unicode form differs from the ASCII one, i.e. the name contains an IDN label. */
    val isInternational: Boolean get() = ascii != unicode
}

sealed interface HostResult {
    data class Normalized(val host: NormalizedHost) : HostResult

    data class Invalid(val error: HostError) : HostResult
}

/**
 * The single place where a scanned host becomes a comparable name: percent-decoding, UTS #46 mapping (fullwidth
 * forms, `。`, upper case), validation, Punycode, then a new [HostKind] (a fullwidth `１２７.０.０.１` is an IPv4).
 * Parameters follow the WHATWG URL standard (no STD3 rules, no hyphen check) plus DNS length limits.
 */
object HostNormalizer {
    private val OPTIONS =
        Uts46Options(useStd3AsciiRules = false, checkHyphens = false, verifyDnsLength = true, allowTrailingDot = true)

    /** [rawHost] is the host as typed (not an IPv6 literal). Pure and bounded. */
    fun normalize(rawHost: String): HostResult {
        if (rawHost.length > MAX_RAW_HOST_LENGTH) return HostResult.Invalid(HostError.TOO_LONG)
        val tables = UnicodeTables.instance ?: return HostResult.Invalid(HostError.DATA_UNAVAILABLE)
        val percent = rawHost.indexOf('%') >= 0
        val decoded = if (percent) percentDecode(rawHost) else rawHost
        if (decoded == null) return HostResult.Invalid(HostError.BAD_PERCENT_ENCODING)
        return when (val result = Uts46.process(decoded, OPTIONS, tables)) {
            is Uts46Result.Failure -> HostResult.Invalid(toHostError(result.error))
            is Uts46Result.Ok -> finish(result, percent)
        }
    }

    private fun finish(result: Uts46Result.Ok, percent: Boolean): HostResult {
        val trailingDot = result.ascii.endsWith('.')
        val ascii = result.ascii.removeSuffix(".")
        val unicode = result.unicode.removeSuffix(".")
        return when {
            ascii.isEmpty() -> HostResult.Invalid(HostError.EMPTY)
            ascii.any { it.code <= ' '.code || it.code >= ASCII_MAX || it in FORBIDDEN_ASCII } ->
                HostResult.Invalid(HostError.FORBIDDEN_CHARACTER)
            else -> HostResult.Normalized(
                NormalizedHost(ascii, unicode, HostNumbers.classify(ascii), trailingDot, percent),
            )
        }
    }

    private fun toHostError(error: Uts46Error): HostError = when (error) {
        Uts46Error.TOO_LONG -> HostError.TOO_LONG
        Uts46Error.DISALLOWED, Uts46Error.INVALID_CHARACTER -> HostError.FORBIDDEN_CHARACTER
        Uts46Error.EMPTY -> HostError.EMPTY
        Uts46Error.EMPTY_LABEL -> HostError.EMPTY_LABEL
        Uts46Error.LABEL_TOO_LONG -> HostError.LABEL_TOO_LONG
        Uts46Error.PUNYCODE -> HostError.BAD_PUNYCODE
        Uts46Error.JOINER -> HostError.BAD_JOINER
        Uts46Error.BIDI -> HostError.BAD_BIDI
        Uts46Error.NOT_NFC, Uts46Error.HYPHEN, Uts46Error.LEADING_MARK -> HostError.BAD_LABEL
    }

    /** `%XX` to bytes, then strict UTF-8. Null for a truncated or non-hex escape and for invalid UTF-8. */
    private fun percentDecode(text: String): String? {
        val bytes = ByteArrayOutputStream(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '%') {
                val value = hexValue(text, i + 1) ?: return null
                bytes.write(value)
                i += ESCAPE_LENGTH
            } else {
                val cp = text.codePointAt(i)
                if (cp in Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code) return null
                val chunk = String(Character.toChars(cp)).toByteArray(StandardCharsets.UTF_8)
                bytes.write(chunk, 0, chunk.size)
                i += Character.charCount(cp)
            }
        }
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray()))
                .toString()
        } catch (ignored: CharacterCodingException) {
            null
        }
    }

    private fun hexValue(text: String, at: Int): Int? {
        if (at + 1 >= text.length) return null
        val high = Character.digit(text[at], HEX_RADIX)
        val low = Character.digit(text[at + 1], HEX_RADIX)
        val ascii = text[at].code <= ASCII_MAX && text[at + 1].code <= ASCII_MAX
        return if (high < 0 || low < 0 || !ascii) null else high * HEX_RADIX + low
    }
}
