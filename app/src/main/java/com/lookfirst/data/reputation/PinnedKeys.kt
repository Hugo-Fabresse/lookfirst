package com.lookfirst.data.reputation

/**
 * Public keys pinned in the APK (docs/design/reputation-bundle.md section 4): X.509 SubjectPublicKeyInfo, ECDSA
 * P-256, base64. Only public keys live here; private keys are never in the repository.
 *
 * ===== TEST KEYS =====
 * The two keys below were generated for LF-23 and their private halves are committed in the TEST sources
 * (app/src/test/.../TestKeys.kt). Anyone can sign a bundle with them. [KEYS_ARE_PRODUCTION] is false, and the
 * Gradle task `verifyPinnedKeysAreProduction` (wired into every release build) refuses to build a release while it
 * is false. LF-25 replaces the keys and flips the flag (docs/design/reputation-bundle.md, "Production keys").
 */
internal object PinnedKeys {
    /** False while the keys below are test keys. Read by the release guard in `app/build.gradle.kts`. */
    const val KEYS_ARE_PRODUCTION = false

    /** Signing key S (held by the lists pipeline as an environment secret). */
    private const val SIGNING_KEY_S =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEmFefeSrlfdO58xWg4paXhBxb" +
            "AGGsmMdyx4LNmbizOzJcjxuclJ3tqR+5mfmwFpsG3i5drBWuCWwevq/Ev/75Mg=="

    /** Offline recovery key R: signs keysets, can never be removed by a keyset. */
    private const val RECOVERY_KEY_R =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEwdB1M3IS5DWYjHHuZ8b8xcVR" +
            "yqOigGuWTbKx/Giugc10hDdBz0iIbjOkWKCqqenwEYCzpzEaDAbzFV604uwRGA=="

    const val FIRST_EPOCH = 0

    val signingKeyS: ByteArray get() = requireNotNull(StrictBase64.decode(SIGNING_KEY_S))

    val recoveryKeyR: ByteArray get() = requireNotNull(StrictBase64.decode(RECOVERY_KEY_R))

    /** The keys of epoch 0: what the embedded bundle is always verified with. */
    fun epochZeroRing(): KeyRing = KeyRing(FIRST_EPOCH, listOf(signingKeyS, recoveryKeyR))
}

/** Public keys (SPKI) allowed to sign manifests of one key epoch. */
internal class KeyRing(val epoch: Int, val manifestKeys: List<ByteArray>)
