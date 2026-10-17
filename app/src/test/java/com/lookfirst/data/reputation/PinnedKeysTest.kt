package com.lookfirst.data.reputation

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PinnedKeysTest {
    private val pinned = listOf(PinnedKeys.signingKeyS, PinnedKeys.recoveryKeyR)
    private val testPublicKeys = listOf(TestKeys.signing.spki, TestKeys.recovery.spki, TestKeys.other.spki)

    @Test
    fun pinnedKeysAreStrictP256NamedCurveKeys() {
        pinned.forEach { assertTrue(SignatureVerifier.isStrictP256Spki(it)) }
        assertNotEquals(SignatureVerifier.keyId(pinned[0]), SignatureVerifier.keyId(pinned[1]))
    }

    @Test
    fun epochZeroRingHoldsSAndR() {
        val ring = PinnedKeys.epochZeroRing()
        assertEquals(0, ring.epoch)
        assertEquals(2, ring.manifestKeys.size)
    }

    /**
     * The guard: while the pinned keys are the test keys (whose private halves are public in the test sources) the
     * flag must say so, and as soon as the flag says production, none of the test keys may still be pinned.
     * The Gradle task `verifyPinnedKeysAreProduction` refuses release builds unless the flag is true.
     */
    @Test
    fun testKeysAndTheProductionFlagAgree() {
        val usesTestKeys = pinned.any { key -> testPublicKeys.any { it.contentEquals(key) } }
        if (PinnedKeys.KEYS_ARE_PRODUCTION) {
            assertFalse("production flag set but a test key is still pinned", usesTestKeys)
        } else {
            assertTrue("test keys are pinned: this build is not releasable", usesTestKeys)
            assertArrayEquals(TestKeys.signing.spki, PinnedKeys.signingKeyS)
            assertArrayEquals(TestKeys.recovery.spki, PinnedKeys.recoveryKeyR)
        }
    }

    @Test
    fun releaseGuardTaskIsWiredToEveryReleaseBuild() {
        val build = File("build.gradle.kts").readText()
        assertTrue(build.contains("verifyPinnedKeysAreProduction"))
        assertTrue(build.contains("\"preReleaseBuild\""))
        assertTrue(build.contains("KEYS_ARE_PRODUCTION"))
    }

    @Test
    fun noPrivateKeyMaterialInMainSources() {
        val main = File("src/main")
        val offenders = main.walkTopDown().filter {
            it.isFile && it.extension in setOf("kt", "xml", "txt", "properties")
        }
            .filter { file ->
                val text = file.readText()
                text.contains("PRIVATE KEY") || text.contains("MIGHAgEAMBMGByqGSM49")
            }.map { it.path }.toList()
        assertEquals(emptyList<String>(), offenders)
    }
}
