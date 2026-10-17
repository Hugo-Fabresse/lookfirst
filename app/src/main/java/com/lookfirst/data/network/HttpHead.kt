package com.lookfirst.data.network

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ProtocolException
import java.util.Locale

/** Largest response head read (status line and headers). */
internal const val MAX_HEAD_BYTES = 16 * 1024
private const val READ_CHUNK = 1024
private const val CR = '\r'.code
private const val LF = '\n'.code
private const val STATUS_MIN = 200
private const val STATUS_MAX = 599
private const val STATUS_DIGITS = 3
private const val LAST_PRINTABLE_ASCII = 0x7E
private const val VERSION_PREFIX = "HTTP/1."
private const val STATUS_OK = 200
private const val BODY_CHUNK = 4096
private const val MAX_LENGTH_DIGITS = 9
private const val MAX_CHUNK_SIZE_DIGITS = 8
private const val MAX_CHUNK_EXTENSION = 128
private const val HEX_RADIX = 16
private const val HEX_LETTER_BASE = 10
private const val BYTE_MASK = 0xFF
private const val GENERIC_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Mobile Safari/537.36"

/**
 * Status and `Location` values of a response; nothing else of the head is kept. [body] is empty unless the caller
 * asked for one (RDAP) and the status was 200.
 */
internal class ResponseHead(val status: Int, val locations: List<String>, val body: ByteArray = NO_BODY) {
    val isRedirect: Boolean get() = status in REDIRECT_STATUSES

    /** The server does not accept HEAD: ask again with a one-byte `Range` GET. */
    val rejectsHead: Boolean get() = status == STATUS_METHOD_NOT_ALLOWED || status == STATUS_NOT_IMPLEMENTED

    /** Never printed: a `Location` is attacker text. */
    override fun toString(): String = "ResponseHead(status=$status, locations=${locations.size}, body=${body.size})"

    companion object {
        private val NO_BODY = ByteArray(0)

        private val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
        private const val STATUS_METHOD_NOT_ALLOWED = 405
        private const val STATUS_NOT_IMPLEMENTED = 501
    }
}

/** The headers that say how the body is framed; checked only when a body is read. */
private class Framing(
    val lengths: List<String>,
    val transferEncodings: List<String>,
    val contentEncodings: List<String>,
)

private class ParsedHead(val head: ResponseHead, val framing: Framing)

/** The head text and the bytes read past it (the start of the body). */
private class HeadBytes(val text: String, val leftover: ByteArray)

/**
 * The only HTTP the tracer speaks: a hand-written HTTP/1.1 request without cookies, credentials, referrer or body,
 * and a bounded, strict read of the response head. Linear time, no regular expressions.
 */
internal object HttpHead {
    /**
     * `HEAD`, or `GET` with `Range: bytes=0-0` ([rangeProbe]) when HEAD is refused. [target] is the canonical path and
     * query (percent-encoded, so it cannot hold a line break), [hostHeader] the canonical host with its port if it is not 443.
     */
    fun request(target: String, hostHeader: String, rangeProbe: Boolean): ByteArray {
        require(isPlainAscii(target) && isPlainAscii(hostHeader))
        val text = StringBuilder()
            .append(if (rangeProbe) "GET " else "HEAD ").append(target).append(" HTTP/1.1\r\n")
            .append("Host: ").append(hostHeader).append("\r\n")
            .append("User-Agent: ").append(GENERIC_USER_AGENT).append("\r\n")
            .append("Accept: */*\r\n")
            .append("Accept-Encoding: identity\r\n")
        if (rangeProbe) text.append("Range: bytes=0-0\r\n")
        text.append("Connection: close\r\n\r\n")
        return text.toString().toByteArray(Charsets.US_ASCII)
    }

    /**
     * A plain `GET` for a JSON document: the same fixed headers as the other requests, no cookie, credential, referrer
     * or body, `Accept` set to [accept]. Same limits on [target] and [hostHeader].
     */
    fun get(target: String, hostHeader: String, accept: String): ByteArray {
        require(isPlainAscii(target) && isPlainAscii(hostHeader) && isPlainAscii(accept))
        return StringBuilder()
            .append("GET ").append(target).append(" HTTP/1.1\r\n")
            .append("Host: ").append(hostHeader).append("\r\n")
            .append("User-Agent: ").append(GENERIC_USER_AGENT).append("\r\n")
            .append("Accept: ").append(accept).append("\r\n")
            .append("Accept-Encoding: identity\r\n")
            .append("Connection: close\r\n\r\n")
            .toString().toByteArray(Charsets.US_ASCII)
    }

