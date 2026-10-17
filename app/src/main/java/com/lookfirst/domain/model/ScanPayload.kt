package com.lookfirst.domain.model

import java.math.BigDecimal

/** One name/value pair of a query string, percent-decoded. Duplicates are kept in order. */
data class QueryParam(val name: String, val value: String)

/** Typed content of a QR code. Every variant keeps [raw], the sanitized (NFC) text. No analysis happens here. */
sealed interface ScanPayload {
    val raw: String

    /** http(s) URL split by a strict parser; [host] and the path parts are raw (not normalized). */
    data class Url(
        override val raw: String,
        val scheme: String,
        val userinfo: String?,
        val host: String,
        val port: Int?,
        val hostKind: HostKind,
        val path: String,
        val query: String?,
        val fragment: String?,
        val nonAsciiHost: Boolean,
        val issues: Set<UrlIssue>,
    ) : ScanPayload {
        val hasUserinfo: Boolean get() = userinfo != null
    }

    /** `WIFI:` credentials. Never logged: see [toString]. */
    data class Wifi(
        override val raw: String,
        val ssid: String,
        val security: WifiSecurity,
        val password: String?,
        val hidden: Boolean,
    ) : ScanPayload {
        override fun toString(): String = "Wifi(redacted)"
    }

    data class Sms(override val raw: String, val number: String, val body: String?) : ScanPayload

    data class Tel(override val raw: String, val number: String) : ScanPayload

    /** `mailto:` or `MATMSG:`. */
    data class Mail(
        override val raw: String,
        val to: List<String>,
        val cc: List<String>,
        val bcc: List<String>,
        val subject: String?,
        val body: String?,
    ) : ScanPayload

    data class Geo(override val raw: String, val lat: Double, val lon: Double, val query: String?) : ScanPayload

    /** `otpauth://`. Never logged: see [toString]. [secret] is the base32 seed as written. */
    data class Otpauth(
        override val raw: String,
        val type: String,
        val issuer: String?,
        val labelIssuer: String?,
        val account: String,
        val secret: String,
        val params: List<QueryParam>,
    ) : ScanPayload {
        override fun toString(): String = "Otpauth(redacted)"
    }

    /** EPC / SEPA credit transfer QR ("BCD" lines). */
    data class Epc(
        override val raw: String,
        val version: String,
        val bic: String?,
        val name: String?,
        val iban: String,
        val amount: BigDecimal?,
        val currency: String?,
        val remittance: String?,
        val issues: Set<EpcIssue>,
    ) : ScanPayload

    /** `bitcoin:`, `ethereum:`, ... [suffix] is what followed the address in the path (`@1/transfer`). */
    data class Crypto(
        override val raw: String,
        val coin: String,
        val address: String,
        val suffix: String,
        val amount: String?,
        val label: String?,
        val message: String?,
        val params: List<QueryParam>,
    ) : ScanPayload

    /** Unknown but well-formed scheme (deep link). Copy only. */
    data class AppLink(override val raw: String, val scheme: String) : ScanPayload

    /** `javascript:`, `intent:`, `file:`, `content:`, `data:`, `vbscript:`... Copy only. */
    data class DangerousScheme(override val raw: String, val scheme: String) : ScanPayload

    data class Text(override val raw: String) : ScanPayload

    /** Claims a known format ([declaredType]) but is malformed, or is otherwise unusable. */
    data class Unknown(override val raw: String, val declaredType: String?) : ScanPayload
}

enum class HostKind { DOMAIN, IPV4, IPV4_OBFUSCATED, IPV6 }

/** Structural oddities of a URL, reported for the later analysis. Nothing is corrected silently. */
enum class UrlIssue {
    MISSING_SLASHES,
    EXTRA_SLASHES,
    BACKSLASH,
    CONTROL_OR_SPACE,
    EMPTY_HOST,
    HOST_TOO_LONG,
    INVALID_HOST_CHARS,
    HOST_PERCENT_ENCODED,
    INVALID_PORT,
}

enum class WifiSecurity { NONE, WEP, WPA, WPA3, UNKNOWN }

enum class EpcIssue {
    BAD_IBAN_FORMAT,
    BAD_IBAN_CHECKSUM,
    BAD_AMOUNT,
    BAD_BIC,
}
