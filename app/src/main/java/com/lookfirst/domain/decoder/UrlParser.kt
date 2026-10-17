package com.lookfirst.domain.decoder

import com.lookfirst.domain.model.HostKind
import com.lookfirst.domain.model.ScanPayload
import com.lookfirst.domain.model.Untrusted
import com.lookfirst.domain.model.UrlIssue

private const val MAX_HOST_LENGTH = 253
private const val MAX_PORT = 65_535
private const val MAX_PORT_DIGITS = 5
private const val ASCII_MAX = 0x7F
private const val FORBIDDEN_HOST_CHARS = "\"<>\\^`{|}@[]:#/?"

/**
 * Strict http(s) URL splitter in the WHATWG spirit: `\` counts as `/`, the host is whatever follows the LAST `@`
 * of the authority. It reports oddities as [UrlIssue]s instead of fixing them. IDN stays as typed
 * (the UTS #46 conversion belongs to the normalizer, not here).
 */
internal object UrlParser {
    /** [text] is the whole sanitized content (kept as raw); [afterScheme] is what follows `scheme:`. */
    fun parse(text: String, scheme: String, afterScheme: String, missingScheme: Boolean = false): ScanPayload.Url {
        // WHATWG pre-processing: tab, CR and LF are dropped everywhere, C0 controls and spaces at both ends.
        val rest = afterScheme.filterNot { it == '\t' || it == '\r' || it == '\n' }.trim { it <= ' ' }
        val issues = LinkedHashSet<UrlIssue>()
        if (missingScheme) issues += UrlIssue.MISSING_SCHEME
        val i = rest.takeWhile { it == '/' || it == '\\' }.length
        if (i < 2) issues += UrlIssue.MISSING_SLASHES
        if (i > 2) issues += UrlIssue.EXTRA_SLASHES

        val authorityEnd = rest.indexOfAny(charArrayOf('/', '\\', '?', '#'), i).orEnd(rest)
        val authority = rest.substring(i, authorityEnd)
        val tail = rest.substring(authorityEnd)
        val beforeQuery = rest.substring(0, rest.indexOfAny(charArrayOf('?', '#')).orEnd(rest))
        if (beforeQuery.indexOf('\\') >= 0) issues += UrlIssue.BACKSLASH
        if (text.trim { it <= ' ' }.any { isControlOrSpace(it) }) issues += UrlIssue.CONTROL_OR_SPACE

        val at = authority.lastIndexOf('@')
        val userinfo = if (at >= 0) authority.substring(0, at) else null
        val hostPort = splitHostPort(authority.substring(at + 1), issues)
        val kind = classifyHost(hostPort.host, hostPort.bracketed, issues)

        val parts = splitTail(tail)
        return ScanPayload.Url(
            raw = Untrusted(text),
            scheme = scheme,
            userinfo = userinfo?.let { Untrusted(it) },
            rawHost = Untrusted(hostPort.host),
            port = hostPort.port,
            hostKind = kind,
            path = Untrusted(parts.path),
            query = parts.query?.let { Untrusted(it) },
            fragment = parts.fragment?.let { Untrusted(it) },
            nonAsciiHost = hostPort.host.any { it.code > ASCII_MAX },
            issues = issues,
        )
    }

    private class TailParts(val path: String, val query: String?, val fragment: String?)

    private fun splitTail(tail: String): TailParts {
        val fragmentStart = tail.indexOf('#')
        val beforeFragment = if (fragmentStart < 0) tail else tail.substring(0, fragmentStart)
        val queryStart = beforeFragment.indexOf('?')
        return TailParts(
            path = if (queryStart < 0) beforeFragment else beforeFragment.substring(0, queryStart),
            query = if (queryStart < 0) null else beforeFragment.substring(queryStart + 1),
            fragment = if (fragmentStart < 0) null else tail.substring(fragmentStart + 1),
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
        val kind = if (bracketed) classifyIpv6(host, issues) else HostNumbers.classify(host)
        if (!bracketed && host.any { isForbiddenHostChar(it) }) {
            issues += UrlIssue.INVALID_HOST_CHARS
        }
        return if (host.any { it.code > ASCII_MAX || it == '%' }) HostKind.UNRESOLVED else kind
    }

    private fun isControlOrSpace(c: Char): Boolean = c <= ' ' || c.code == ASCII_MAX

    private fun isForbiddenHostChar(c: Char): Boolean = c <= ' ' || c.code == ASCII_MAX || c in FORBIDDEN_HOST_CHARS

    private fun classifyIpv6(host: String, issues: MutableSet<UrlIssue>): HostKind {
        val valid = host.contains(':') && host.all { it == ':' || it == '.' || HostNumbers.isAsciiHex(it) }
        if (!valid) issues += UrlIssue.INVALID_HOST_CHARS
        return HostKind.IPV6
    }

    private fun Int.orEnd(s: String): Int = if (this < 0) s.length else this
}