    /** Printable ASCII only, no space: nothing that could end a line or a token of the request. */
    private fun isPlainAscii(text: String): Boolean = text.none { it <= ' ' || it.code > LAST_PRINTABLE_ASCII }

    /**
     * Reads up to the blank line that ends the head, never more than [MAX_HEAD_BYTES] bytes in total. [beforeRead] runs
     * before each blocking read (it applies the time left). A head that does not end, is too large, or is not
     * HTTP/1.x is a [ProtocolException].
     */
    @Throws(IOException::class)
    fun read(input: InputStream, beforeRead: () -> Unit): ResponseHead =
        parse(readHeadBytes(input, beforeRead).text).head

    /**
     * The head as [read], then, for a 200 answer and when [maxBody] is above 0, the body: framed by `Content-Length`,
     * by `Transfer-Encoding: chunked`, or by the end of the connection, never above [maxBody] bytes (more is a
     * [ProtocolException], so is any other framing or a content coding). Linear, bounded, no decompression.
     */
    @Throws(IOException::class)
    fun readResponse(input: InputStream, maxBody: Int, beforeRead: () -> Unit): ResponseHead {
        val raw = readHeadBytes(input, beforeRead)
        val parsed = parse(raw.text)
        if (maxBody <= 0 || parsed.head.status != STATUS_OK) return parsed.head
        val body = BodyReader(input, raw.leftover, maxBody, beforeRead).read(parsed.framing)
        return ResponseHead(parsed.head.status, parsed.head.locations, body)
    }

    @Throws(IOException::class)
    private fun readHeadBytes(input: InputStream, beforeRead: () -> Unit): HeadBytes {
        val buffer = ByteArray(MAX_HEAD_BYTES)
        var length = 0
        var end = -1
        val chunk = ByteArray(READ_CHUNK)
        while (end < 0) {
            beforeRead()
            val wanted = minOf(chunk.size, MAX_HEAD_BYTES - length)
            if (wanted <= 0) throw ProtocolException("head too large")
            val got = input.read(chunk, 0, wanted)
            if (got < 0) throw ProtocolException("head truncated")
            val from = maxOf(0, length - HEAD_END.size + 1)
            System.arraycopy(chunk, 0, buffer, length, got)
            length += got
            end = findHeadEnd(buffer, from, length)
        }
        return HeadBytes(String(buffer, 0, end, Charsets.UTF_8), buffer.copyOfRange(end, length))
    }

    /** Index just after the first CR LF CR LF in `buffer[from, length)`, or -1. A bare LF line ending is refused. */
    private fun findHeadEnd(buffer: ByteArray, from: Int, length: Int): Int {
        var i = from
        while (i + HEAD_END.size <= length) {
            if (HEAD_END.indices.all { buffer[i + it] == HEAD_END[it] }) return i + HEAD_END.size
            i++
        }
        return -1
    }

    @Throws(ProtocolException::class)
    private fun parse(head: String): ParsedHead {
        val lines = head.split("\r\n")
        val status = parseStatusLine(lines.first())
        val locations = mutableListOf<String>()
        val lengths = mutableListOf<String>()
        val transfers = mutableListOf<String>()
        val encodings = mutableListOf<String>()
        val headerLines = lines.drop(1).takeWhile { it.isNotEmpty() }
        for (line in headerLines) {
            // A folded line (obs-fold) is refused, never guessed at: a Location split in two is an attack.
            val colon = line.indexOf(':')
            if (colon <= 0 || line[0] == ' ' || line[0] == '\t') throw ProtocolException("bad header line")
            val value = line.substring(colon + 1).trim(' ', '\t')
            when (line.substring(0, colon).lowercase(Locale.ROOT)) {
                "location" -> locations += value
                "content-length" -> lengths += value
                "transfer-encoding" -> transfers += value.lowercase(Locale.ROOT)
                "content-encoding" -> encodings += value.lowercase(Locale.ROOT)
            }
        }
        return ParsedHead(ResponseHead(status, locations), Framing(lengths, transfers, encodings))
    }

