package com.lookfirst.data.reputation

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

private const val HEADER_SIZE = 64
private const val ENTRY_SIZE = 8
private const val INDEX_FORMAT = 1
private const val RESERVED_START = 12
private const val RESERVED_END = 32
private const val SHORT_MASK = 0xFFFF
private const val INT_MASK = 0xFFFFFFFFL
private const val READ_CHUNK = 64 * 1024
private val MAGIC = byteArrayOf('L'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'X'.code.toByte())

/** Index source ids. Each list the app knows has its own, never reused: ids 0 and 1 belonged to the removed bundle. */
internal object SourceIds {
    const val CERT_PL = 2
}

/** Entries only in the installed index (`removed`) and only in a candidate (`added`), see [MappedIndex]. */
internal class SetDifference(val removed: Long, val added: Long)

/** Bytes 32 to 63 of the file are reserved (they once held a Public Suffix List hash): written as zero, not read. */
internal class LfixHeader(val sourceId: Int, val entryCount: Long)

/** Hashing of index keys and one-pass validation of the LFIX layout (`docs/design/reputation-lists.md`, section 6). */
internal object Lfix {
    const val KIND_HOST = 'h'
    const val KIND_DOMAIN = 'd'

    /** First 8 bytes (big-endian) of SHA-256(kind byte || ASCII name), as a signed long; compare unsigned. */
    fun keyOf(kind: Char, asciiName: String): Long {
        val input = ByteArray(1 + asciiName.length)
        input[0] = kind.code.toByte()
        for (i in asciiName.indices) input[i + 1] = asciiName[i].code.toByte()
        return ByteBuffer.wrap(sha256(input)).order(ByteOrder.BIG_ENDIAN).getLong(0)
    }

    /**
     * Validates [file] in one linear pass: magic, version, reserved bytes zero, `size == 64 + 8n`, entries strictly
     * increasing (unsigned). Returns the header, or null for any deviation.
     */
    fun validate(file: File): LfixHeader? {
        val header = readHeader(file) ?: return null
        return if (isStrictlyIncreasing(file)) header else null
    }

    /** Header and size agreement only (load time); [validate] is the install-time full pass. */
    fun readHeader(file: File): LfixHeader? = try {
        val size = file.length()
        val head = if (size >= HEADER_SIZE && (size - HEADER_SIZE) % ENTRY_SIZE == 0L) readHead(file) else null
        parseHeader(head)?.takeIf { it.entryCount == (size - HEADER_SIZE) / ENTRY_SIZE }
    } catch (@Suppress("SwallowedException") ignored: IOException) {
        null
    }

    private fun readHead(file: File): ByteArray? {
        val head = ByteArray(HEADER_SIZE)
        file.inputStream().use { input ->
            var read = 0
            while (read < HEADER_SIZE) {
                val n = input.read(head, read, HEADER_SIZE - read)
                if (n < 0) return null
                read += n
            }
        }
        return head
    }

    private fun parseHeader(head: ByteArray?): LfixHeader? {
        if (head == null) return null
        val buffer = ByteBuffer.wrap(head).order(ByteOrder.BIG_ENDIAN)
        val magicOk = head.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)
        val reservedZero = (RESERVED_START until RESERVED_END).all { head[it].toInt() == 0 }
        if (!magicOk || buffer.getShort(MAGIC.size).toInt() != INDEX_FORMAT || !reservedZero) return null
        val sourceId = buffer.getShort(MAGIC.size + Short.SIZE_BYTES).toInt() and SHORT_MASK
        val count = buffer.getInt(MAGIC.size + 2 * Short.SIZE_BYTES).toLong() and INT_MASK
        return LfixHeader(sourceId, count)
    }

    private fun isStrictlyIncreasing(file: File): Boolean {
        val cursor = Cursor(file)
        try {
            var previous = cursor.next() ?: return true
            while (true) {
                val value = cursor.next() ?: return true
                if (java.lang.Long.compareUnsigned(value, previous) <= 0) return false
                previous = value
            }
        } finally {
            cursor.close()
        }
    }

    private class Cursor(file: File) {
        private val channel: FileChannel = file.inputStream().channel.also { it.position(HEADER_SIZE.toLong()) }
        private val buffer: ByteBuffer = ByteBuffer.allocate(READ_CHUNK).order(ByteOrder.BIG_ENDIAN).also {
            it.limit(0)
        }

        fun next(): Long? {
            if (buffer.remaining() < ENTRY_SIZE) {
                buffer.compact()
                while (buffer.position() < ENTRY_SIZE && channel.read(buffer) >= 0) {
                    // keep reading until a whole entry is buffered or the file ends
                }
                buffer.flip()
                if (buffer.remaining() < ENTRY_SIZE) return null
            }
            return buffer.getLong()
        }

        fun close() = channel.close()
    }
}

/**
 * A validated index mapped read-only and searched in place: no heap copy, no parsing. Bounds are checked on
 * construction against the file size; a file that does not fit its own header is refused.
 */
internal class MappedIndex private constructor(
    private val buffer: ByteBuffer,
    val entryCount: Long,
    val header: LfixHeader,
) {
    /** Binary search for [key] (compared as unsigned 64-bit). */
    fun contains(key: Long): Boolean {
        var low = 0L
        var high = entryCount - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val value = buffer.getLong(HEADER_SIZE + (mid * ENTRY_SIZE).toInt())
            val cmp = java.lang.Long.compareUnsigned(value, key)
            when {
                cmp == 0 -> return true
                cmp < 0 -> low = mid + 1
                else -> high = mid - 1
            }
        }
        return false
    }

    /**
     * Entries of this index missing from [newKeys] (`removed`) and entries of [newKeys] missing from it (`added`),
     * by one linear merge of two unsigned-ordered sequences. Null when this index is not strictly increasing
     * (damaged). [newKeys] must be strictly increasing too (the list parser's output).
     */
    fun differenceTo(newKeys: LongArray): SetDifference? {
        var i = 0L
        var j = 0
        var removed = 0L
        var added = 0L
        var previous = 0L
        var checked = -1L
        while (i < entryCount) {
            val old = keyAt(i)
            if (i > checked) {
                if (checked >= 0 && java.lang.Long.compareUnsigned(old, previous) <= 0) return null
                previous = old
                checked = i
            }
            val cmp = if (j < newKeys.size) java.lang.Long.compareUnsigned(old, newKeys[j]) else -1
            when {
                cmp < 0 -> {
                    removed++
                    i++
                }
                cmp > 0 -> {
                    added++
                    j++
                }
                else -> {
                    i++
                    j++
                }
            }
        }
        return SetDifference(removed, added + (newKeys.size - j))
    }

    private fun keyAt(position: Long): Long = buffer.getLong(HEADER_SIZE + (position * ENTRY_SIZE).toInt())

    companion object {
        /** Maps [file]; null if its size or header do not agree. (The full ordering check is done at install.) */
        fun open(file: File): MappedIndex? = try {
            val size = file.length()
            val header = Lfix.readHeader(file)
            if (header == null || size > Int.MAX_VALUE) {
                null
            } else {
                RandomAccessFile(file, "r").use { raf ->
                    val mapped = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, size).order(ByteOrder.BIG_ENDIAN)
                    MappedIndex(mapped, header.entryCount, header)
                }
            }
        } catch (@Suppress("SwallowedException") ignored: IOException) {
            null
        }
    }
}
