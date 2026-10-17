package com.lookfirst.domain.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/** LF-20: SHA256SUMS lists the SHA-256 of every embedded host data file, and the files match it. */
class HostDataIntegrityTest {
    private val sha256Pattern = Regex("[0-9a-f]{64}")

    private val expectedFiles = setOf(
        "bidi-class.txt",
        "confusables.txt",
        "idna-mapping.txt",
        "joining-type.txt",
        "marks.txt",
        "public-suffix-list.txt",
        "scripts.txt",
        "virama.txt",
    )

    private fun resource(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/lookfirst/host/$name")) { name }.use { it.readBytes() }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun sums(): List<Pair<String, String>> =
        resource("SHA256SUMS").decodeToString().lines().filter { it.isNotBlank() }
            .map { it.substringAfter("  ") to it.substringBefore("  ") }

    @Test
    fun sumsListEveryDataFileExactlyOnce() {
        val names = sums().map { it.first }
        assertEquals(expectedFiles, names.toSet())
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun everyDataFileMatchesItsSum() {
        sums().forEach { (name, sum) ->
            assertTrue("$name sum is not a SHA-256", sha256Pattern.matches(sum))
            assertEquals("$name differs from SHA256SUMS", sum, sha256(resource(name)))
        }
    }

    @Test
    fun everyDataFileNamesItsSource() {
        expectedFiles.forEach { name ->
            val header = resource(name).decodeToString().lines().take(6).filter { it.startsWith("# Source: https://") }
            assertTrue("$name has no source header", header.isNotEmpty())
            header.forEach { assertTrue(it, Regex("SHA-256 [0-9a-f]{64}$").containsMatchIn(it)) }
        }
    }
}
