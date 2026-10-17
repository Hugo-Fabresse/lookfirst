package com.lookfirst.data.reputation

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

private const val MAX_INTEGER_DIGITS = 15
private const val UNICODE_ESCAPE_LENGTH = 4
private const val HEX_RADIX = 16
private const val FIRST_PRINTABLE = 0x20
private const val DECIMAL_OFFSET = 10

/** JSON values the bundle manifest can contain. Numbers are integers only. */
internal sealed interface Json {
    data class Obj(val fields: Map<String, Json>) : Json

    data class Arr(val items: List<Json>) : Json

    data class Str(val value: String) : Json

    data class Num(val value: Long) : Json

    data class Bool(val value: Boolean) : Json

    data object Null : Json
}

/**
 * Bounds of one parse. [allowFractions]: a number with a fraction or an exponent is read as its integer part (a
 * server's answer may carry one in a field nobody reads); otherwise it is refused.
 */
internal data class JsonLimits(
    val maxDepth: Int,
    val maxNodes: Int,
    val maxStringLength: Int,
    val allowFractions: Boolean = false,
) {
    companion object {
        /** The signed manifest. */
        val MANIFEST = JsonLimits(maxDepth = 6, maxNodes = 2_000, maxStringLength = 1_024)

        /** The RDAP bootstrap file of the bundle (a few thousand values, shape checked after). */
        val BOOTSTRAP = JsonLimits(maxDepth = 6, maxNodes = 20_000, maxStringLength = 1_024)

        /** A registry's answer (at most 256 KiB, untrusted): nested vCards, long notices. */
        val RDAP = JsonLimits(maxDepth = 24, maxNodes = 20_000, maxStringLength = 16 * 1_024, allowFractions = true)
    }
}

/**
 * Strict, bounded JSON reader (the manifest only after its signature is verified; the bootstrap and RDAP answers
 * under their own [JsonLimits]). Rejects, by returning null: invalid UTF-8, duplicate keys, floats (unless the limits
 * allow them), leading zeros, control characters in strings, trailing bytes, depth or value count above the limits.
 * Linear, no regex, no reflection.
 */
internal object StrictJson {
    fun parse(bytes: ByteArray, limits: JsonLimits = JsonLimits.MANIFEST): Json? {
        val text = decodeUtf8(bytes) ?: return null
        val reader = Reader(text, limits)
        reader.skipWhitespace()
        val value = reader.readValue(0) ?: return null
        reader.skipWhitespace()
        return if (reader.atEnd()) value else null
    }

    private fun decodeUtf8(bytes: ByteArray): String? = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (@Suppress("SwallowedException") ignored: CharacterCodingException) {
        null
    }

    private class Reader(private val text: String, private val limits: JsonLimits) {
        private var pos = 0
        private var nodes = 0

        fun atEnd(): Boolean = pos == text.length

        fun skipWhitespace() {
            while (pos < text.length && text[pos] in " \t\r\n") pos++
        }

        fun readValue(depth: Int): Json? {
            if (depth > limits.maxDepth || ++nodes > limits.maxNodes || atEnd()) return null
            return when (text[pos]) {
                '{' -> readObject(depth)
                '[' -> readArray(depth)
                '"' -> readString()?.let { Json.Str(it) }
                't' -> literal("true", Json.Bool(true))
                'f' -> literal("false", Json.Bool(false))
                'n' -> literal("null", Json.Null)
                else -> readInteger()
            }
        }

        private fun literal(word: String, value: Json): Json? =
            if (text.startsWith(word, pos)) value.also { pos += word.length } else null

        @Suppress("ReturnCount") // one exit per malformed shape
        private fun readObject(depth: Int): Json? {
            pos++
            val fields = LinkedHashMap<String, Json>()
            skipWhitespace()
            if (consume('}')) return Json.Obj(fields)
            while (true) {
                skipWhitespace()
                val key = (if (peek() == '"') readString() else null) ?: return null
                skipWhitespace()
                if (!consume(':')) return null
                skipWhitespace()
                val value = readValue(depth + 1) ?: return null
                if (fields.put(key, value) != null) return null
                skipWhitespace()
                if (consume('}')) return Json.Obj(fields)
                if (!consume(',')) return null
            }
        }

