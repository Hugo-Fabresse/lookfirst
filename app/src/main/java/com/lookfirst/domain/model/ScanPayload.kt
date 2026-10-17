package com.lookfirst.domain.model

import com.lookfirst.domain.host.HostNormalizer
import com.lookfirst.domain.host.HostResult
import java.math.BigDecimal

/** One name/value pair of a query string, percent-decoded. Duplicates are kept in order. Never printed. */
data class QueryParam(val name: String, val value: Untrusted) {
    override fun toString(): String = "QueryParam(len=${name.length}+${value.value.length})"
}

/**
 * Typed content of a QR code. Every variant keeps [raw], the sanitized (NFC) text. No analysis happens here.
 * All text coming from the QR is an [Untrusted], so no variant prints scanned content in `toString()`.
 */
sealed interface ScanPayload {
    val raw: Untrusted

    /**
     * http(s) URL split by a strict parser. [rawHost] and the path parts are as typed (not normalized): the
     * UTS #46 conversion comes with the analysis, which is why [hostKind] is [HostKind.UNRESOLVED] for any host
     * with non-ASCII characters or a `%`. [scheme] is empty when the URL had none ([UrlIssue.MISSING_SCHEME]).
     */
    data class Url(
        override val raw: Untrusted,
        val scheme: String,
        val userinfo: Untrusted?,
        val rawHost: Untrusted,
        val port: Int?,
        val hostKind: HostKind,
        val path: Untrusted,
        val query: Untrusted?,
        val fragment: Untrusted?,
        val nonAsciiHost: Boolean,
        val issues: Set<UrlIssue>,
    ) : ScanPayload {
        val hasUserinfo: Boolean get() = userinfo != null

        /** UTS #46 result for [rawHost], computed once per payload (null for an IPv6 literal). Not part of equals. */
        internal val normalizedHostResult: HostResult? by lazy(LazyThreadSafetyMode.NONE) {
            if (hostKind == HostKind.IPV6) null else HostNormalizer.normalize(rawHost.value)
        }
    }

    /** `WIFI:` credentials. */
    data class Wifi(
        override val raw: Untrusted,
        val ssid: Untrusted,
        val security: WifiSecurity,
        val password: Untrusted?,
        val hidden: Boolean,
    ) : ScanPayload

    data class Sms(override val raw: Untrusted, val number: Untrusted, val body: Untrusted?) : ScanPayload

    data class Tel(override val raw: Untrusted, val number: Untrusted) : ScanPayload

    /** `mailto:` or `MATMSG:`. */
    data class Mail(
        override val raw: Untrusted,
        val to: List<Untrusted>,
        val cc: List<Untrusted>,
        val bcc: List<Untrusted>,
        val subject: Untrusted?,
        val body: Untrusted?,
    ) : ScanPayload

    data class Geo(override val raw: Untrusted, val lat: Double, val lon: Double, val query: Untrusted?) : ScanPayload

    /** `otpauth://`. [secret] is the base32 seed as written. */
    data class Otpauth(
        override val raw: Untrusted,
        val type: String,
        val issuer: Untrusted?,
        val labelIssuer: Untrusted?,
        val account: Untrusted,
        val secret: Untrusted,
        val params: List<QueryParam>,
    ) : ScanPayload

    /** EPC / SEPA credit transfer QR ("BCD" lines). */
    data class Epc(
        override val raw: Untrusted,
        val version: String,
        val bic: Untrusted?,
        val name: Untrusted?,
        val iban: Untrusted,
        val amount: BigDecimal?,
        val currency: String?,
        val remittance: Untrusted?,
        val issues: Set<EpcIssue>,
    ) : ScanPayload

    /**
     * `bitcoin:`, `ethereum:`, ... [suffix] is what followed the address in the path (`@1/transfer`).
     * Units are kept apart and never merged: [amount] is in coin units (BIP-21), [value] in wei (EIP-681),
     * [tokenAmount] is the `uint256` of an ERC-20 `transfer`, whose [recipient] is the `address` parameter.
     */
    data class Crypto(
        override val raw: Untrusted,
        val coin: String,
        val address: Untrusted,
        val suffix: Untrusted,
        val function: Untrusted?,
        val recipient: Untrusted?,
        val amount: Untrusted?,
        val value: Untrusted?,
        val tokenAmount: Untrusted?,
        val label: Untrusted?,
        val message: Untrusted?,
        val params: List<QueryParam>,
    ) : ScanPayload

    /** Unknown but well-formed scheme (deep link). Copy only. */
    data class AppLink(override val raw: Untrusted, val scheme: String) : ScanPayload

    /** `javascript:`, `intent:`, `file:`, `content:`, `data:`, `vbscript:`... Copy only. */
    data class DangerousScheme(override val raw: Untrusted, val scheme: String) : ScanPayload

    data class Text(override val raw: Untrusted) : ScanPayload

    /** Claims a known format ([declaredType]) but is malformed, ambiguous, or otherwise unusable. */
    data class Unknown(override val raw: Untrusted, val declaredType: String?) : ScanPayload
}

/**
 * [UNRESOLVED]: the host has non-ASCII characters or a `%` escape, so what a browser would connect to is not
 * known until the UTS #46 normalizer has run (`１２７.０.０.１` or `%70aypal.com`). Do not trust DOMAIN/IPV4 then.
 */
enum class HostKind { DOMAIN, IPV4, IPV4_OBFUSCATED, IPV6, UNRESOLVED }

/** Structural oddities of a URL, reported for the later analysis. Nothing is corrected silently. */
enum class UrlIssue {
    MISSING_SCHEME,
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
    BAD_CURRENCY,
}
