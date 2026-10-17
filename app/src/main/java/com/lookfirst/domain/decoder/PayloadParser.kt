package com.lookfirst.domain.decoder

import com.lookfirst.domain.model.ParseResult
import com.lookfirst.domain.model.ScanPayload
import java.util.Locale

private const val MAX_SCHEME_LENGTH = 32

/**
 * Raw scanned string -> [ParseResult]. Pure, deterministic, linear, never throws on any input.
 * Decodes only: no verdict, no normalization, no network. Nothing here opens or executes anything.
 */
object PayloadParser {
    fun parse(raw: String): ParseResult = when (val sanitized = ContentSanitizer.sanitize(raw)) {
        is SanitizeResult.TooLarge -> ParseResult.TooLarge(sanitized.length, ContentSanitizer.MAX_CHARS)
        SanitizeResult.Empty -> ParseResult.Empty
        is SanitizeResult.Clean -> ParseResult.Ok(parsePayload(sanitized.text), sanitized.hiddenChars)
    }

    private fun parsePayload(text: String): ScanPayload {
        if (EpcParser.looksLikeEpc(text)) return EpcParser.parse(text)
        val head = readScheme(text) ?: return ScanPayload.Text(text)
        val afterScheme = text.substring(head.afterColon)
        val scheme = head.scheme
        return when {
            scheme == "http" || scheme == "https" -> UrlParser.parse(text, scheme, afterScheme)
            scheme in DANGEROUS_SCHEMES -> ScanPayload.DangerousScheme(text, scheme)
            else -> parseKnown(text, scheme, afterScheme.trim()) ?: parseUnlisted(text, scheme)
        }
    }

    private fun parseKnown(text: String, scheme: String, rest: String): ScanPayload? = when (scheme) {
        "wifi" -> WifiParser.parse(text, rest)
        "sms", "smsto" -> SmsParser.parse(text, scheme, rest)
        "tel" -> TelParser.parse(text, rest)
        "mailto" -> MailParser.parseMailto(text, rest)
        "matmsg" -> MailParser.parseMatmsg(text, rest)
        "geo" -> GeoParser.parse(text, rest)
        "otpauth" -> OtpauthParser.parse(text, rest)
        in CryptoParser.SCHEMES -> CryptoParser.parse(text, scheme, rest)
        else -> null
    }

    /** A well-formed unknown scheme with no whitespace is a deep link; anything else ("Note: buy milk") is text. */
    private fun parseUnlisted(text: String, scheme: String): ScanPayload =
        if (text.none { it <= ' ' }) ScanPayload.AppLink(text, scheme) else ScanPayload.Text(text)

    private class SchemeHead(val scheme: String, val afterColon: Int)

    /**
     * RFC 3986 scheme, lowercased. Leading control/space and embedded tab/CR/LF are skipped the way browsers do,
     * so `" java\nscript:..."` is still seen as `javascript`.
     */
    private fun readScheme(text: String): SchemeHead? {
        val scheme = StringBuilder()
        var i = 0
        while (i < text.length && text[i] <= ' ') i++
        while (i < text.length && scheme.length <= MAX_SCHEME_LENGTH) {
            val c = text[i++]
            when {
                c == ':' -> return if (scheme.isNotEmpty() && scheme[0] in LETTERS) head(scheme, i) else null
                c == '\t' || c == '\r' || c == '\n' -> Unit
                isSchemeChar(c) -> scheme.append(c)
                else -> return null
            }
        }
        return null
    }

    private fun isSchemeChar(c: Char): Boolean = c in LETTERS || c in '0'..'9' || c == '+' || c == '-' || c == '.'

    private fun head(scheme: CharSequence, afterColon: Int) =
        SchemeHead(scheme.toString().lowercase(Locale.ROOT), afterColon)

    private val LETTERS = ('a'..'z') + ('A'..'Z')

    private val DANGEROUS_SCHEMES =
        setOf(
            "javascript", "vbscript", "data", "intent", "android-app", "file", "content",
            "blob", "filesystem", "jar", "about", "view-source",
        )
}
