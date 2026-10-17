package com.lookfirst.data.reputation

private const val KEYSET_MAX_BYTES = 8 * 1024L
private const val KEYSET_SIGNATURE_MAX_BYTES = 256
private const val MAX_KEYSET_KEYS = 8
private const val MAX_KEYSET_EPOCH = 1_000_000L

/** Keys allowed to sign manifests from [epoch] on. Never signs keysets (only R does). */
internal class Keyset(val epoch: Int, val keys: List<ByteArray>)

/**
 * `keyset.json` + `keyset.sig` (design document, section 4): signed by the offline key R only, never by a key a
 * keyset delegates. Format: `{"formatVersion":1,"epoch":N,"keys":[{"spki":"<base64 SPKI>"}...]}`. R is always kept
 * by the caller: a keyset cannot remove it.
 */
internal object KeysetVerifier {
    /** Null unless [signature] is R's over [json], the shape is exact and [epoch] is above [currentEpoch]. */
    fun verify(json: ByteArray, signature: ByteArray, recoveryKey: ByteArray, currentEpoch: Int): Keyset? {
        val sized = json.size <= KEYSET_MAX_BYTES && signature.size <= KEYSET_SIGNATURE_MAX_BYTES
        if (!sized || !SignatureVerifier.verify(SignatureContext.KEYSET, json, signature, listOf(recoveryKey))) {
            return null
        }
        val root = StrictJson.parse(json) as? Json.Obj ?: return null
        val format = (root.fields["formatVersion"] as? Json.Num)?.value
        val epoch = (root.fields["epoch"] as? Json.Num)?.value
        val keys = parseKeys(root.fields["keys"])
        if (format != 1L || epoch == null || keys == null) return null
        return if (epoch in (currentEpoch + 1L)..MAX_KEYSET_EPOCH) Keyset(epoch.toInt(), keys) else null
    }

    @Suppress("ReturnCount") // a flat list of independent shape checks
    private fun parseKeys(node: Json?): List<ByteArray>? {
        val items = (node as? Json.Arr)?.items ?: return null
        if (items.isEmpty() || items.size > MAX_KEYSET_KEYS) return null
        val keys = items.map { item ->
            val text = ((item as? Json.Obj)?.fields?.get("spki") as? Json.Str)?.value ?: return null
            val spki = StrictBase64.decode(text) ?: return null
            if (!SignatureVerifier.isStrictP256Spki(spki)) return null
            spki
        }
        return if (keys.map { Hex.encode(it) }.toSet().size == keys.size) keys else null
    }
}
