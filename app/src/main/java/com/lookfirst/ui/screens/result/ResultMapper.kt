package com.lookfirst.ui.screens.result

import com.lookfirst.R
import com.lookfirst.domain.analysis.UserContentPlatforms
import com.lookfirst.domain.host.HostResult
import com.lookfirst.domain.host.Ipv4Check
import com.lookfirst.domain.host.PublicSuffixList
import com.lookfirst.domain.model.HostKind
import com.lookfirst.domain.model.ParseResult
import com.lookfirst.domain.model.ScanPayload
import com.lookfirst.domain.model.Untrusted
import com.lookfirst.domain.open.CanonicalUrl

private const val CRYPTO_GROUP_SIZE = 4

/** Pure mapping from the decoder output to what the Result screen shows. No analysis, no verdict. */
internal fun ParseResult.toUiState(multipleCodes: Boolean = false): ResultUiState = when (this) {
    is ParseResult.TooLarge -> ResultUiState.Unreadable(UnreadableReason.TOO_LARGE)
    ParseResult.Empty -> ResultUiState.Unreadable(UnreadableReason.EMPTY)
    is ParseResult.Ok -> ResultUiState.Content(
        ResultContent(
            typeLabel = payload.typeLabel(),
            host = (payload as? ScanPayload.Url)?.hostDisplay(),
            isWebLink = payload is ScanPayload.Url,
            openKind = payload.openKind(),
            fields = payload.fields(),
            exact = payload.raw,
            hiddenCharCount = hiddenChars.size + fieldHiddenChars.sumOf { it.chars.size },
            containsSecret = payload.containsSecret(),
            copyOnly = payload is ScanPayload.AppLink || payload is ScanPayload.DangerousScheme,
            multipleCodes = multipleCodes,
        ),
    )
}

/** The host after UTS #46 (Unicode and technical forms) with its registrable domain; the typed host if invalid. */
internal fun ScanPayload.Url.hostDisplay(): HostDisplay? {
    if (rawHost.value.isEmpty()) return null
    val host = (normalizedHostResult as? HostResult.Normalized)?.host
        ?: return HostDisplay(rawHost, technical = null, strongFrom = 0, normalized = false)
    val suffixes = if (host.kind == HostKind.DOMAIN) PublicSuffixList.instance else null
    val pslCount = suffixes?.suffixLabelCount(host.asciiLabels) ?: 0
    // A tenant of an open platform (paypal-login.wordpress.com) is bold from its own label, like on blogspot.com.
    val tenantName = if (suffixes == null) {
        null
    } else {
        suffixes.registrableDomain(host.ascii)?.let {
            UserContentPlatforms.registrableFor(host.ascii, it)
        }
    }
    val suffixCount = if (tenantName != null && tenantName.count { it == '.' } > pslCount) {
        tenantName.count { it == '.' }
    } else {
        pslCount
    }
    val nameIndex = host.unicodeLabels.size - suffixCount - 1
    val strongFrom = if (suffixes == null ||
        nameIndex < 0
    ) {
        0
    } else {
        host.unicodeLabels.take(nameIndex).sumOf { it.length + 1 }
    }
    // An IPv4 written in hex, octal or short form is shown as the dotted decimal address a browser connects to.
    val dotted = if (host.kind == HostKind.IPV4 || host.kind == HostKind.IPV4_OBFUSCATED) {
        Ipv4Check.toDotted(host.ascii)
    } else {
        null
    }
    if (dotted != null) return HostDisplay(Untrusted(dotted), technical = null, strongFrom = 0, normalized = true)
    return HostDisplay(
        shown = Untrusted(host.unicode),
        technical = host.ascii.takeIf { host.isInternational },
        strongFrom = strongFrom,
        normalized = true,
    )
}

