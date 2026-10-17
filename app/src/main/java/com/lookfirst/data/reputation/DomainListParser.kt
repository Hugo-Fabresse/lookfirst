package com.lookfirst.data.reputation

import com.lookfirst.domain.host.HostNormalizer
import com.lookfirst.domain.host.HostResult
import com.lookfirst.domain.host.PublicSuffixList
import com.lookfirst.domain.model.HostKind
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream

private const val READ_CHUNK = 64 * 1024
private const val MAX_LINE_BYTES = 300
private const val MAX_OFFICIAL_DROPPED = 100
private const val INVALID_RATIO_DIVISOR = 50
private const val INITIAL_KEYS = 1024
private const val ASCII_DEL = 0x7F
private const val BYTE_MASK = 0xFF
private const val SPACE = 0x20
private const val BYTE_ORDER_MARK = '﻿'

internal sealed interface ParseOutcome {
    /** [keys] are sorted (unsigned) and unique; [droppedOfficial] counts entries naming an official domain. */
    class Parsed(val keys: LongArray, val droppedOfficial: Int) : ParseOutcome

    class Rejected(val reason: ListFailure) : ParseOutcome
}

/**
 * Reads a list of one host per line (comments start with `#`, CRLF or LF, blanks ignored) and turns it into sorted
 * index keys. The file is untrusted: it is streamed in fixed chunks, a line longer than [MAX_LINE_BYTES] is not
 * kept (it counts as invalid), and every host goes through [HostNormalizer], the same code that normalizes a
 * scanned host, so an entry and a lookup always agree. Linear in the file size, no regular expression.
 *
 * Refused as a whole: no entry, more than 2 % invalid lines (a binary or wrong file), more entries than the
 * source allows, or more than [MAX_OFFICIAL_DROPPED] entries that name an official (embedded) domain.
 */
internal object DomainListParser {
    fun parse(file: File, maxEntries: Long, officialDomains: Set<String>, psl: PublicSuffixList?): ParseOutcome =
        Run(maxEntries, officialDomains, psl).execute(file)

    private class Run(
        private val maxEntries: Long,
        private val officialDomains: Set<String>,
        private val psl: PublicSuffixList?,
    ) {
        private var keys = LongArray(INITIAL_KEYS)
        private var count = 0
        private var lines = 0L
        private var invalid = 0L
        private var dropped = 0
        private var firstLine = true
        private val line = ByteArray(MAX_LINE_BYTES)
        private var length = 0
        private var overflow = false
        private var failure: ListFailure? = null

        fun execute(file: File): ParseOutcome {
            BufferedInputStream(FileInputStream(file), READ_CHUNK).use { input ->
                val chunk = ByteArray(READ_CHUNK)
                while (failure == null) {
                    val read = input.read(chunk)
                    if (read < 0) break
                    for (i in 0 until read) {
                        consume(chunk[i])
                        if (failure != null) break
                    }
                }
            }
            if (failure == null && (length > 0 || overflow)) endLine()
            return outcome()
        }

        private fun consume(byte: Byte) {
            if (byte == '\n'.code.toByte()) {
                endLine()
            } else if (length < MAX_LINE_BYTES) {
                line[length++] = byte
            } else {
                overflow = true
            }
        }

        private fun outcome(): ParseOutcome {
            val reason = failure ?: when {
                lines == 0L -> ListFailure.TOO_FEW
                invalid * INVALID_RATIO_DIVISOR > lines -> ListFailure.BAD_FORMAT
                dropped > MAX_OFFICIAL_DROPPED -> ListFailure.POISONED
                count == 0 -> ListFailure.TOO_FEW
                else -> null
            }
            return if (reason != null) ParseOutcome.Rejected(reason) else ParseOutcome.Parsed(sortedUnique(), dropped)
        }

        private fun endLine() {
            val wasOverflow = overflow
            val size = length
            length = 0
            overflow = false
            if (wasOverflow) {
                lines++
                invalid++
                firstLine = false
                return
            }
            var start = 0
            var end = size
            while (start < end && isBlank(line[start])) start++
            while (end > start && isBlank(line[end - 1])) end--
            var text = String(line, start, end - start, Charsets.UTF_8)
            if (firstLine) text = text.removePrefix(BYTE_ORDER_MARK.toString())
            firstLine = false
            if (text.isEmpty() || text[0] == '#') return
            lines++
            if (hasControl(line, start, end)) {
                invalid++
                return
            }
            addEntry(text)
        }

        private fun isBlank(byte: Byte): Boolean = byte == ' '.code.toByte() ||
            byte == '\t'.code.toByte() ||
            byte == '\r'.code.toByte()

        private fun hasControl(bytes: ByteArray, from: Int, to: Int): Boolean {
            for (i in from until to) {
                val value = bytes[i].toInt() and BYTE_MASK
                if (value < SPACE && value != '\t'.code || value == ASCII_DEL) return true
            }
            return false
        }

        private fun addEntry(text: String) {
            val host = (HostNormalizer.normalize(text) as? HostResult.Normalized)?.host
            if (host == null || host.kind == HostKind.IPV6) {
                invalid++
                return
            }
            val ascii = host.ascii
            val key: Long
            if (host.kind == HostKind.DOMAIN) {
                val registrable = psl?.registrableDomain(ascii)
                if (psl != null && registrable == null) {
                    // A bare public suffix ("com", "co.uk") would flag a whole registry: refused, not stored.
                    invalid++
                    return
                }
                if (registrable != null && registrable in officialDomains && ascii == registrable) {
                    dropped++
                    return
                }
                key = Lfix.keyOf(if (ascii == registrable) Lfix.KIND_DOMAIN else Lfix.KIND_HOST, ascii)
            } else {
                key = Lfix.keyOf(Lfix.KIND_HOST, ascii)
            }
            append(key)
        }

        private fun append(key: Long) {
            if (count + 1L > maxEntries) {
                failure = ListFailure.TOO_MANY
                return
            }
            if (count == keys.size) keys = keys.copyOf(minOf(keys.size.toLong() * 2, maxEntries + 1).toInt())
            keys[count++] = key
        }

        /** Unsigned order: flip the sign bit, sort as signed, flip back. */
        private fun sortedUnique(): LongArray {
            val flipped = LongArray(count) { keys[it] xor Long.MIN_VALUE }
            flipped.sort()
            var unique = 0
            for (i in flipped.indices) {
                if (i == 0 || flipped[i] != flipped[i - 1]) flipped[unique++] = flipped[i]
            }
            return LongArray(unique) { flipped[it] xor Long.MIN_VALUE }
        }
    }
}
