package com.lookfirst.domain.decoder

import com.lookfirst.domain.model.HostKind
import com.lookfirst.domain.model.ScanPayload
import com.lookfirst.domain.model.UrlIssue

private const val HEX_RADIX = 16
private const val MAX_HOST_LENGTH = 253
private const val MAX_PORT = 65_535
private const val MAX_PORT_DIGITS = 5
private const val IPV4_LABELS = 4
private const val MAX_IPV4_OCTET = 255
private const val MAX_OCTET_DIGITS = 3
private const val ASCII_MAX = 0x7F
private const val HEX_PREFIX_LENGTH = 2
private const val FORBIDDEN_HOST_CHARS = "\"<>\\^`{|}@[]:#/?"

/**
 * Strict http(s) URL splitter in the WHATWG spirit: `\` counts as `/`, the host is whatever follows the LAST `@`
 * of the authority. It reports oddities as [UrlIssue]s instead of fixing them. IDN stays as typed
 * (the UTS #46 conversion belongs to the normalizer, not here).
 */
internal object UrlParser {
    /** [text] is the whole sanitized content (kept as raw); [afterScheme] is what follows `scheme:`. */
    fun parse(text: String, scheme: String, afterScheme: String): ScanPayload.Url {
        val rest = afterScheme.trimEnd { it <= ' ' }
        val issues = LinkedHashSet<UrlIssue>()
        var i = 0
        while (i < rest.length && (rest[i] == '/' || rest[i] == '\\')) i++
        if (i < 2) issues += UrlIssue.MISSING_SLASHES
        if (i > 2) issues += UrlIssue.EXTRA_SLASHES

        val authorityEnd = rest.indexOfAny(charArrayOf('/', '\\', '?', '#'), i).orEnd(rest)
        val authority = rest.substring(i, authorityEnd)
        val tail = rest.substring(authorityEnd)
        val beforeQuery = rest.substring(0, rest.indexOfAny(charArrayOf('?', '#')).orEnd(rest))
        if (beforeQuery.indexOf('\\') >= 0) issues += UrlIssue.BACKSLASH
        if (text.trim { it <= ' ' }.any { it <= ' ' || it.code == ASCII_MAX }) issues += UrlIssue.CONTROL_OR_SPACE

        val at = authority.lastIndexOf('@')
        val userinfo = if (at >= 0) authority.substring(0, at) else null
        val hostPort = splitHostPort(authority.substring(at + 1), issues)
        val kind = classifyHost(hostPort.host, hostPort.bracketed, issues)

        val fragmentStart = tail.indexOf('#')
        val beforeFragment = if (fragmentStart < 0) tail else tail.substring(0, fragmentStart)
        val queryStart = beforeFragment.indexOf('?')
        return ScanPayload.Url(
            raw = text,
            scheme = scheme,
            userinfo = userinfo,
            host = hostPort.host,
            port = hostPort.port,
            hostKind = kind,
            path = if (queryStart < 0) beforeFragment else beforeFragment.substring(0, queryStart),
            query = if (queryStart < 0) null else beforeFragment.substring(queryStart + 1),
            fragment = if (fragmentStart < 0) null else tail.substring(fragmentStart + 1),
            nonAsciiHost = hostPort.host.any { it.code > ASCII_MAX },
            issues = issues,
        )
    }

    private class HostPort(val host: String, val port: Int?, val bracketed: Boolean)

    private fun splitHostPort(hostPort: String, issues: MutableSet<UrlIssue>): HostPort {
        if (hostPort.startsWith("[")) {
            val close = hostPort.indexOf(']')
            if (close < 0) {
                issues += UrlIssue.INVALID_HOST_CHARS
                return HostPort(hostPort.substring(1), null, true)
            }
            val after = hostPort.substring(close + 1)
            if (after.isNotEmpty() && !after.startsWith(":")) issues += UrlIssue.INVALID_HOST_CHARS
            return HostPort(hostPort.substring(1, close), parsePort(after.removePrefix(":"), issues), true)
        }
        val colon = hostPort.lastIndexOf(':')
        if (colon < 0) return HostPort(hostPort, null, false)
        return HostPort(hostPort.substring(0, colon), parsePort(hostPort.substring(colon + 1), issues), false)
    }

    private fun parsePort(digits: String, issues: MutableSet<UrlIssue>): Int? {
        if (digits.isEmpty()) return null
        val valid = digits.length <= MAX_PORT_DIGITS && digits.all { it in '0'..'9' }
        val port = if (valid) digits.toInt() else -1
        if (port !in 0..MAX_PORT) {
            issues += UrlIssue.INVALID_PORT
            return null
        }
        return port
    }

    private fun classifyHost(host: String, bracketed: Boolean, issues: MutableSet<UrlIssue>): HostKind {
        if (host.isEmpty()) issues += UrlIssue.EMPTY_HOST
        if (host.length > MAX_HOST_LENGTH) issues += UrlIssue.HOST_TOO_LONG
        if (host.indexOf('%') >= 0) issues += UrlIssue.HOST_PERCENT_ENCODED
        if (bracketed) return classifyIpv6(host, issues)
        if (host.any { it <= ' ' || it.code == ASCII_MAX || it in FORBIDDEN_HOST_CHARS }) {
            issues += UrlIssue.INVALID_HOST_CHARS
        }
        return classifyNumeric(host)
    }

    private fun classifyIpv6(host: String, issues: MutableSet<UrlIssue>): HostKind {
        val valid = host.contains(':') && host.all { it == ':' || it == '.' || isAsciiHex(it) }
        if (!valid) issues += UrlIssue.INVALID_HOST_CHARS
        return HostKind.IPV6
    }

    /** Dotted decimal IPv4 is IPV4; single numbers, hex, octal-looking forms and shortened forms are IPV4_OBFUSCATED. */
    private fun classifyNumeric(host: String): HostKind {
        val labels = host.removeSuffix(".").split('.')
        if (labels.size > IPV4_LABELS || !labels.all { isNumericLabel(it) }) return HostKind.DOMAIN
        val strict = labels.size == IPV4_LABELS && labels.all { isStrictOctet(it) }
        return if (strict) HostKind.IPV4 else HostKind.IPV4_OBFUSCATED
    }

    private fun Int.orEnd(s: String): Int = if (this < 0) s.length else this

    private fun isAsciiHex(c: Char): Boolean = c.code <= ASCII_MAX && Character.digit(c, HEX_RADIX) >= 0

    private fun isNumericLabel(label: String): Boolean {
        if (label.isEmpty()) return false
        if (label.length >= HEX_PREFIX_LENGTH && label.startsWith("0x", ignoreCase = true)) {
            return label.substring(HEX_PREFIX_LENGTH).all { isAsciiHex(it) }
        }
        return label.all { it in '0'..'9' }
    }

    private fun isStrictOctet(label: String): Boolean {
        val plainDigits = label.length <= MAX_OCTET_DIGITS && label.all { it in '0'..'9' }
        return plainDigits && (label.length == 1 || label[0] != '0') && label.toInt() <= MAX_IPV4_OCTET
    }
}
