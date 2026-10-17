package com.lookfirst.domain.host

import java.text.Normalizer

private const val ASCII_LIMIT = 0x80
private const val ACE_PREFIX = "xn--"
private const val MAX_LABEL_LENGTH = 63
private const val MAX_DOMAIN_LENGTH = 253
private const val MAX_INPUT_LENGTH = 1024
private const val HYPHEN_POSITION = 2

/** UTS #46 processing flags. Transitional processing is not supported; CheckJoiners and CheckBidi are always on. */
internal data class Uts46Options(
    val useStd3AsciiRules: Boolean,
    val checkHyphens: Boolean,
    val verifyDnsLength: Boolean,
    /** Accept one empty last label ("example.com.", the DNS root); the conformance data wants it rejected. */
    val allowTrailingDot: Boolean = false,
)

internal enum class Uts46Error {
    TOO_LONG,
    DISALLOWED,
    EMPTY,
    EMPTY_LABEL,
    LABEL_TOO_LONG,
    PUNYCODE,
    NOT_NFC,
    HYPHEN,
    LEADING_MARK,
    INVALID_CHARACTER,
    JOINER,
    BIDI,
}

internal sealed interface Uts46Result {
    /** [unicode] and [ascii] keep a trailing root dot if the input had one. */
    data class Ok(val unicode: String, val ascii: String) : Uts46Result

    data class Failure(val error: Uts46Error) : Uts46Result
}

/**
 * UTS #46 (Unicode IDNA Compatibility Processing), non-transitional, from the embedded IdnaMappingTable.
 * Linear in the (bounded) input, never throws. Conformance: IdnaTestV2.txt (see Uts46ConformanceTest).
 */
internal object Uts46 {
    fun process(domain: String, options: Uts46Options, tables: UnicodeTables): Uts46Result {
        if (domain.length > MAX_INPUT_LENGTH) return Uts46Result.Failure(Uts46Error.TOO_LONG)
        val mapped = map(domain, options, tables) ?: return Uts46Result.Failure(Uts46Error.DISALLOWED)
        val labels = Normalizer.normalize(mapped, Normalizer.Form.NFC).split('.')
        val unicodeLabels = ArrayList<String>(labels.size)
        val failure = convertAll(labels, unicodeLabels, options, tables)
        return when {
            failure != null -> Uts46Result.Failure(failure)
            !ContextRules.bidiValid(unicodeLabels, tables) -> Uts46Result.Failure(Uts46Error.BIDI)
            else -> toAscii(unicodeLabels, options)
        }
    }

    /** Converts every label into [out]; the empty last label of "a.b." is the root and passes. */
    private fun convertAll(
        labels: List<String>,
        out: MutableList<String>,
        options: Uts46Options,
        tables: UnicodeTables,
    ): Uts46Error? {
        for ((index, label) in labels.withIndex()) {
            val isRoot = options.allowTrailingDot && index == labels.lastIndex && index > 0
            if (label.isEmpty() && !isRoot && options.verifyDnsLength) {
                return if (labels.size == 1) Uts46Error.EMPTY else Uts46Error.EMPTY_LABEL
            }
            val converted = if (label.isEmpty()) LabelResult.Ok(label) else convertLabel(label, options, tables)
            when (converted) {
                is LabelResult.Failure -> return converted.error
                is LabelResult.Ok -> out += converted.label
            }
        }
        return null
    }

    private sealed interface LabelResult {
        class Ok(val label: String) : LabelResult

        class Failure(val error: Uts46Error) : LabelResult
    }

    private fun map(domain: String, options: Uts46Options, tables: UnicodeTables): String? {
        val out = StringBuilder(domain.length)
        var i = 0
        while (i < domain.length) {
            val cp = domain.codePointAt(i)
            i += Character.charCount(cp)
            val entry = tables.idnaEntry(cp) ?: return null
            when (entry.status) {
                IdnaStatus.VALID, IdnaStatus.DEVIATION -> out.appendCodePoint(cp)
                IdnaStatus.IGNORED -> Unit
                IdnaStatus.MAPPED -> entry.mapping.forEach { out.appendCodePoint(it) }
                IdnaStatus.STD3_VALID -> {
                    if (options.useStd3AsciiRules) return null
                    out.appendCodePoint(cp)
                }
                IdnaStatus.STD3_MAPPED -> {
                    if (options.useStd3AsciiRules) return null
                    entry.mapping.forEach { out.appendCodePoint(it) }
                }
            }
        }
        return out.toString()
    }

