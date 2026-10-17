package com.lookfirst.data.reputation

import java.io.ByteArrayOutputStream
import java.security.MessageDigest

private const val HEX_RADIX = 16
private const val NIBBLE_BITS = 4
private const val NIBBLE_MASK = 0xF
private const val BYTE_MASK = 0xFF
private const val BASE64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
private const val BASE64_GROUP = 4
private const val BASE64_MAX_PADDING = 2
private const val SEXTET_BITS = 6
private const val BASE64_MAX_CHARS = 4096

/** Small, strict encodings. `java.util.Base64` needs API 26 and minSdk is 23, so the verifier carries its own. */
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

/** Standard alphabet, padding required, no whitespace, no non-canonical trailing bits. Null on any deviation. */
internal object StrictBase64 {
    fun decode(text: String): ByteArray? {
        if (text.isEmpty() || text.length > BASE64_MAX_CHARS || text.length % BASE64_GROUP != 0) return null
        val padding = text.takeLastWhile { it == '=' }.length
        if (padding > BASE64_MAX_PADDING) return null
        val body = text.dropLast(padding)
        val out = ByteArrayOutputStream(text.length)
        var buffer = 0
        var bits = 0
        for (c in body) {
            val value = BASE64_ALPHABET.indexOf(c)
            if (value < 0) return null
            buffer = (buffer shl SEXTET_BITS) or value
            bits += SEXTET_BITS
            if (bits >= Byte.SIZE_BITS) {
                bits -= Byte.SIZE_BITS
                out.write((buffer shr bits) and BYTE_MASK)
                buffer = buffer and ((1 shl bits) - 1)
            }
        }
        return if (buffer == 0) out.toByteArray() else null
    }
}

internal fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
