package com.lookfirst.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.ProtocolException

class HttpHeadTest {
    private fun read(text: String, beforeRead: () -> Unit = {}): ResponseHead =
        HttpHead.read(ByteArrayInputStream(text.toByteArray(Charsets.ISO_8859_1)), beforeRead)

    @Test
    fun `a HEAD request carries no cookie, credential, referrer or body`() {
        val text = String(HttpHead.request("/a?b=1", "example.com", rangeProbe = false), Charsets.US_ASCII)
        assertTrue(text.startsWith("HEAD /a?b=1 HTTP/1.1\r\nHost: example.com\r\n"))
        assertTrue(text.endsWith("Connection: close\r\n\r\n"))
        val names = text.split("\r\n").map { it.substringBefore(':', "").lowercase() }.filter { it.isNotEmpty() }
        assertFalse(
            names.any {
                it in setOf("cookie", "authorization", "referer", "origin", "content-length", "range")
            },
        )
        assertEquals(1, text.split("\r\n").count { it.startsWith("User-Agent:") })
    }

    @Test
    fun `the range probe is a GET for one byte`() {
        val text = String(HttpHead.request("/", "example.com:8443", rangeProbe = true), Charsets.US_ASCII)
        assertTrue(text.startsWith("GET / HTTP/1.1\r\nHost: example.com:8443\r\n"))
        assertTrue(text.contains("Range: bytes=0-0\r\n"))
    }

    @Test
    fun `the user agent is the same for every request`() {
        fun ua(bytes: ByteArray) = String(bytes).lineSequence().first { it.startsWith("User-Agent:") }
        assertEquals(ua(HttpHead.request("/a", "a.example", false)), ua(HttpHead.request("/b", "b.example", true)))
    }

    @Test
    fun `a request line cannot be split by a hostile target or host`() {
        assertThrows(IllegalArgumentException::class.java) { HttpHead.request("/a\r\nCookie: x", "e.example", false) }
        assertThrows(IllegalArgumentException::class.java) { HttpHead.request("/a b", "e.example", false) }
        assertThrows(IllegalArgumentException::class.java) { HttpHead.request("/", "e.example\r\nX: y", false) }
        assertThrows(IllegalArgumentException::class.java) { HttpHead.request("/é", "e.example", false) }
    }

    @Test
    fun `status and location are read and the body is ignored`() {
        val head = read("HTTP/1.1 302 Found\r\nLocation: https://b.example/x\r\nContent-Length: 5\r\n\r\nhello")
        assertEquals(302, head.status)
        assertTrue(head.isRedirect)
        assertEquals(listOf("https://b.example/x"), head.locations)
    }

    @Test
    fun `header names are case-insensitive and values are trimmed`() {
        assertEquals(listOf("/next"), read("HTTP/1.0 301 Moved\r\nLOCATION:   /next \t\r\n\r\n").locations)
    }

    @Test
    fun `405 and 501 mean the server refuses HEAD`() {
        assertTrue(read("HTTP/1.1 405 Method Not Allowed\r\n\r\n").rejectsHead)
        assertTrue(read("HTTP/1.1 501 Not Implemented\r\n\r\n").rejectsHead)
        assertFalse(read("HTTP/1.1 200 OK\r\n\r\n").rejectsHead)
    }

    @Test
    fun `only the redirect statuses are redirects`() {
        for (status in listOf(301, 302, 303, 307, 308)) assertTrue(read("HTTP/1.1 $status X\r\n\r\n").isRedirect)
        for (status in listOf(200, 204, 300, 304, 404, 500)) assertFalse(read("HTTP/1.1 $status X\r\n\r\n").isRedirect)
    }

    @Test
    fun `a head that is not HTTP 1 is refused`() {
        listOf(
            "SSH-2.0-OpenSSH\r\n\r\n",
            "HTTP/2 200\r\n\r\n",
            "HTTP/1.1 20 OK\r\n\r\n",
            "HTTP/1.1 abc OK\r\n\r\n",
            "HTTP/1.1 100 Continue\r\n\r\n",
            "HTTP/1.1 999 X\r\n\r\n",
            "HTTP/1.1 2000 X\r\n\r\n",
            "\r\n\r\n",
        ).forEach { assertThrows(it, ProtocolException::class.java) { read(it) } }
    }

    @Test
    fun `a malformed header line is refused`() {
        assertThrows(ProtocolException::class.java) { read("HTTP/1.1 200 OK\r\nno colon here\r\n\r\n") }
        assertThrows(ProtocolException::class.java) { read("HTTP/1.1 200 OK\r\n: empty name\r\n\r\n") }
    }

