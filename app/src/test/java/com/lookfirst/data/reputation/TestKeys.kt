package com.lookfirst.data.reputation

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * TEST KEYS ONLY. The private halves of the keys pinned in `PinnedKeys.kt` while `KEYS_ARE_PRODUCTION` is false
 * live here, in test sources, and nowhere else. Production keys (LF-25) are never generated or stored in this
 * repository. [other] is a key that is pinned nowhere (an attacker's key).
 */
internal class TestSigner(private val pkcs8Base64: String, val spkiBase64: String) {
    val spki: ByteArray get() = Base64.getDecoder().decode(spkiBase64)
    val keyId: String get() = SignatureVerifier.keyId(spki)

    fun signLine(context: SignatureContext, message: ByteArray): String {
        val pkcs8 = Base64.getDecoder().decode(pkcs8Base64)
        val key = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
        val der = Signature.getInstance("SHA256withECDSA").run {
            initSign(key)
            update(context.prefix)
            update(message)
            sign()
        }
        return "${context.tag} $keyId ${Base64.getEncoder().encodeToString(der)}\n"
    }
}

internal object TestKeys {
    val signing = TestSigner(
        "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQg2wULtHEFDgtLMQFbq09MwYbIFX4RHsk1+eseeYCh5qqhRANCAASYV595" +
            "KuV907nzFaDilpeEHFsAYayYx3LHgs2ZuLM7MlyPG5yUne2pH7mZ+bAWmwbeLl2sFa4JbB6+r8S//vky",
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEmFefeSrlfdO58xWg4paXhBxbAGGsmMdyx4LNmbizOzJc" +
            "jxuclJ3tqR+5mfmwFpsG3i5drBWuCWwevq/Ev/75Mg==",
    )

    val recovery = TestSigner(
        "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgR6EEydREs1ZT38nOv7XjvCqz7DDizeXHA59GSAvLflGhRANCAATB0HUz" +
            "chLkNZiMce5nxvzFxVHKo6KAa5ZNsrH8aK6BzXSEN0HPSIhuM6RYoKqp6fARgLOnMRoMBvMVXrTi7BEY",
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEwdB1M3IS5DWYjHHuZ8b8xcVRyqOigGuWTbKx/Giugc10" +
            "hDdBz0iIbjOkWKCqqenwEYCzpzEaDAbzFV604uwRGA==",
    )

    /** Not pinned anywhere. */
    val other = TestSigner(
        "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgrwg3eBWjkmR0sRP50VIFTJ/LZwwtJNl1sceglKTSiByhRANCAAR6N2Jg" +
            "r4Yjl+NH4rRcl6D87jULbdNOltDNkRGYQ+PhHrJbtTieHbhj9UirA+LAB39IELGBNj1MDwcGPtkpMFZ7",
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEejdiYK+GI5fjR+K0XJeg/O41C23TTpbQzZERmEPj4R6y" +
            "W7U4nh24Y/VIqwPiwAd/SBCxgTY9TA8HBj7ZKTBWew==",
    )

    /** SPKI with explicit curve parameters (the CurveBall shape), generated with openssl. */
    const val EXPLICIT_PARAMS_SPKI =
        "MIIBSzCCAQMGByqGSM49AgEwgfcCAQEwLAYHKoZIzj0BAQIhAP////8AAAABAAAAAAAAAAAAAAAA////" +
            "////////////MFsEIP////8AAAABAAAAAAAAAAAAAAAA///////////////8BCBaxjXYqjqT57PrvVV2" +
            "mIa8ZR0GsMxTsPY7zjw+J9JgSwMVAMSdNgiG5wSTamZ44ROdJreBn36QBEEEaxfR8uEsQkf4vOblY6RA" +
            "8ncDfYEt6zOg9KE5RdiYwpZP40Li/hp/m47n60p8D54WK84zV2sxXs7LtkBoN79R9QIhAP////8AAAAA" +
            "//////////+85vqtpxeehPO5ysL8YyVRAgEBA0IABP8RtxFZ62DzA3mXIeY5IeBoIAs5Zutp2J8S0j/E" +
            "xE+nIx7lCK+W3wru6iprCARAGJttsHsIr1jmt4qLKYSE+aQ="

    /** secp384r1 key, named curve but not P-256. */
    const val P384_SPKI =
        "MHYwEAYHKoZIzj0CAQYFK4EEACIDYgAE6nI3Z+gictViFT84nrD4uxC1hCa4MF+wXWiUDFf4Dxqj2IUMODtcm/n3xJ3LDiFOy1FmAzGQitS/" +
            "bRGI1RXsn8/IOA+iH1/+sAB14LsQABKyUsAO25NQiJ/1qqvwS5NZ"
}
