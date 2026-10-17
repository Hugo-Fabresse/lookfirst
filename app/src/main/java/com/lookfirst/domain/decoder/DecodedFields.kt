package com.lookfirst.domain.decoder

import com.lookfirst.domain.model.FieldHiddenChars
import com.lookfirst.domain.model.QueryParam
import com.lookfirst.domain.model.ScanPayload
import com.lookfirst.domain.model.Untrusted

/**
 * Percent-decoding and unescaping can reveal characters the raw QR text did not show (`%E2%80%AE`).
 * Every decoded field is already NFC; this lists the hidden characters left in each, by field name.
 */
internal object DecodedFields {
    fun hiddenChars(payload: ScanPayload): List<FieldHiddenChars> = fieldsOf(payload).mapNotNull { (name, value) ->
        val chars = ContentSanitizer.findHiddenChars(value.value)
        if (chars.isEmpty()) null else FieldHiddenChars(name, chars)
    }

    private fun fieldsOf(payload: ScanPayload): List<Pair<String, Untrusted>> = when (payload) {
        is ScanPayload.Wifi -> listOfNotNull("ssid" to payload.ssid, payload.password?.let { "password" to it })
        is ScanPayload.Sms -> listOfNotNull("number" to payload.number, payload.body?.let { "body" to it })
        is ScanPayload.Tel -> listOf("number" to payload.number)
        is ScanPayload.Mail -> mailFields(payload)
        is ScanPayload.Geo -> listOfNotNull(payload.query?.let { "q" to it })
        is ScanPayload.Otpauth -> otpauthFields(payload)
        is ScanPayload.Crypto -> cryptoFields(payload)
        else -> emptyList()
    }

    private fun mailFields(m: ScanPayload.Mail): List<Pair<String, Untrusted>> =
        m.to.map { "to" to it } + m.cc.map { "cc" to it } + m.bcc.map { "bcc" to it } +
            listOfNotNull(m.subject?.let { "subject" to it }, m.body?.let { "body" to it })

    private fun otpauthFields(o: ScanPayload.Otpauth): List<Pair<String, Untrusted>> = listOfNotNull(
        "account" to o.account,
        o.issuer?.let { "issuer" to it },
        o.labelIssuer?.let { "label" to it },
    ) + paramFields(o.params)

    private fun cryptoFields(c: ScanPayload.Crypto): List<Pair<String, Untrusted>> = listOfNotNull(
        "address" to c.address,
        c.label?.let { "label" to it },
        c.message?.let { "message" to it },
    ) + paramFields(c.params)

    private fun paramFields(params: List<QueryParam>): List<Pair<String, Untrusted>> =
        params.map { it.name to it.value }
}
