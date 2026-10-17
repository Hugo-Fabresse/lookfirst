package com.lookfirst.domain.decoder

import com.lookfirst.domain.model.QueryParam
import com.lookfirst.domain.model.ScanPayload

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

internal object SmsParser {
    private fun isColonForm(colon: Int, query: Int): Boolean = colon >= 0 && (query < 0 || colon < query)

    /** `sms:`/`smsto:` (`sms:123?body=x`, `sms:123&body=x`, `SMSTO:123:text`). The body is shown, never sent. */
    fun parse(text: String, scheme: String, afterScheme: String): ScanPayload {
        val colon = afterScheme.indexOf(':')
        val query = afterScheme.indexOf('?')
        if (scheme == "smsto" && isColonForm(colon, query)) {
            val number = afterScheme.substring(0, colon).trim()
            val body = afterScheme.substring(colon + 1)
            return if (number.isEmpty()) ScanPayload.Unknown(text, "SMS") else ScanPayload.Sms(text, number, body)
        }
        val (path, params) = splitQuery(afterScheme) ?: return ScanPayload.Unknown(text, "SMS")
        val amp = path.indexOf('&')
        val number = PercentCodec.decode(if (amp < 0) path else path.substring(0, amp)).trim()
        val ampParams = if (amp < 0) emptyList() else PercentCodec.parseQuery(path.substring(amp + 1)).orEmpty()
        val body = (params + ampParams).firstValue("body")
        return if (number.isEmpty()) ScanPayload.Unknown(text, "SMS") else ScanPayload.Sms(text, number, body)
    }
}

internal object TelParser {
    fun parse(text: String, afterScheme: String): ScanPayload {
        val number = PercentCodec.decode(afterScheme).trim()
        return if (number.isEmpty()) ScanPayload.Unknown(text, "TEL") else ScanPayload.Tel(text, number)
    }
}

internal object MailParser {
    fun parseMailto(text: String, afterScheme: String): ScanPayload {
        val (path, params) = splitQuery(afterScheme) ?: return ScanPayload.Unknown(text, "MAILTO")
        val to =
            recipients(PercentCodec.decode(path)) + params.filter { it.name == "to" }.flatMap { recipients(it.value) }
        return build(
            text,
            to,
            params.filter { it.name == "cc" }.flatMap { recipients(it.value) },
            params.filter { it.name == "bcc" }.flatMap { recipients(it.value) },
            params.firstValue("subject"),
            params.firstValue("body"),
        )
    }

    /** `MATMSG:TO:a@b.c;SUB:subject;BODY:text;;` */
    fun parseMatmsg(text: String, afterScheme: String): ScanPayload {
        val fields = EscapedFields.scan(afterScheme) ?: return ScanPayload.Unknown(text, "MATMSG")
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
        if (empty || to.size + cc.size + bcc.size > MAX_RECIPIENTS) return ScanPayload.Unknown(text, "MAILTO")
        return ScanPayload.Mail(text, to, cc, bcc, subject, body)
    }

    private fun recipients(list: String): List<String> = list.split(',', ';').map {
        it.trim()
    }.filter { it.isNotEmpty() }
}

internal object GeoParser {
    /** `geo:lat,lon[,alt][;crs=...;u=...][?q=label]` */
    fun parse(text: String, afterScheme: String): ScanPayload {
        val (path, params) = splitQuery(afterScheme) ?: return ScanPayload.Unknown(text, "GEO")
        val coords = path.substringBefore(';').split(',')
        val lat = coords.getOrNull(0)?.let { parseCoordinate(it, MAX_LATITUDE) }
        val lon = coords.getOrNull(1)?.let { parseCoordinate(it, MAX_LONGITUDE) }
        if (lat == null || lon == null || coords.size > MAX_GEO_PARTS) return ScanPayload.Unknown(text, "GEO")
        return ScanPayload.Geo(text, lat, lon, params.firstValue("q"))
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
