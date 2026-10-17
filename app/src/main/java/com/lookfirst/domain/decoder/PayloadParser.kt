package com.lookfirst.domain.decoder

import com.lookfirst.domain.model.ParseResult
import com.lookfirst.domain.model.ScanPayload
import com.lookfirst.domain.model.Untrusted
import java.util.Locale

private const val MAX_SCHEME_LENGTH = 32
private const val DEL = 0x7F
private const val C1_END = 0x9F
private const val BRAILLE_BLANK = 0x2800

/**
 * Raw scanned string -> [ParseResult]. Pure, deterministic, linear, never throws on any input.
 * Decodes only: no verdict, no normalization, no network. Nothing here opens or executes anything.
 */
object PayloadParser {
    fun parse(raw: String): ParseResult = when (val sanitized = ContentSanitizer.sanitize(raw)) {
        is SanitizeResult.TooLarge -> ParseResult.TooLarge(sanitized.length, ContentSanitizer.MAX_CHARS)
        SanitizeResult.Empty -> ParseResult.Empty
        is SanitizeResult.Clean -> parsePayload(sanitized.text).let {
            ParseResult.Ok(it, sanitized.hiddenChars, DecodedFields.hiddenChars(it))
        }
    }

    private fun parsePayload(text: String): ScanPayload {
        if (EpcParser.looksLikeEpc(text)) return EpcParser.parse(text)
        val head = readScheme(text) ?: return ScanPayload.Text(Untrusted(text))
        val afterScheme = text.substring(head.afterColon)
        val scheme = head.scheme
        if (scheme.contains('.') && isHostPort(afterScheme)) {
            val schemeless = "//" + text.trim { it <= ' ' }
            return UrlParser.parse(text, "", schemeless, missingScheme = true)
        }
        return when {
            scheme == "http" || scheme == "https" -> UrlParser.parse(text, scheme, afterScheme)
            scheme in DANGEROUS_SCHEMES -> ScanPayload.DangerousScheme(Untrusted(text), scheme)
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
        if (text.none { it <= ' ' }) ScanPayload.AppLink(Untrusted(text), scheme) else ScanPayload.Text(Untrusted(text))

    /** `secure-login.example:8443/verify`: digits after the colon, then nothing or `/`, `?`, `#`. */
    private fun isHostPort(afterColon: String): Boolean {
        val digits = afterColon.takeWhile { it in '0'..'9' }
        val next = afterColon.getOrNull(digits.length)
        return digits.isNotEmpty() && (next == null || next == '/' || next == '?' || next == '#')
    }

    /** Leading characters a browser or a human would not see; used for classification only. */
    private fun isInvisibleLead(cp: Int): Boolean = cp <= ' '.code ||
        cp in DEL..C1_END ||
        Character.isSpaceChar(cp) ||
        ContentSanitizer.isDefaultIgnorable(cp) ||
        cp == BRAILLE_BLANK

    private class SchemeHead(val scheme: String, val afterColon: Int)

    /**
     * RFC 3986 scheme, lowercased. Leading control/space and embedded tab/CR/LF are skipped the way browsers do,
     * so `" java\nscript:..."` is still seen as `javascript`.
     */
    private fun readScheme(text: String): SchemeHead? {
        val scheme = StringBuilder()
        var i = 0
        while (i < text.length && isInvisibleLead(text.codePointAt(i))) i += Character.charCount(text.codePointAt(i))
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
