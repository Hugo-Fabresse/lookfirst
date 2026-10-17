package com.lookfirst.domain.decoder

import com.lookfirst.domain.model.ScanPayload

/** Payment URIs (BIP-21, EIP-681 and look-alikes). The address and every parameter are kept as written. */
internal object CryptoParser {
    val SCHEMES: Set<String> =
        setOf(
            "bitcoin", "bitcoincash", "litecoin", "dogecoin", "dash", "monero",
            "ethereum", "solana", "tron", "ripple", "stellar", "cardano",
        )

    fun parse(text: String, scheme: String, afterScheme: String): ScanPayload {
        val rest = afterScheme.removePrefix("//").removePrefix("pay-")
        val q = rest.indexOf('?')
        val path = PercentCodec.decode(if (q < 0) rest else rest.substring(0, q))
        val params = if (q < 0) emptyList() else PercentCodec.parseQuery(rest.substring(q + 1))
        val addressEnd = path.indexOfAny(charArrayOf('@', '/')).let { if (it < 0) path.length else it }
        val address = path.substring(0, addressEnd).trim()
        if (params == null || address.isEmpty()) return ScanPayload.Unknown(text, scheme.uppercase())
        return ScanPayload.Crypto(
            raw = text,
            coin = scheme,
            address = address,
            suffix = path.substring(addressEnd),
            amount = params.firstValue("amount") ?: params.firstValue("value") ?: params.firstValue("tx_amount"),
            label = params.firstValue("label"),
            message = params.firstValue("message"),
            params = params,
        )
    }
}