    @Throws(ProtocolException::class)
    private fun parseStatusLine(line: String): Int {
        if (!line.startsWith(VERSION_PREFIX) || line.length < VERSION_PREFIX.length + 2 + STATUS_DIGITS) {
            throw ProtocolException("not HTTP/1.x")
        }
        val versionDigit = line[VERSION_PREFIX.length]
        val digits = line.substring(VERSION_PREFIX.length + 2, VERSION_PREFIX.length + 2 + STATUS_DIGITS)
        val after = line.getOrNull(VERSION_PREFIX.length + 2 + STATUS_DIGITS)
        val valid = versionDigit in '0'..'9' &&
            line[VERSION_PREFIX.length + 1] == ' ' &&
            digits.all { it in '0'..'9' } &&
            (after == null || after == ' ')
        val status = if (valid) digits.toInt() else -1
        if (status !in STATUS_MIN..STATUS_MAX) throw ProtocolException("bad status line")
        return status
    }

    private val HEAD_END = byteArrayOf(CR.toByte(), LF.toByte(), CR.toByte(), LF.toByte())
}

/**
 * Reads a response body without ever holding more than [max] bytes. [leftover] are the bytes the head reader had
 * already taken from the stream. Each refusal is a [ProtocolException].
 */
private class BodyReader(
    private val input: InputStream,
    private val leftover: ByteArray,
    private val max: Int,
    private val beforeRead: () -> Unit,
) {
    private var leftoverAt = 0
    private val block = ByteArray(BODY_CHUNK)
    private var blockLength = 0
    private var blockAt = 0

    @Throws(IOException::class)
    fun read(framing: Framing): ByteArray {
        if (framing.contentEncodings.any { it != "identity" && it.isNotEmpty() }) {
            throw ProtocolException("content coding")
        }
        val chunked = framing.transferEncodings.isNotEmpty()
        if (chunked && (framing.transferEncodings != listOf("chunked") || framing.lengths.isNotEmpty())) {
            throw ProtocolException("unsupported framing")
        }
        return when {
            chunked -> readChunked()
            framing.lengths.isNotEmpty() -> readLength(parseLength(framing.lengths))
            else -> readUntilClose()
        }
    }

    private fun parseLength(values: List<String>): Int {
        val value = values.first()
        val valid = value.length in 1..MAX_LENGTH_DIGITS && value.all { it in '0'..'9' } && values.all { it == value }
        if (!valid) throw ProtocolException("bad length")
        return value.toInt()
    }

    private fun readLength(length: Int): ByteArray {
        if (length > max) throw ProtocolException("body too large")
        val out = ByteArray(length)
        for (i in 0 until length) {
            val b = nextByte()
            if (b < 0) throw ProtocolException("truncated")
            out[i] = b.toByte()
        }
        return out
    }

    private fun readUntilClose(): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = nextByte()
            if (b < 0) return out.toByteArray()
            if (out.size() >= max) throw ProtocolException("body too large")
            out.write(b)
        }
    }

    private fun readChunked(): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val size = readChunkSize()
            if (size == 0L) return out.toByteArray()
            if (out.size() + size > max) refuse("body too large")
            for (i in 0 until size) {
                val b = nextByte()
                if (b < 0) refuse("truncated")
                out.write(b)
            }
            if (nextByte() != CR || nextByte() != LF) refuse("bad chunk end")
        }
    }

    /** Hex digits, an optional `;extension` of bounded length, then CR LF. */
    private fun readChunkSize(): Long {
        var size = 0L
        var digits = 0
        var b = nextByte()
        while (digitValue(b) >= 0) {
            size = size * HEX_RADIX + digitValue(b)
            digits++
            if (digits > MAX_CHUNK_SIZE_DIGITS) refuse("bad chunk size")
            b = nextByte()
        }
        if (digits == 0) refuse("bad chunk size")
        if (b == ';'.code) {
            var skipped = 0
            while (b != CR) {
                if (b < 0 || ++skipped > MAX_CHUNK_EXTENSION) refuse("bad chunk extension")
                b = nextByte()
            }
        }
        if (b != CR || nextByte() != LF) refuse("bad chunk size line")
        return size
    }

    private fun refuse(reason: String): Nothing = throw ProtocolException(reason)

    private fun digitValue(b: Int): Int = when (b) {
        in '0'.code..'9'.code -> b - '0'.code
        in 'a'.code..'f'.code -> b - 'a'.code + HEX_LETTER_BASE
        in 'A'.code..'F'.code -> b - 'A'.code + HEX_LETTER_BASE
        else -> -1
    }

    /** The next byte, -1 at the end of the connection. */
    private fun nextByte(): Int {
        if (leftoverAt < leftover.size) return leftover[leftoverAt++].toInt() and BYTE_MASK
        if (blockAt >= blockLength) {
            beforeRead()
            val got = input.read(block, 0, block.size)
            if (got <= 0) return -1
            blockLength = got
            blockAt = 0
        }
        return block[blockAt++].toInt() and BYTE_MASK
    }
}
