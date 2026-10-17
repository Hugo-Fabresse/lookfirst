package com.lookfirst.domain.host

import java.math.BigInteger

private const val HEX_RADIX = 16
private const val OCTAL_RADIX = 8
private const val DECIMAL_RADIX = 10
private const val MAX_PARTS = 4
private const val OCTET_RANGE = 256
private const val HEX_PREFIX_LENGTH = 2
private const val ASCII_LIMIT = 0x80
private const val BITS_PER_OCTET = 8
private val OCTET_MASK: BigInteger = BigInteger.valueOf(255)

/** WHATWG "ends in a number" and IPv4 parser checks, so that `foo.1` or `1.2.3.4.5` are not treated as names. */
internal object Ipv4Check {
    /** True when the last label is only ASCII digits or `0x` followed by hex digits. */
    fun endsInNumber(host: String): Boolean {
        val last = host.substringAfterLast('.')
        return last.isNotEmpty() && (last.all { it in '0'..'9' } || parseNumber(last) != null)
    }

    /** True when [host] parses as an IPv4 address (1 to 4 parts, decimal / octal / hex, last part fills the rest). */
    fun isValid(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size > MAX_PARTS) return false
        val numbers = parts.map { parseNumber(it) ?: return false }
        val limit = BigInteger.valueOf(OCTET_RANGE.toLong()).pow(MAX_PARTS + 1 - parts.size)
        return numbers.dropLast(1).all { it < BigInteger.valueOf(OCTET_RANGE.toLong()) } && numbers.last() < limit
    }

    /**
     * The dotted-decimal form a browser connects to (`0x7f.1` becomes `127.0.0.1`), or null if [host] is not a valid
     * IPv4 address. The last part fills the remaining bytes (WHATWG IPv4 parser).
     */
    fun toDotted(host: String): String? {
        if (!isValid(host)) return null
        val numbers = host.split('.').map { parseNumber(it) ?: return null }
        var value = numbers.last()
        numbers.dropLast(1).forEachIndexed { index, part ->
            value += part.shiftLeft(BITS_PER_OCTET * (MAX_PARTS - 1 - index))
        }
        return (MAX_PARTS - 1 downTo 0).joinToString(".") {
            value.shiftRight(BITS_PER_OCTET * it).and(OCTET_MASK).toString()
        }
    }

    private fun parseNumber(part: String): BigInteger? {
        if (part.isEmpty()) return null
        val hex = part.length >= HEX_PREFIX_LENGTH && (part.startsWith("0x") || part.startsWith("0X"))
        val digits = if (hex) part.substring(HEX_PREFIX_LENGTH) else part
        val radix = when {
            hex -> HEX_RADIX
            part.length > 1 && part[0] == '0' -> OCTAL_RADIX
            else -> DECIMAL_RADIX
        }
        val body = if (hex || radix == DECIMAL_RADIX) digits else digits.substring(1)
        if (body.isEmpty()) return BigInteger.ZERO
        return if (body.all {
                it.code < ASCII_LIMIT && Character.digit(it, radix) >= 0
            }
        ) {
            BigInteger(body, radix)
        } else {
            null
        }
    }
}