    @Test
    fun `a folded continuation line is refused`() {
        assertThrows(ProtocolException::class.java) {
            read("HTTP/1.1 302 Found\r\nLocation: https://a.example/\r\n  .evil.example\r\n\r\n")
        }
        assertThrows(ProtocolException::class.java) { read("HTTP/1.1 302 Found\r\nX: a\r\n\tb\r\n\r\n") }
    }

    @Test
    fun `a head that never ends is cut at 16 KiB`() {
        val endless = object : InputStream() {
            var served = 0L

            override fun read(): Int = 'a'.code.also { served++ }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                for (i in 0 until len) b[off + i] = 'a'.code.toByte()
                served += len
                return len
            }
        }
        assertThrows(ProtocolException::class.java) { HttpHead.read(endless) {} }
        assertEquals(MAX_HEAD_BYTES.toLong(), endless.served)
    }

    @Test
    fun `a head with 20000 header lines is refused without reading past the cap`() {
        val big = "HTTP/1.1 200 OK\r\n" + "X-A: b\r\n".repeat(20_000) + "\r\n"
        val input = ByteArrayInputStream(big.toByteArray())
        assertThrows(ProtocolException::class.java) { HttpHead.read(input) {} }
        assertTrue(input.available() > 0)
    }

    @Test
    fun `a closed connection before the end of the head is refused`() {
        assertThrows(ProtocolException::class.java) { read("HTTP/1.1 200 OK\r\nLocation: x") }
        assertThrows(ProtocolException::class.java) { read("") }
    }

    @Test
    fun `a bare LF line ending does not end the head`() {
        assertThrows(ProtocolException::class.java) { read("HTTP/1.1 200 OK\n\n") }
    }

    @Test
    fun `an end marker split across reads is found`() {
        val parts = listOf("HTTP/1.1 301 Moved\r\nLocation: /a\r", "\n\r", "\n")
        val stream = object : InputStream() {
            var next = 0

            override fun read(): Int = throw UnsupportedOperationException()

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (next >= parts.size) return -1
                val bytes = parts[next++].toByteArray()
                System.arraycopy(bytes, 0, b, off, bytes.size)
                return bytes.size
            }
        }
        assertEquals(listOf("/a"), HttpHead.read(stream) {}.locations)
    }

    @Test
    fun `the time limit is applied before every read and stops a slow server`() {
        var calls = 0
        val drip = object : InputStream() {
            override fun read(): Int = 'a'.code

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                b[off] = 'a'.code.toByte()
                return 1
            }
        }
        assertThrows(IOException::class.java) {
            HttpHead.read(drip) { if (++calls > 50) throw IOException("budget spent") }
        }
        assertEquals(51, calls)
    }

    @Test
    fun `a non-ASCII location is decoded as UTF-8`() {
        val raw = "HTTP/1.1 302 Found\r\nLocation: /é\r\n\r\n".toByteArray(Charsets.UTF_8)
        assertEquals(listOf("/é"), HttpHead.read(ByteArrayInputStream(raw)) {}.locations)
    }

    private fun body(text: String, max: Int = 1024, beforeRead: () -> Unit = {}): ResponseHead =
        HttpHead.readResponse(ByteArrayInputStream(text.toByteArray(Charsets.ISO_8859_1)), max, beforeRead)

    private fun ResponseHead.text() = String(this.body, Charsets.ISO_8859_1)

    @Test
    fun `a get request asks for json, without cookie, credential or referrer`() {
        val text =
            String(HttpHead.get("/domain/example.com", "rdap.example", "application/rdap+json"), Charsets.US_ASCII)
        assertTrue(text.startsWith("GET /domain/example.com HTTP/1.1\r\nHost: rdap.example\r\n"))
        assertTrue(text.contains("Accept: application/rdap+json\r\n"))
        assertTrue(text.contains("Accept-Encoding: identity\r\n"))
        assertTrue(text.endsWith("Connection: close\r\n\r\n"))
        val names = text.split("\r\n").map { it.substringBefore(':', "").lowercase() }.filter { it.isNotEmpty() }
        assertFalse(names.any { it in setOf("cookie", "authorization", "referer", "origin", "range") })
        assertThrows(IllegalArgumentException::class.java) { HttpHead.get("/a\r\nX: y", "h.example", "a/b") }
        assertThrows(IllegalArgumentException::class.java) { HttpHead.get("/", "h.example", "a/b\r\nX: y") }
    }

    @Test
    fun `the user agent of a get is the one of the other requests`() {
        fun ua(bytes: ByteArray) = String(bytes).lineSequence().first { it.startsWith("User-Agent:") }
        assertEquals(ua(HttpHead.request("/a", "a.example", false)), ua(HttpHead.get("/b", "b.example", "a/b")))
    }

    @Test
    fun `a body framed by Content-Length is read`() {
        val head = body("HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello")
        assertEquals("hello", head.text())
    }

    @Test
    fun `a body is read to the end of the connection without framing`() {
        assertEquals("hello world", body("HTTP/1.1 200 OK\r\n\r\nhello world").text())
    }

    @Test
    fun `a chunked body is decoded, extensions and trailers ignored`() {
        val raw = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n" +
            "5;ext=1\r\nhello\r\n6\r\n world\r\nA\r\n0123456789\r\n0\r\nX-Trailer: y\r\n\r\n"
        assertEquals("hello world0123456789", body(raw).text())
    }

    @Test
    fun `a body is only read for a 200 answer`() {
        assertEquals(0, body("HTTP/1.1 404 Not Found\r\nContent-Length: 5\r\n\r\nhello").body.size)
        assertEquals(0, body("HTTP/1.1 301 Moved\r\nLocation: /x\r\n\r\nhello").body.size)
        assertEquals(0, body("HTTP/1.1 200 OK\r\n\r\nhello", max = 0).body.size)
    }

    @Test
    fun `a body above the cap is refused whatever the framing`() {
        listOf(
            "HTTP/1.1 200 OK\r\nContent-Length: 11\r\n\r\nhello world",
            "HTTP/1.1 200 OK\r\n\r\nhello world",
            "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\nb\r\nhello world\r\n0\r\n\r\n",
            "HTTP/1.1 200 OK\r\nContent-Length: 99999999\r\n\r\nx",
        ).forEach { assertThrows(it, ProtocolException::class.java) { body(it, max = 10) } }
    }

    @Test
    fun `an endless body is cut at the cap`() {
        val endless = object : InputStream() {
            var served = 0L
            var head = "HTTP/1.1 200 OK\r\n\r\n".toByteArray()

            override fun read(): Int = 'a'.code

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (head.isNotEmpty()) {
                    val n = minOf(len, head.size)
                    System.arraycopy(head, 0, b, off, n)
                    head = head.copyOfRange(n, head.size)
                    return n
                }
                for (i in 0 until len) b[off + i] = 'a'.code.toByte()
                served += len
                return len
            }
        }
        assertThrows(ProtocolException::class.java) { HttpHead.readResponse(endless, 1000) {} }
        assertTrue(endless.served < 1000 + 4096 + 16)
    }

    @Test
    fun `a truncated body is refused`() {
        assertThrows(ProtocolException::class.java) { body("HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\nhello") }
        assertThrows(ProtocolException::class.java) {
            body("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhel")
        }
    }

    @Test
    fun `ambiguous or unsupported framing is refused`() {
        listOf(
            "HTTP/1.1 200 OK\r\nContent-Length: 5\r\nContent-Length: 6\r\n\r\nhello!",
            "HTTP/1.1 200 OK\r\nContent-Length: +5\r\n\r\nhello",
            "HTTP/1.1 200 OK\r\nContent-Length: -1\r\n\r\nhello",
            "HTTP/1.1 200 OK\r\nContent-Length: 5\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n",
            "HTTP/1.1 200 OK\r\nTransfer-Encoding: gzip, chunked\r\n\r\n0\r\n\r\n",
            "HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nContent-Length: 2\r\n\r\nab",
        ).forEach { assertThrows(it, ProtocolException::class.java) { body(it) } }
    }

    @Test
    fun `identical Content-Length repeats and identity coding are accepted`() {
        val raw = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nContent-Length: 2\r\nContent-Encoding: identity\r\n\r\nab"
        assertEquals("ab", body(raw).text())
    }

    @Test
    fun `a malformed chunk is refused`() {
        listOf(
            "zz\r\nab\r\n0\r\n\r\n",
            "\r\nab\r\n0\r\n\r\n",
            "123456789\r\n",
            "2\r\nabXX0\r\n\r\n",
            "2;" + "x".repeat(300) + "\r\nab\r\n0\r\n\r\n",
            "2\nab\r\n0\r\n\r\n",
        ).forEach { chunks ->
            val raw = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n$chunks"
            assertThrows(chunks, ProtocolException::class.java) { body(raw) }
        }
    }

    @Test
    fun `the body read stops at the time budget`() {
        var calls = 0
        assertThrows(IOException::class.java) {
            body("HTTP/1.1 200 OK\r\n\r\nhello") { if (++calls > 1) throw IOException("budget spent") }
        }
    }

    @Test
    fun `a body that arrived with the head is not lost`() {
        // The whole answer comes in one read: the head reader has already taken the start of the body.
        assertEquals("hello", body("HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello").text())
    }
}
