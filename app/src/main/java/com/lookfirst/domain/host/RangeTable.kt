package com.lookfirst.domain.host

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets

private const val HEX_RADIX = 16
private const val MAX_RESOURCE_BYTES = 1_000_000
private const val BUFFER_SIZE = 8192

/** Sorted, non-overlapping code point ranges, each with a value. Lookup is a binary search. */
internal class RangeTable<T : Any> private constructor(
    private val low: IntArray,
    private val high: IntArray,
    private val values: List<T>,
) {
    operator fun get(codePoint: Int): T? {
        var from = 0
        var to = low.size - 1
        while (from <= to) {
            val mid = (from + to) ushr 1
            when {
                codePoint < low[mid] -> to = mid - 1
                codePoint > high[mid] -> from = mid + 1
                else -> return values[mid]
            }
        }
        return null
    }

    companion object {
        /** Parses "LOW HIGH VALUE..." lines (hexadecimal); `#` lines and malformed lines are skipped. */
        fun <T : Any> parse(lines: List<String>, valueOf: (String) -> T?): RangeTable<T> {
            val entries = lines.filter { it.isNotEmpty() && it[0] != '#' }.mapNotNull { parseLine(it, valueOf) }
            return RangeTable(
                entries.map { it.low }.toIntArray(),
                entries.map { it.high }.toIntArray(),
                entries.map { it.value },
            )
        }

        private fun <T : Any> parseLine(line: String, valueOf: (String) -> T?): Entry<T>? {
            val parts = line.split(' ', limit = 3)
            val lo = parts.getOrNull(0)?.toIntOrNull(HEX_RADIX)
            val hi = parts.getOrNull(1)?.toIntOrNull(HEX_RADIX)
            val value = valueOf(parts.getOrElse(2) { "" })
            return if (lo == null || hi == null || hi < lo) null else value?.let { Entry(lo, hi, it) }
        }
    }

    private class Entry<T>(val low: Int, val high: Int, val value: T)
}

/** Reads the generated data files (scripts/gen-host-data.py) packaged as Java resources. Null when unreadable. */
internal object HostResources {
    fun lines(name: String): List<String>? = try {
        HostResources::class.java.getResourceAsStream("/lookfirst/host/$name")?.use { stream ->
            readBounded(stream)?.let { String(it, StandardCharsets.UTF_8).lines() }
        }
    } catch (ignored: IOException) {
        null
    }

    private fun readBounded(stream: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            if (out.size() > MAX_RESOURCE_BYTES) return null
        }
        return out.toByteArray()
    }
}
