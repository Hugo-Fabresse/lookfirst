package com.lookfirst.domain.open

import com.lookfirst.domain.host.HostResult
import com.lookfirst.domain.model.HostKind
import com.lookfirst.domain.model.ScanPayload
import com.lookfirst.domain.model.UrlIssue

private const val HTTP_SCHEME = "http"
private const val HTTPS_SCHEME = "https"
private const val HEX_DIGITS = "0123456789ABCDEF"
private const val ASCII_MAX = 0x7F
private const val NIBBLE_BITS = 4
private const val NIBBLE_MASK = 0x0F
private const val BYTE_MASK = 0xFF
private const val ESCAPE_LENGTH = 3
private const val REPLACEMENT_CHARACTER = 0xFFFD
private const val PLAIN_PUNCTUATION = "-._~!$&'()*+,;=:@/"

private val BLOCKING_ISSUES = setOf(
    UrlIssue.EMPTY_HOST,
    UrlIssue.HOST_TOO_LONG,
    UrlIssue.INVALID_HOST_CHARS,
    UrlIssue.INVALID_PORT,
)

/**
 * The URL a browser is given: built from the analysed fields only, never from the raw text. [host] is the
 * normalized ASCII host (punycode, `[...]` around an IPv6 literal) and [port] the analysed port, so that the platform
 * layer can check that what it parses back is exactly what was analysed (threat model T-CNT-7).
 */
data class CanonicalUrl(val value: String, val scheme: String, val host: String, val port: Int?) {
    /** The address never reaches logs. */
    override fun toString(): String = "CanonicalUrl(len=${value.length})"

    companion object {
        /**
         * Null when the URL cannot be rebuilt without guessing: an unusable host or port, characters a host cannot
         * hold, or a scheme other than http(s). A URL typed without scheme is opened as https. The userinfo (the
         * text before `@`) is dropped: it names nobody and only helps to disguise the host.
         */
        fun of(url: ScanPayload.Url): CanonicalUrl? {
            val scheme = url.scheme.ifEmpty { HTTPS_SCHEME }
            val host = asciiHost(url)
            if ((scheme != HTTP_SCHEME && scheme != HTTPS_SCHEME) || host == null) return null
            if (url.issues.any { it in BLOCKING_ISSUES }) return null
            val text = StringBuilder(scheme).append("://").append(host)
            url.port?.let { text.append(':').append(it) }
            text.append(encodePath(url.path.value))
            url.query?.let { text.append('?').append(encode(it.value, allowQuestion = true)) }
            url.fragment?.let { text.append('#').append(encode(it.value, allowQuestion = true)) }
            return CanonicalUrl(text.toString(), scheme, host, url.port)
        }

        private fun asciiHost(url: ScanPayload.Url): String? = if (url.hostKind == HostKind.IPV6) {
            "[${url.rawHost.value.lowercase()}]"
        } else {
            (url.normalizedHostResult as? HostResult.Normalized)?.host?.ascii
        }

        /** A browser reads `\` as `/` in a web path: say it once, in the canonical form. */
        private fun encodePath(path: String): String {
            val slashed = path.replace('\\', '/')
            return encode(if (slashed.startsWith("/")) slashed else "/$slashed", allowQuestion = false)
        }

        /** Percent-encodes all but unreserved characters and sub-delimiters; a valid `%XX` stays, a lone `%` becomes `%25`. */
        private fun encode(text: String, allowQuestion: Boolean): String {
            val out = StringBuilder(text.length + text.length / ESCAPE_LENGTH)
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '%' && isEscape(text, i)) {
                    out.append(text, i, i + ESCAPE_LENGTH)
                    i += ESCAPE_LENGTH
                } else if (isPlain(c) || (allowQuestion && c == '?')) {
                    out.append(c)
                    i++
                } else {
                    val codePoint = text.codePointAt(i)
                    appendEscaped(out, codePoint)
                    i += Character.charCount(codePoint)
                }
            }
            return out.toString()
        }

        private fun isPlain(c: Char): Boolean = c.code < ASCII_MAX &&
            (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in PLAIN_PUNCTUATION)

        private fun isEscape(text: String, at: Int): Boolean =
            at + 2 < text.length && text[at + 1].isHexDigit() && text[at + 2].isHexDigit()

        private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

        private fun appendEscaped(out: StringBuilder, codePoint: Int) {
            // A lone surrogate cannot be written as UTF-8: it becomes U+FFFD, never a raw character.
            val lone = codePoint in Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code
            val bytes = String(Character.toChars(if (lone) REPLACEMENT_CHARACTER else codePoint)).toByteArray()
            for (b in bytes) {
                val v = b.toInt() and BYTE_MASK
                out.append('%').append(HEX_DIGITS[v shr NIBBLE_BITS]).append(HEX_DIGITS[v and NIBBLE_MASK])
            }
        }
    }
}
