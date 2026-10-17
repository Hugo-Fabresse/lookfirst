package com.lookfirst.domain.host

private const val BASE = 36
private const val T_MIN = 1
private const val T_MAX = 26
private const val SKEW = 38
private const val DAMP = 700
private const val INITIAL_BIAS = 72
private const val INITIAL_N = 128
private const val DELIMITER = '-'
private const val MAX_CODE_POINT = 0x10FFFF
private const val ASCII_LIMIT = 0x80
private const val LETTERS = 26
private const val LOW_SURROGATE_START = 0xD800
private const val LOW_SURROGATE_END = 0xDFFF

/** Maximum code points kept when decoding: a DNS label is 63 characters, this leaves room and bounds the cost. */
internal const val PUNYCODE_MAX_OUTPUT = 256

/** Punycode, RFC 3492, on code points. Overflow and malformed input give null, never an exception. */
internal object Punycode {
    /** [input] is what follows `xn--`. Null if it is not valid Punycode. */
    fun decode(input: String): IntArray? =
        if (input.length > PUNYCODE_MAX_OUTPUT || input.any { it.code >= ASCII_LIMIT }) null else Decoder(input).run()

    /** One decoding pass over [input] (RFC 3492 section 6.2); the state is the code point and the bias. */
    private class Decoder(private val input: String) {
        private val output = ArrayList<Int>()
        private var n = INITIAL_N
        private var bias = INITIAL_BIAS
        private var index = 0

        fun run(): IntArray? {
            val delimiter = input.lastIndexOf(DELIMITER)
            for (k in 0 until maxOf(delimiter, 0)) output += input[k].code
            index = delimiter + 1
            var i = 0L
            while (index < input.length) {
                val value = readValue(i) ?: return null
                i = insert(value, i) ?: return null
            }
            return output.toIntArray()
        }

        /** Reads one generalized variable-length integer and adds it to [start]; null on overflow or a bad digit. */
        private fun readValue(start: Long): Long? {
            var value = start
            var weight = 1L
            var k = BASE
            var done = false
            while (!done && index < input.length) {
                val digit = digitValue(input[index++])
                if (digit < 0) return null
                value += digit * weight
                val t = threshold(k, bias)
                done = digit < t
                weight *= BASE - t
                k += BASE
                if (value > Int.MAX_VALUE || (!done && weight > Int.MAX_VALUE)) return null
            }
            return if (done) value else null
        }

        /** Inserts the code point that [value] designates; returns the next position or null if it is invalid. */
        private fun insert(value: Long, previous: Long): Long? {
            val count = output.size + 1
            bias = adapt(value - previous, count, previous == 0L)
            val next = n + value / count
            val invalid = next > MAX_CODE_POINT || next in LOW_SURROGATE_START..LOW_SURROGATE_END
            if (invalid || count > PUNYCODE_MAX_OUTPUT) return null
            n = next.toInt()
            val position = (value % count).toInt()
            output.add(position, n)
            return position + 1L
        }
    }

    /** Encodes [codePoints] (no `xn--` prefix). Null on overflow or an unreasonably long input. */
    fun encode(codePoints: IntArray): String? {
        if (codePoints.size > PUNYCODE_MAX_OUTPUT) return null
        val out = StringBuilder()
        for (cp in codePoints) if (cp < ASCII_LIMIT) out.append(cp.toChar())
        val basicCount = out.length
        var handled = basicCount
        if (basicCount > 0) out.append(DELIMITER)
        var n = INITIAL_N
        var delta = 0L
        var bias = INITIAL_BIAS
        while (handled < codePoints.size) {
            val m = codePoints.filter { it >= n }.minOrNull() ?: return null
            delta += (m - n).toLong() * (handled + 1)
            if (delta > Int.MAX_VALUE) return null
            n = m
            for (cp in codePoints) {
                if (cp < n) delta++
                if (cp == n) {
                    appendVariableLength(out, delta, bias)
                    bias = adapt(delta, handled + 1, handled == basicCount)
                    delta = 0
                    handled++
                }
            }
            delta++
            n++
        }
        return out.toString()
    }

    private fun appendVariableLength(out: StringBuilder, value: Long, bias: Int) {
        var q = value
        var k = BASE
        while (true) {
            val t = threshold(k, bias)
            if (q < t) break
            out.append(digitChar((t + (q - t) % (BASE - t)).toInt()))
            q = (q - t) / (BASE - t)
            k += BASE
        }
        out.append(digitChar(q.toInt()))
    }

    private fun threshold(k: Int, bias: Int): Int = when {
        k <= bias -> T_MIN
        k >= bias + T_MAX -> T_MAX
        else -> k - bias
    }

    private fun adapt(delta: Long, numPoints: Int, first: Boolean): Int {
        var d = if (first) delta / DAMP else delta / 2
        d += d / numPoints
        var k = 0
        while (d > ((BASE - T_MIN) * T_MAX) / 2) {
            d /= BASE - T_MIN
            k += BASE
        }
        return (k + (BASE - T_MIN + 1) * d / (d + SKEW)).toInt()
    }

    private fun digitValue(c: Char): Int = when (c) {
        in 'a'..'z' -> c - 'a'
        in 'A'..'Z' -> c - 'A'
        in '0'..'9' -> c - '0' + LETTERS
        else -> -1
    }

    private fun digitChar(d: Int): Char = if (d < LETTERS) 'a' + d else '0' + (d - LETTERS)
}
