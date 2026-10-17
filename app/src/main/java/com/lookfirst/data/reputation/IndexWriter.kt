package com.lookfirst.data.reputation

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val HEADER_BYTES = 64
private const val FORMAT = 1
private const val FORMAT_OFFSET = 4
private const val SOURCE_OFFSET = 6
private const val COUNT_OFFSET = 8
private const val BUFFER_ENTRIES = 8192

/**
 * Writes the LFIX index the phone builds from a downloaded list: the header of `Lfix` (magic, version 1, source id,
 * entry count, reserved bytes and the Public Suffix List hash field left at zero) and the sorted 8-byte keys.
 * [keys] must be sorted (unsigned) and unique, which `DomainListParser` guarantees; the written file is checked
 * with `Lfix.validate` before it is installed.
 */
internal object IndexWriter {
    fun write(target: File, sourceId: Int, keys: LongArray) {
        val header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN)
        header.put(byteArrayOf('L'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'X'.code.toByte()))
        header.putShort(FORMAT_OFFSET, FORMAT.toShort())
        header.putShort(SOURCE_OFFSET, sourceId.toShort())
        header.putInt(COUNT_OFFSET, keys.size)
        FileOutputStream(target).use { file ->
            BufferedOutputStream(file).use { out ->
                out.write(header.array())
                val chunk = ByteBuffer.allocate(BUFFER_ENTRIES * Long.SIZE_BYTES).order(ByteOrder.BIG_ENDIAN)
                for (key in keys) {
                    chunk.putLong(key)
                    if (!chunk.hasRemaining()) {
                        out.write(chunk.array(), 0, chunk.position())
                        chunk.clear()
                    }
                }
                out.write(chunk.array(), 0, chunk.position())
                out.flush()
                file.fd.sync()
            }
        }
    }
}
