package com.lookfirst.domain.decoder

import com.lookfirst.domain.model.ScanPayload
import com.lookfirst.domain.model.Untrusted

/**
 * Payment URIs (BIP-21, EIP-681 and look-alikes). The address and every parameter are kept as written.
 * Ambiguous or "must understand" content is refused rather than guessed: a repeated `amount`, `value`, `address`
 * or `uint256`, and any `req-*` parameter (BIP-21 says a wallet that does not know it must reject the URI).
 */
internal object CryptoParser {
    val SCHEMES: Set<String> =
        setOf(
            "bitcoin", "bitcoincash", "litecoin", "dogecoin", "dash", "monero",
            "ethereum", "solana", "tron", "ripple", "stellar", "cardano",
        )

    private val SINGLE_PARAMS = setOf("amount", "value", "address", "uint256")

    fun parse(text: String, scheme: String, afterScheme: String): ScanPayload {
        val unknown = ScanPayload.Unknown(Untrusted(text), scheme.uppercase())
        val rest = afterScheme.removePrefix("//").removePrefix("pay-")
        val q = rest.indexOf('?')
        val path = PercentCodec.decode(if (q < 0) rest else rest.substring(0, q))
        val params = if (q < 0) emptyList() else PercentCodec.parseQuery(rest.substring(q + 1))
        val addressEnd = path.indexOfAny(charArrayOf('@', '/')).let { if (it < 0) path.length else it }
        val address = path.substring(0, addressEnd).trim()
        if (params == null || address.isEmpty()) return unknown
        if (params.hasDuplicate(SINGLE_PARAMS) || params.any { it.name.startsWith("req-") }) return unknown
        val suffix = path.substring(addressEnd)
        val function = suffix.substringAfter('/', "").substringBefore('/').ifEmpty { null }
        val isTransfer = function == "transfer"
        return ScanPayload.Crypto(
            raw = Untrusted(text),
            coin = scheme,
            address = Untrusted(address),
            suffix = Untrusted(suffix),
            function = function?.let { Untrusted(it) },
            recipient = if (isTransfer) params.firstValue("address") else null,
            amount = params.firstValue("amount") ?: params.firstValue("tx_amount"),
            value = params.firstValue("value"),
            tokenAmount = if (isTransfer) params.firstValue("uint256") else null,
            label = params.firstValue("label"),
            message = params.firstValue("message"),
            params = params,
        )
    }
}
