package com.lookfirst.data.reputation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class SignatureVerifierTest {
    private val message = "{\"hello\":\"world\"}".toByteArray()
    private val keys = listOf(TestKeys.signing.spki, TestKeys.recovery.spki)

    private fun line(
        signer: TestSigner,
        context: SignatureContext = SignatureContext.MANIFEST,
        data: ByteArray = message,
    ) = signer.signLine(context, data).toByteArray()

    private fun verify(file: ByteArray, data: ByteArray = message, ring: List<ByteArray> = keys) =
        SignatureVerifier.verify(SignatureContext.MANIFEST, data, file, ring)

    @Test
    fun acceptsSigningKeyAndRecoveryKey() {
        assertTrue(verify(line(TestKeys.signing)))
        assertTrue(verify(line(TestKeys.recovery)))
    }

    @Test
    fun rejectsUnknownKey() {
        assertFalse(verify(line(TestKeys.other)))
    }

    @Test
    fun rejectsSignatureLabelledWithAnotherKeyId() {
        val forged = String(line(TestKeys.other)).replace(TestKeys.other.keyId, TestKeys.signing.keyId)
        assertFalse(verify(forged.toByteArray()))
    }

    @Test
    fun rejectsOneByteChange() {
        val altered = message.copyOf().also { it[3] = (it[3] + 1).toByte() }
        assertFalse(verify(line(TestKeys.signing), data = altered))
    }

    @Test
    fun domainSeparationKeysetSignatureIsNotAManifestSignature() {
        val keysetSignature = line(TestKeys.recovery, SignatureContext.KEYSET)
        assertFalse(verify(keysetSignature))
        assertTrue(SignatureVerifier.verify(SignatureContext.KEYSET, message, keysetSignature, keys))
    }

    @Test
    fun rejectsMalformedSignatureLines() {
        val good = String(line(TestKeys.signing)).trimEnd('\n')
        val parts = good.split(' ')
        val malformed = listOf(
            "",
            good + " extra",
            parts[0] + " " + parts[1],
            "lookfirst-sig-v2 ${parts[1]} ${parts[2]}",
            "${parts[0]} ${parts[1].uppercase()} ${parts[2]}",
            "${parts[0]} ${parts[1].take(6)} ${parts[2]}",
            "${parts[0]} ${parts[1]} ${parts[2].dropLast(1)}",
            "${parts[0]} ${parts[1]} ${parts[2]}\n\n",
            "${parts[0]} ${parts[1]} " + "A".repeat(400),
            "${parts[0]}  ${parts[1]} ${parts[2]}",
            "${parts[0]} ${parts[1]} not*base64!",
        )
        malformed.forEach { assertFalse("accepted: $it", verify(it.toByteArray())) }
        assertFalse(verify(ByteArray(300) { 'a'.code.toByte() }))
        assertFalse(verify("${parts[0]} ${parts[1]} ${parts[2]}é".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun rejectsNonDerSignatureBody() {
        val tag = SignatureContext.MANIFEST.tag
        val raw = Base64.getEncoder().encodeToString(ByteArray(64) { 1 })
        assertFalse(verify("$tag ${TestKeys.signing.keyId} $raw\n".toByteArray()))
    }

    @Test
    fun rejectsSignatureLongerThanTheCap() {
        val tag = SignatureContext.MANIFEST.tag
        val long = Base64.getEncoder().encodeToString(byteArrayOf(0x30) + ByteArray(100))
        assertFalse(verify("$tag ${TestKeys.signing.keyId} $long\n".toByteArray()))
    }

    @Test
    fun explicitCurveParametersAreRefused() {
        val explicit = Base64.getDecoder().decode(TestKeys.EXPLICIT_PARAMS_SPKI)
        assertFalse(SignatureVerifier.isStrictP256Spki(explicit))
        val file = "${SignatureContext.MANIFEST.tag} ${SignatureVerifier.keyId(explicit)} " +
            Base64.getEncoder().encodeToString(byteArrayOf(0x30, 0x02, 0x01, 0x01)) + "\n"
        assertFalse(verify(file.toByteArray(), ring = keys + listOf(explicit)))
    }

    @Test
    fun otherCurvesAndTruncatedKeysAreRefused() {
        assertFalse(SignatureVerifier.isStrictP256Spki(Base64.getDecoder().decode(TestKeys.P384_SPKI)))
        assertFalse(SignatureVerifier.isStrictP256Spki(TestKeys.signing.spki.copyOf(90)))
        assertFalse(SignatureVerifier.isStrictP256Spki(TestKeys.signing.spki + byteArrayOf(0)))
        val compressed = TestKeys.signing.spki.copyOf().also { it[26] = 0x02 }
        assertFalse(SignatureVerifier.isStrictP256Spki(compressed))
        assertTrue(SignatureVerifier.isStrictP256Spki(TestKeys.signing.spki))
    }

    @Test
    fun keysetVerifierOnlyAcceptsRecoveryKeyAndNewerEpochs() {
        val json = keysetJson(1, TestKeys.other)
        val sigR = line(TestKeys.recovery, SignatureContext.KEYSET, json)
        val sigS = line(TestKeys.signing, SignatureContext.KEYSET, json)
        val keyset = KeysetVerifier.verify(json, sigR, TestKeys.recovery.spki, 0)
        assertEquals(1, keyset?.epoch)
        assertTrue(keyset!!.keys.single().contentEquals(TestKeys.other.spki))
        assertNull("signed by S", KeysetVerifier.verify(json, sigS, TestKeys.recovery.spki, 0))
        assertNull("same epoch", KeysetVerifier.verify(json, sigR, TestKeys.recovery.spki, 1))
        val explicitJson = keysetJson(1, null, TestKeys.EXPLICIT_PARAMS_SPKI)
        val explicitSig = line(TestKeys.recovery, SignatureContext.KEYSET, explicitJson)
        assertNull("explicit parameters", KeysetVerifier.verify(explicitJson, explicitSig, TestKeys.recovery.spki, 0))
        val entry = """{"spki":"${TestKeys.other.spkiBase64}"}"""
        val duplicate = """{"epoch":1,"formatVersion":1,"keys":[$entry,$entry]}""".toByteArray()
        assertNull(
            "duplicate keys",
            KeysetVerifier.verify(
                duplicate,
                line(TestKeys.recovery, SignatureContext.KEYSET, duplicate),
                TestKeys.recovery.spki,
                0,
            ),
        )
    }

    private fun keysetJson(epoch: Int, signer: TestSigner?, rawSpki: String? = null): ByteArray {
        val spki = rawSpki ?: signer!!.spkiBase64
        return """{"epoch":$epoch,"formatVersion":1,"keys":[{"spki":"$spki"}]}""".toByteArray()
    }
}