    /** Punycode-decodes an `xn--` label, then checks the validity criteria of UTS #46 section 4.1. */
    private fun convertLabel(label: String, options: Uts46Options, tables: UnicodeTables): LabelResult {
        var unicode = label
        if (label.startsWith(ACE_PREFIX)) {
            val encoded = label.substring(ACE_PREFIX.length)
            val cps = if (label.any { it.code >= ASCII_LIMIT }) null else Punycode.decode(encoded)
            if (cps == null || cps.isEmpty() || cps.all { it < ASCII_LIMIT }) {
                return LabelResult.Failure(Uts46Error.PUNYCODE)
            }
            unicode = String(cps, 0, cps.size)
        }
        val error = validateLabel(unicode, options, tables)
        return if (error == null) LabelResult.Ok(unicode) else LabelResult.Failure(error)
    }

    private fun validateLabel(label: String, options: Uts46Options, tables: UnicodeTables): Uts46Error? {
        val cps = codePointsOf(label)
        return when {
            !Normalizer.isNormalized(label, Normalizer.Form.NFC) -> Uts46Error.NOT_NFC
            hyphenError(label, options.checkHyphens) -> Uts46Error.HYPHEN
            cps.isNotEmpty() && tables.isMark(cps[0]) -> Uts46Error.LEADING_MARK
            cps.any { !isValidCodePoint(it, options, tables) } -> Uts46Error.INVALID_CHARACTER
            !ContextRules.joinersValid(cps, tables) -> Uts46Error.JOINER
            else -> null
        }
    }

    private fun hyphenError(label: String, checkHyphens: Boolean): Boolean = if (checkHyphens) {
        (label.length > HYPHEN_POSITION + 1 && label[HYPHEN_POSITION] == '-' && label[HYPHEN_POSITION + 1] == '-') ||
            label.startsWith('-') ||
            label.endsWith('-')
    } else {
        label.startsWith(ACE_PREFIX)
    }

    private fun isValidCodePoint(cp: Int, options: Uts46Options, tables: UnicodeTables): Boolean {
        if (cp == '.'.code) return false
        // Since UTS #46 15.1 the table has no STD3 statuses: the ASCII restriction is part of the validity criteria.
        if (options.useStd3AsciiRules && cp < ASCII_LIMIT && !isLetterDigitHyphen(cp)) return false
        return when (tables.idnaEntry(cp)?.status) {
            IdnaStatus.VALID, IdnaStatus.DEVIATION -> true
            IdnaStatus.STD3_VALID -> !options.useStd3AsciiRules
            else -> false
        }
    }

    private fun isLetterDigitHyphen(cp: Int): Boolean =
        cp in 'a'.code..'z'.code || cp in '0'.code..'9'.code || cp == '-'.code

    private fun toAscii(unicodeLabels: List<String>, options: Uts46Options): Uts46Result {
        val asciiLabels = ArrayList<String>(unicodeLabels.size)
        for (label in unicodeLabels) {
            val cps = codePointsOf(label)
            val ascii = if (cps.all { it < ASCII_LIMIT }) label else Punycode.encode(cps)?.let { ACE_PREFIX + it }
            if (ascii == null) return Uts46Result.Failure(Uts46Error.PUNYCODE)
            if (options.verifyDnsLength && ascii.length > MAX_LABEL_LENGTH) {
                return Uts46Result.Failure(Uts46Error.LABEL_TOO_LONG)
            }
            asciiLabels += ascii
        }
        val joined = asciiLabels.joinToString(".")
        if (options.verifyDnsLength && joined.removeSuffix(".").length > MAX_DOMAIN_LENGTH) {
            return Uts46Result.Failure(Uts46Error.TOO_LONG)
        }
        return Uts46Result.Ok(unicode = unicodeLabels.joinToString("."), ascii = joined)
    }
}
