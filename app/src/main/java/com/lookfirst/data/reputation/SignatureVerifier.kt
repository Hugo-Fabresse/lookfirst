package com.lookfirst.data.reputation

import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

private const val MAX_SIGNATURE_FILE_BYTES = 256
private const val MAX_DER_SIGNATURE_BYTES = 72
private const val DER_SEQUENCE = 0x30
private const val KEY_ID_HEX_LENGTH = 8
private const val SIGNATURE_LINE_FIELDS = 3
private const val SPKI_LENGTH = 91
private const val UNCOMPRESSED_POINT = 0x04

/** What a signature is for. The prefix is signed with the data, so a signature never crosses purposes. */
internal enum class SignatureContext(val tag: String, val prefix: ByteArray) {
    MANIFEST("lookfirst-sig-v1", "LookFirst bundle manifest v1\n".toByteArray(Charsets.US_ASCII)),
    KEYSET("lookfirst-sig-v1", "LookFirst keyset v1\n".toByteArray(Charsets.US_ASCII)),
}

/**
 * ECDSA P-256 / SHA-256 verification with `java.security` only (ADR-0007). The signature file is one ASCII line,
 * `lookfirst-sig-v1 <keyId> <base64 DER>`; the signed message is the context prefix followed by the exact bytes.
 */
internal object SignatureVerifier {
    /** X.509 SubjectPublicKeyInfo header for id-ecPublicKey + secp256r1 (named curve) + BIT STRING of 65 bytes. */
    private val spkiPrefix = Hex.decodeLower("3059301306072a8648ce3d020106082a8648ce3d030107034200")!!

    /**
     * True only for a 91-byte SPKI made of the fixed named-curve header and an uncompressed point. Anything else
     * (explicit curve parameters, other curves, compressed points, trailing bytes) is refused by construction.
     */
    fun isStrictP256Spki(spki: ByteArray): Boolean = spki.size == SPKI_LENGTH &&
        spki.copyOfRange(0, spkiPrefix.size).contentEquals(spkiPrefix) &&
        spki[spkiPrefix.size].toInt() == UNCOMPRESSED_POINT

    fun keyId(spki: ByteArray): String = Hex.encode(sha256(spki)).substring(0, KEY_ID_HEX_LENGTH)

    fun verify(
        context: SignatureContext,
        message: ByteArray,
        signatureFile: ByteArray,
        keys: List<ByteArray>,
    ): Boolean {
        val line = parseLine(context, signatureFile) ?: return false
        // keyId only selects candidates; each candidate is a full strict SPKI from our own list.
        val candidates = keys.filter { isStrictP256Spki(it) && keyId(it) == line.keyId }
        return candidates.any { verifyWith(it, context, message, line.der) }
    }

    private class Line(val keyId: String, val der: ByteArray)

    @Suppress("ReturnCount") // a flat list of independent shape checks
    private fun parseLine(context: SignatureContext, file: ByteArray): Line? {
        if (file.isEmpty() || file.size > MAX_SIGNATURE_FILE_BYTES || file.any { it < 0 }) return null
        val text = String(file, Charsets.US_ASCII).removeSuffix("\n")
        val fields = text.split(' ')
        if (fields.size != SIGNATURE_LINE_FIELDS || fields[0] != context.tag) return null
        if (!Hex.isLowerHex(fields[1], KEY_ID_HEX_LENGTH)) return null
        val der = StrictBase64.decode(fields[2]) ?: return null
        val shapeOk = der.size <= MAX_DER_SIGNATURE_BYTES && der.isNotEmpty() && der[0].toInt() == DER_SEQUENCE
        return if (shapeOk) Line(fields[1], der) else null
    }

    private fun verifyWith(spki: ByteArray, context: SignatureContext, message: ByteArray, der: ByteArray): Boolean =
        try {
            val key: PublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki))
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(key)
                update(context.prefix)
                update(message)
                verify(der)
            }
        } catch (@Suppress("SwallowedException") ignored: GeneralSecurityException) {
            false
        }
}
