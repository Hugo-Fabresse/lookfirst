package com.lookfirst.data.reputation

import java.security.MessageDigest

private const val HEX_RADIX = 16
private const val NIBBLE_BITS = 4
private const val NIBBLE_MASK = 0xF
private const val BYTE_MASK = 0xFF

/** Small, strict encodings (hexadecimal only; minSdk 23 has no `java.util.HexFormat`). */
internal object Hex {
    fun encode(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and BYTE_MASK
            out.append(Character.forDigit(v ushr NIBBLE_BITS, HEX_RADIX))
            out.append(Character.forDigit(v and NIBBLE_MASK, HEX_RADIX))
        }
        return out.toString()
    }

    /** True for lowercase hexadecimal of exactly [length] characters. */
    fun isLowerHex(text: String, length: Int): Boolean =
        text.length == length && text.all { it in '0'..'9' || it in 'a'..'f' }

    fun decodeLower(text: String): ByteArray? {
        if (text.length % 2 != 0 || !isLowerHex(text, text.length)) return null
        return ByteArray(text.length / 2) { i ->
            val high = Character.digit(text[2 * i], HEX_RADIX)
            val low = Character.digit(text[2 * i + 1], HEX_RADIX)
            ((high shl NIBBLE_BITS) or low).toByte()
        }
    }
}

internal fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
