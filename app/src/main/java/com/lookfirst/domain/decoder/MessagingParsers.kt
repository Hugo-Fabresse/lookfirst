package com.lookfirst.domain.decoder

import com.lookfirst.domain.model.QueryParam
import com.lookfirst.domain.model.ScanPayload
import com.lookfirst.domain.model.Untrusted

private const val MAX_RECIPIENTS = 50
private const val MAX_LATITUDE = 90.0
private const val MAX_LONGITUDE = 180.0

/** Splits `path?query` (the query may be absent) and parses the query; null when it has too many params. */
private fun splitQuery(rest: String): Pair<String, List<QueryParam>>? {
    val q = rest.indexOf('?')
    if (q < 0) return rest to emptyList()
    val params = PercentCodec.parseQuery(rest.substring(q + 1)) ?: return null
    return rest.substring(0, q) to params
}

private fun unknown(text: String, type: String) = ScanPayload.Unknown(Untrusted(text), type)

internal object SmsParser {
    private fun isColonForm(colon: Int, query: Int): Boolean = colon >= 0 && (query < 0 || colon < query)

    /** `sms:`/`smsto:` (`sms:123?body=x`, `sms:123&body=x`, `SMSTO:123:text`). The body is shown, never sent. */
    fun parse(text: String, scheme: String, afterScheme: String): ScanPayload {
        val colon = afterScheme.indexOf(':')
        val query = afterScheme.indexOf('?')
        if (scheme == "smsto" && isColonForm(colon, query)) {
            return build(text, afterScheme.substring(0, colon).trim(), nfc(afterScheme.substring(colon + 1)))
        }
        val (path, params) = splitQuery(afterScheme) ?: return unknown(text, "SMS")
        val amp = path.indexOf('&')
        val number = PercentCodec.decode(if (amp < 0) path else path.substring(0, amp)).trim()
        val ampParams =
            if (amp <
                0
            ) {
                emptyList()
            } else {
                PercentCodec.parseQuery(path.substring(amp + 1)) ?: return unknown(text, "SMS")
            }
        return build(text, number, (params + ampParams).firstValue("body")?.value)
    }

    private fun build(text: String, number: String, body: String?): ScanPayload = if (number.isEmpty()) {
        unknown(text, "SMS")
    } else {
        ScanPayload.Sms(Untrusted(text), Untrusted(number), body?.let { Untrusted(it) })
    }
}

internal object TelParser {
    fun parse(text: String, afterScheme: String): ScanPayload {
        val number = PercentCodec.decode(afterScheme).trim()
        return if (number.isEmpty()) unknown(text, "TEL") else ScanPayload.Tel(Untrusted(text), Untrusted(number))
    }
}

internal object MailParser {
    fun parseMailto(text: String, afterScheme: String): ScanPayload {
        val (path, params) = splitQuery(afterScheme) ?: return unknown(text, "MAILTO")
        val to =
            recipients(PercentCodec.decode(path)) +
                params.filter { it.name == "to" }.flatMap { recipients(it.value.value) }
        return build(
            text,
            to,
            params.filter { it.name == "cc" }.flatMap { recipients(it.value.value) },
            params.filter { it.name == "bcc" }.flatMap { recipients(it.value.value) },
            params.firstValue("subject")?.value,
            params.firstValue("body")?.value,
        )
    }

    /** `MATMSG:TO:a@b.c;SUB:subject;BODY:text;;` */
    fun parseMatmsg(text: String, afterScheme: String): ScanPayload {
        val fields = EscapedFields.scan(afterScheme) ?: return unknown(text, "MATMSG")
        fun first(key: String) = fields.firstOrNull { it.first == key }?.second
        return build(text, recipients(first("TO").orEmpty()), emptyList(), emptyList(), first("SUB"), first("BODY"))
    }

    private fun build(
        text: String,
        to: List<String>,
        cc: List<String>,
        bcc: List<String>,
        subject: String?,
        body: String?,
    ): ScanPayload {
        val empty = to.isEmpty() && cc.isEmpty() && bcc.isEmpty() && subject == null && body == null
        if (empty || to.size + cc.size + bcc.size > MAX_RECIPIENTS) return unknown(text, "MAILTO")
        return ScanPayload.Mail(
            Untrusted(text),
            to.map { Untrusted(it) },
            cc.map { Untrusted(it) },
            bcc.map { Untrusted(it) },
            subject?.let { Untrusted(it) },
            body?.let { Untrusted(it) },
        )
    }

    private fun recipients(list: String): List<String> =
        list.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
}

internal object GeoParser {
    /** `geo:lat,lon[,alt][;crs=...;u=...][?q=label]` */
    fun parse(text: String, afterScheme: String): ScanPayload {
        val (path, params) = splitQuery(afterScheme) ?: return unknown(text, "GEO")
        val coords = path.substringBefore(';').split(',')
        val lat = coords.getOrNull(0)?.let { parseCoordinate(it, MAX_LATITUDE) }
        val lon = coords.getOrNull(1)?.let { parseCoordinate(it, MAX_LONGITUDE) }
        if (lat == null || lon == null || coords.size > MAX_GEO_PARTS) return unknown(text, "GEO")
        return ScanPayload.Geo(Untrusted(text), lat, lon, params.firstValue("q"))
    }

    /** Plain decimal only: rejects `NaN`, `Infinity`, hex floats and exponents that `toDouble` would accept. */
    private fun parseCoordinate(s: String, limit: Double): Double? {
        val ok =
            s.isNotEmpty() &&
                s.length <= MAX_COORD_LENGTH &&
                s.all { it in '0'..'9' || it == '.' || it == '-' || it == '+' }
        val value = if (ok) s.toDoubleOrNull() else null
        return value?.takeIf { it >= -limit && it <= limit }
    }

    private const val MAX_COORD_LENGTH = 32
    private const val MAX_GEO_PARTS = 3
}
