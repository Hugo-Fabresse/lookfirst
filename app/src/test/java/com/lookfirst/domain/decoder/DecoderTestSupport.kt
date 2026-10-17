package com.lookfirst.domain.decoder

import com.lookfirst.domain.model.ParseResult
import com.lookfirst.domain.model.ScanPayload

internal fun parseOk(raw: String): ParseResult.Ok = PayloadParser.parse(raw) as ParseResult.Ok

internal inline fun <reified T : ScanPayload> decode(raw: String): T {
    val payload = parseOk(raw).payload
    check(payload is T) { "expected ${T::class.simpleName} but got ${payload::class.simpleName}" }
    return payload
}
