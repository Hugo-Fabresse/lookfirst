package com.lookfirst.domain.decoder

import com.lookfirst.domain.model.HostKind

private const val HEX_RADIX = 16
private const val IPV4_LABELS = 4
private const val MAX_IPV4_OCTET = 255
private const val MAX_OCTET_DIGITS = 3
private const val ASCII_MAX = 0x7F
private const val HEX_PREFIX_LENGTH = 2

/** Recognizes the numeric host spellings browsers accept as IPv4 (decimal, octal-looking, hex, shortened). */
internal object HostNumbers {
    /** Dotted decimal IPv4 is IPV4; single numbers, hex, octal-looking forms and shortened forms are IPV4_OBFUSCATED. */
    fun classify(host: String): HostKind {
        val labels = host.removeSuffix(".").split('.')
        if (labels.size > IPV4_LABELS || !labels.all { isNumericLabel(it) }) return HostKind.DOMAIN
        val strict = labels.size == IPV4_LABELS && labels.all { isStrictOctet(it) }
        return if (strict) HostKind.IPV4 else HostKind.IPV4_OBFUSCATED
    }

    fun isAsciiHex(c: Char): Boolean = c.code <= ASCII_MAX && Character.digit(c, HEX_RADIX) >= 0

    private fun isNumericLabel(label: String): Boolean {
        if (label.isEmpty()) return false
        if (label.length >= HEX_PREFIX_LENGTH && label.startsWith("0x", ignoreCase = true)) {
            return label.substring(HEX_PREFIX_LENGTH).all { isAsciiHex(it) }
        }
        return label.all { it in '0'..'9' }
    }

    private fun isStrictOctet(label: String): Boolean {
        val plainDigits = label.length <= MAX_OCTET_DIGITS && label.all { it in '0'..'9' }
        return plainDigits && (label.length == 1 || label[0] != '0') && label.toInt() <= MAX_IPV4_OCTET
    }
}
