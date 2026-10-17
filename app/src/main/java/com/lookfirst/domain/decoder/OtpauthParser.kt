package com.lookfirst.domain.decoder

import com.lookfirst.domain.model.ScanPayload
import java.util.Locale

private const val MAX_LABEL = 256

/** `otpauth://totp|hotp/Issuer:account?secret=BASE32&issuer=...`. The secret is kept, never logged. */
internal object OtpauthParser {
    private fun isValid(type: String, label: String): Boolean =
        (type == "totp" || type == "hotp") && label.length <= MAX_LABEL

    fun parse(text: String, afterScheme: String): ScanPayload {
        val rest = afterScheme.removePrefix("//")
        val slash = rest.indexOf('/')
        val type = (if (slash < 0) rest else rest.substring(0, slash)).lowercase(Locale.ROOT)
        val afterType = if (slash < 0) "" else rest.substring(slash + 1)
        val q = afterType.indexOf('?')
        val label = PercentCodec.decode(if (q < 0) afterType else afterType.substring(0, q))
        val params = if (q < 0) emptyList() else PercentCodec.parseQuery(afterType.substring(q + 1))
        val secret = params?.firstValue("secret")
        if (!isValid(type, label) || params == null || secret.isNullOrEmpty()) {
            return ScanPayload.Unknown(text, "OTPAUTH")
        }
        val colon = label.indexOf(':')
        val labelIssuer = if (colon < 0) null else label.substring(0, colon).trim().ifEmpty { null }
        val account = (if (colon < 0) label else label.substring(colon + 1)).trim()
        return ScanPayload.Otpauth(
            raw = text,
            type = type,
            issuer = params.firstValue("issuer") ?: labelIssuer,
            labelIssuer = labelIssuer,
            account = account,
            secret = secret,
            params = params.filter { it.name != "secret" },
        )
    }
}
