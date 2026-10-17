package com.lookfirst.data.network

import java.io.IOException
import java.io.InputStream
import java.net.ProtocolException

/** Largest response head read (status line and headers). The body is never read at all. */
internal const val MAX_HEAD_BYTES = 16 * 1024
private const val READ_CHUNK = 1024
private const val CR = '\r'.code
private const val LF = '\n'.code
private const val STATUS_MIN = 200
private const val STATUS_MAX = 599
private const val STATUS_DIGITS = 3
private const val LAST_PRINTABLE_ASCII = 0x7E
private const val VERSION_PREFIX = "HTTP/1."
private const val GENERIC_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Mobile Safari/537.36"

/** Status and `Location` values of a response; nothing else of the head is kept. */
internal class ResponseHead(val status: Int, val locations: List<String>) {
    val isRedirect: Boolean get() = status in REDIRECT_STATUSES

    /** The server does not accept HEAD: ask again with a one-byte `Range` GET. */
    val rejectsHead: Boolean get() = status == STATUS_METHOD_NOT_ALLOWED || status == STATUS_NOT_IMPLEMENTED

    /** Never printed: a `Location` is attacker text. */
    override fun toString(): String = "ResponseHead(status=$status, locations=${locations.size})"

    private companion object {
        val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
        const val STATUS_METHOD_NOT_ALLOWED = 405
        const val STATUS_NOT_IMPLEMENTED = 501
    }
}

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

    /** Printable ASCII only, no space: nothing that could end a line or a token of the request. */
    private fun isPlainAscii(text: String): Boolean = text.none { it <= ' ' || it.code > LAST_PRINTABLE_ASCII }

    /**
     * Reads up to the blank line that ends the head, never more than [MAX_HEAD_BYTES] bytes in total. [beforeRead] runs
     * before each blocking read (it applies the time left). A head that does not end, is too large, or is not
     * HTTP/1.x is a [ProtocolException].
     */
    @Throws(IOException::class)
    fun read(input: InputStream, beforeRead: () -> Unit): ResponseHead {
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
        return parse(String(buffer, 0, end, Charsets.UTF_8))
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
    private fun parse(head: String): ResponseHead {
        val lines = head.split("\r\n")
        val status = parseStatusLine(lines.first())
        val locations = mutableListOf<String>()
        // A folded line (obs-fold) continues the previous header; the tracer never accepts a folded Location.
        val headerLines = lines.drop(1).takeWhile { it.isNotEmpty() }.filterNot { it[0] == ' ' || it[0] == '\t' }
        for (line in headerLines) {
            val colon = line.indexOf(':')
            if (colon <= 0) throw ProtocolException("bad header line")
            if (line.substring(0, colon).equals("location", ignoreCase = true)) {
                locations += line.substring(colon + 1).trim(' ', '\t')
            }
        }
        return ResponseHead(status, locations)
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