        private fun readArray(depth: Int): Json? {
            pos++
            val items = ArrayList<Json>()
            skipWhitespace()
            if (consume(']')) return Json.Arr(items)
            while (true) {
                skipWhitespace()
                items += readValue(depth + 1) ?: return null
                skipWhitespace()
                if (consume(']')) return Json.Arr(items)
                if (!consume(',')) return null
            }
        }

        private fun readString(): String? {
            pos++
            val out = StringBuilder()
            while (pos < text.length) {
                val c = text[pos++]
                when {
                    c == '"' -> return out.toString()
                    c.code < FIRST_PRINTABLE || out.length >= limits.maxStringLength -> return null
                    c == '\\' -> out.append(readEscape() ?: return null)
                    else -> out.append(c)
                }
            }
            return null
        }

        private fun readEscape(): String? {
            if (atEnd()) return null
            return when (val e = text[pos++]) {
                '"', '\\', '/' -> e.toString()
                'b' -> "\b"
                'f' -> "\u000C"
                'n' -> "\n"
                'r' -> "\r"
                't' -> "\t"
                'u' -> readUnicodeEscape()
                else -> null
            }
        }

        /** `\uXXXX` with exactly four ASCII hex digits. NUL and unpaired surrogates are refused. */
        private fun readUnicodeEscape(): String? {
            val first = readHex4() ?: return null
            val c = first.toChar()
            return when {
                first == 0 -> null
                c.isLowSurrogate() -> null
                c.isHighSurrogate() -> {
                    if (!text.startsWith("\\u", pos)) return null
                    pos += 2
                    val low = readHex4()?.toChar() ?: return null
                    if (low.isLowSurrogate()) String(charArrayOf(c, low)) else null
                }
                else -> c.toString()
            }
        }

        private fun readHex4(): Int? {
            if (pos + UNICODE_ESCAPE_LENGTH > text.length) return null
            var value = 0
            for (i in 0 until UNICODE_ESCAPE_LENGTH) {
                val digit = hexValue(text[pos + i])
                if (digit < 0) return null
                value = value * HEX_RADIX + digit
            }
            pos += UNICODE_ESCAPE_LENGTH
            return value
        }

        private fun hexValue(c: Char): Int = when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + DECIMAL_OFFSET
            in 'A'..'F' -> c - 'A' + DECIMAL_OFFSET
            else -> -1
        }

        private fun readInteger(): Json? {
            val start = pos
            if (peek() == '-') pos++
            val digitsStart = pos
            while (pos < text.length && text[pos] in '0'..'9') pos++
            val digits = pos - digitsStart
            val leadingZero = digits > 1 && text[digitsStart] == '0'
            if (digits == 0 || digits > MAX_INTEGER_DIGITS || leadingZero) return null
            val integerEnd = pos
            if (!fractionAccepted()) return null
            return Json.Num(text.substring(start, integerEnd).toLong())
        }

        /** True when no fraction or exponent follows, or one follows and the limits allow it and it is well formed. */
        private fun fractionAccepted(): Boolean {
            val follows = pos < text.length && text[pos] in ".eE"
            return !follows || (limits.allowFractions && skipFraction())
        }

        /** `.digits`, then `e|E [+|-] digits`, at least one digit each. False if malformed. */
        private fun skipFraction(): Boolean {
            if (consume('.') && !skipDigits()) return false
            if (peek() == 'e' || peek() == 'E') {
                pos++
                if (peek() == '+' || peek() == '-') pos++
                if (!skipDigits()) return false
            }
            return true
        }

        private fun skipDigits(): Boolean {
            val from = pos
            while (pos < text.length && text[pos] in '0'..'9') pos++
            return pos > from
        }

        private fun peek(): Char? = if (pos < text.length) text[pos] else null

        private fun consume(c: Char): Boolean = if (peek() == c) true.also { pos++ } else false
    }
}
