package com.lookfirst.data.network

import com.lookfirst.domain.decoder.PayloadParser
import com.lookfirst.domain.model.ParseResult
import com.lookfirst.domain.model.ScanPayload
import com.lookfirst.domain.open.CanonicalUrl

/** Longest `Location` looked at. A longer one is not a redirect target, it is an attack on the parser. */
internal const val MAX_LOCATION_CHARS = 4096
private const val DEL = 0x7F
private const val HTTP = "http"
private const val HTTPS = "https"

/** What a `Location` header leads to. */
internal sealed interface NextHop {
    /** Followed: https, same rules as the scanned address. */
    data class Secure(val url: CanonicalUrl) : NextHop

    /** Not followed: the target is `http://` (the app never speaks cleartext). */
    data class Insecure(val url: CanonicalUrl) : NextHop

    /** Not followed: unusable, [raw] is the text as received (cut by the caller before display). */
    data class Unusable(val raw: String) : NextHop
}

/**
 * Turns a `Location` into the next hop. The reference is resolved against the current address by a linear algorithm,
 * then it goes through the same parser and host normalizer as a scanned address ([PayloadParser], [CanonicalUrl]),
 * so a hop is judged exactly like a scan. Nothing is guessed: whatever is not clearly an http(s) address is unusable.
 */
internal object LocationResolver {
    fun resolve(base: CanonicalUrl, location: String): NextHop {
        val canonical = canonicalOf(base, location.trim(' ')) ?: return NextHop.Unusable(location)
        return when (canonical.scheme) {
            HTTPS -> NextHop.Secure(canonical)
            HTTP -> NextHop.Insecure(canonical)
            else -> NextHop.Unusable(location)
        }
    }

    private fun canonicalOf(base: CanonicalUrl, reference: String): CanonicalUrl? {
        val usable = reference.isNotEmpty() &&
            reference.length <= MAX_LOCATION_CHARS &&
            reference.none { it < ' ' || it.code == DEL }
        if (!usable) return null
        val parsed = (PayloadParser.parse(absolute(base, reference)) as? ParseResult.Ok)?.payload as? ScanPayload.Url
        // `host:8080/x` reads as an address without scheme for the decoder, but as scheme `host` for a browser: refuse.
        return parsed?.takeIf { it.scheme.isNotEmpty() }?.let { CanonicalUrl.of(it) }
    }

    /** RFC 3986 section 5, reduced to what a redirect needs; `\` counts as `/` like in a browser. */
    private fun absolute(base: CanonicalUrl, reference: String): String {
        val origin = origin(base)
        val pathAndQuery = base.value.removePrefix(origin).substringBefore('#')
        return when {
            hasScheme(reference) -> reference
            isSlash(reference[0]) && reference.length > 1 && isSlash(reference[1]) -> "${base.scheme}:$reference"
            isSlash(reference[0]) -> origin + removeDotSegments(reference.replace('\\', '/'))
            reference[0] == '?' -> origin + pathAndQuery.substringBefore('?') + reference
            reference[0] == '#' -> origin + pathAndQuery + reference
            else -> origin + removeDotSegments(directory(pathAndQuery) + reference)
        }
    }

    fun origin(base: CanonicalUrl): String = "${base.scheme}://${base.host}${base.port?.let { ":$it" }.orEmpty()}"

    private fun isSlash(c: Char) = c == '/' || c == '\\'

    /** `scheme:` at the very start: a letter, then letters, digits, `+`, `-`, `.`, then `:`. */
    private fun hasScheme(reference: String): Boolean {
        if (!reference[0].isAsciiLetter()) return false
        val end = reference.indexOfFirst {
            !(it.isAsciiLetter() || it in '0'..'9' || it == '+' || it == '-' || it == '.')
        }
        return end > 0 && reference[end] == ':'
    }

    private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'

    /** The path of [pathAndQuery] up to and including its last `/`. */
    private fun directory(pathAndQuery: String): String {
        val path = pathAndQuery.substringBefore('?')
        return path.substring(0, path.lastIndexOf('/') + 1)
    }

    /** Removes `.` and `..` segments from the path part of [target]; the query and the fragment are left as they are. */
    private fun removeDotSegments(target: String): String {
        val tailStart = target.indexOfAny(charArrayOf('?', '#')).let { if (it < 0) target.length else it }
        val segments = ArrayDeque<String>()
        val parts = target.substring(0, tailStart).split('/')
        for ((index, part) in parts.withIndex()) {
            val last = index == parts.lastIndex
            when (part) {
                "." -> if (last) segments.addLast("")
                ".." -> {
                    if (segments.size > 1) segments.removeLast()
                    if (last) segments.addLast("")
                }
                else -> segments.addLast(part)
            }
        }
        val path = segments.joinToString("/")
        return (if (path.startsWith("/")) path else "/$path") + target.substring(tailStart)
    }
}