internal fun ScanPayload.openKind(): OpenKind? = when (this) {
    is ScanPayload.Url -> if (CanonicalUrl.of(this) != null) OpenKind.BROWSER else null
    is ScanPayload.Tel -> OpenKind.DIALER
    is ScanPayload.Sms -> OpenKind.SMS
    is ScanPayload.Mail -> OpenKind.MAIL
    is ScanPayload.Geo -> OpenKind.MAPS
    is ScanPayload.Wifi -> OpenKind.WIFI
    else -> null
}

internal fun ScanPayload.containsSecret(): Boolean = this is ScanPayload.Otpauth ||
    (this is ScanPayload.Wifi && password != null)

private fun ScanPayload.typeLabel(): Int = when (this) {
    is ScanPayload.Url -> urlTypeLabel(scheme)
    is ScanPayload.Wifi -> R.string.type_wifi
    is ScanPayload.Sms -> R.string.type_sms
    is ScanPayload.Tel -> R.string.type_tel
    is ScanPayload.Mail -> R.string.type_mail
    is ScanPayload.Geo -> R.string.type_geo
    is ScanPayload.Otpauth -> R.string.type_otpauth
    is ScanPayload.Epc -> R.string.type_payment
    is ScanPayload.Crypto -> R.string.type_crypto
    is ScanPayload.AppLink -> R.string.type_applink
    is ScanPayload.DangerousScheme -> R.string.type_dangerous_scheme
    is ScanPayload.Text -> R.string.type_text
    is ScanPayload.Unknown -> R.string.type_unknown
}

private fun urlTypeLabel(scheme: String): Int = when (scheme) {
    "https" -> R.string.type_url_https
    "http" -> R.string.type_url_http
    else -> R.string.type_url_no_scheme
}

private fun ScanPayload.fields(): List<ResultField> = when (this) {
    is ScanPayload.Url -> urlFields()
    is ScanPayload.Wifi -> wifiFields()
    is ScanPayload.Sms -> listOf(ResultField(R.string.field_number, number))
    is ScanPayload.Tel -> listOf(ResultField(R.string.field_number, number))
    is ScanPayload.Mail -> to.map { ResultField(R.string.field_to, it) }
    is ScanPayload.Geo -> listOf(ResultField(R.string.field_coordinates, Untrusted("$lat, $lon")))
    is ScanPayload.Otpauth -> otpauthFields()
    is ScanPayload.Epc -> epcFields()
    is ScanPayload.Crypto -> listOf(
        ResultField(R.string.field_address, Untrusted(address.value.chunked(CRYPTO_GROUP_SIZE).joinToString(" "))),
    )
    is ScanPayload.AppLink, is ScanPayload.DangerousScheme, is ScanPayload.Text, is ScanPayload.Unknown -> emptyList()
}

private fun ScanPayload.Wifi.wifiFields(): List<ResultField> = listOfNotNull(
    ResultField(R.string.field_ssid, ssid),
    password?.let { ResultField(R.string.field_password, it, FieldKind.SECRET) },
)

private fun ScanPayload.Otpauth.otpauthFields(): List<ResultField> = listOfNotNull(
    (issuer ?: labelIssuer)?.let { ResultField(R.string.field_issuer, it) },
    ResultField(R.string.field_account, account),
    ResultField(R.string.field_secret, secret, FieldKind.SECRET),
)

private fun ScanPayload.Epc.epcFields(): List<ResultField> = listOfNotNull(
    name?.let { ResultField(R.string.field_beneficiary, it) },
    ResultField(R.string.field_iban, iban),
    amount?.let { ResultField(R.string.field_amount, Untrusted(formatAmount(it.toPlainString(), currency))) },
)

private fun ScanPayload.Url.urlFields(): List<ResultField> = listOfNotNull(
    userinfo?.let { ResultField(R.string.field_userinfo, it, FieldKind.WARNING) },
    port?.let { ResultField(R.string.field_port, Untrusted(it.toString())) },
)

private fun formatAmount(amount: String, currency: String?): String = listOfNotNull(amount, currency).joinToString(" ")
