package com.lookfirst.domain.decoder

import com.lookfirst.domain.model.QueryParam
import com.lookfirst.domain.model.Untrusted
import java.io.ByteArrayOutputStream
import java.util.Locale

private const val HEX_RADIX = 16
private const val HEX_DIGITS_PER_ESCAPE = 2
private const val ASCII_LIMIT = 0x80

/** Percent-decoding and query splitting. Linear, no regex; callers pass already size-bounded text. */
internal object PercentCodec {
    /** Upper bound on the parameters of one query; more is treated as hostile and rejected. */
    const val MAX_PARAMS = 32

    /** Lenient decoding: a malformed `%zz` stays literal, invalid UTF-8 becomes U+FFFD. */
    fun decode(s: String): String {
        if (s.indexOf('%') < 0) return s
        val out = StringBuilder(s.length)
        val bytes = ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val value = hexByteAt(s, i)
            if (value >= 0) {
                bytes.write(value)
                i += 1 + HEX_DIGITS_PER_ESCAPE
            } else {
                flush(bytes, out)
                out.append(s[i])
                i++
            }
        }
        flush(bytes, out)
        return nfc(out.toString())
    }

    private fun flush(bytes: ByteArrayOutputStream, out: StringBuilder) {
        if (bytes.size() > 0) {
            out.append(String(bytes.toByteArray(), Charsets.UTF_8))
            bytes.reset()
        }
    }

    private fun hexByteAt(s: String, i: Int): Int {
        if (s[i] != '%' || i + HEX_DIGITS_PER_ESCAPE >= s.length) return -1
        val hi = Character.digit(s[i + 1], HEX_RADIX)
        val lo = Character.digit(s[i + 2], HEX_RADIX)
        val ascii = isAscii(s[i + 1]) && isAscii(s[i + 2])
        return if (hi < 0 || lo < 0 || !ascii) -1 else hi * HEX_RADIX + lo
    }

    private fun isAscii(c: Char): Boolean = c.code < ASCII_LIMIT

    /** Splits `a=b&c=d` into decoded pairs, or null when there are more than [MAX_PARAMS]. Names are lowercased. */
    fun parseQuery(query: String): List<QueryParam>? {
        val params = ArrayList<QueryParam>()
        var start = 0
        while (start <= query.length) {
            val end = query.indexOf('&', start).let { if (it < 0) query.length else it }
            if (end > start) {
                if (params.size >= MAX_PARAMS) return null
                params += toParam(query.substring(start, end))
            }
            start = end + 1
        }
        return params
    }

    private fun toParam(pair: String): QueryParam {
        val eq = pair.indexOf('=')
        val name = if (eq < 0) pair else pair.substring(0, eq)
        val value = if (eq < 0) "" else pair.substring(eq + 1)
        return QueryParam(decode(name).lowercase(Locale.ROOT), Untrusted(decode(value)))
    }
}

internal fun List<QueryParam>.firstValue(name: String): Untrusted? = firstOrNull { it.name == name }?.value

/** True when any of [names] appears more than once: ambiguous (which one will the app use?), so rejected. */
internal fun List<QueryParam>.hasDuplicate(names: Set<String>): Boolean =
    filter { it.name in names }.groupingBy { it.name }.eachCount().any { it.value > 1 }

/**
 * Scanner for `KEY:value;KEY:value;;` formats (WIFI:, MATMSG:) with `\` escapes for `\ ; , : "`.
 * Fields come in any order. Linear, bounded by [MAX_FIELDS]; returns null when malformed.
 */
internal object EscapedFields {
    const val MAX_FIELDS = 16
    private const val MAX_KEY = 16
    private const val ESCAPABLE = "\\;,:\""

    fun scan(body: String): List<Pair<String, String>>? {
        val fields = ArrayList<Pair<String, String>>()
        var i = 0
        while (i < body.length) {
            if (body[i] == ';') {
                i++
                continue
            }
            if (fields.size >= MAX_FIELDS) return null
            val keyEnd = keyEnd(body, i) ?: return null
            val key = body.substring(i, keyEnd).uppercase(Locale.ROOT)
            val value = StringBuilder()
            i = readValue(body, keyEnd + 1, value)
            fields += key to nfc(value.toString())
        }
        return fields
    }

    private fun keyEnd(body: String, from: Int): Int? {
        val limit = minOf(body.length, from + MAX_KEY + 1)
        var j = from
        while (j < limit && body[j] != ':' && body[j] != ';') j++
        return if (j < limit && body[j] == ':') j else null
    }

    private fun isEscape(body: String, at: Int): Boolean =
        body[at] == '\\' && at + 1 < body.length && body[at + 1] in ESCAPABLE

    /** Appends the unescaped value to [out] and returns the index after its terminating `;`. */
    private fun readValue(body: String, from: Int, out: StringBuilder): Int {
        var j = from
        while (j < body.length && body[j] != ';') {
            if (isEscape(body, j)) {
                out.append(body[j + 1])
                j += 2
            } else {
                out.append(body[j])
                j++
            }
        }
        return j + 1
    }
}
