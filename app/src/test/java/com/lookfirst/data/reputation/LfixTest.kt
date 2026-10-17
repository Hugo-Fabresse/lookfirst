package com.lookfirst.data.reputation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LfixTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val pslSha = "ab".repeat(32)

    private fun write(name: String, keys: List<Long>, source: Int = 1): File =
        File(folder.root, name).also { it.writeBytes(BundleBuilder.indexOfKeys(source, keys, pslSha)) }

    @Test
    fun keyIsTheFirstEightBytesOfSha256OfKindAndName() {
        // SHA-256 of "h" + "a.com" computed independently: the layout of the key is part of the format.
        val digest = sha256("ha.com".toByteArray())
        var expected = 0L
        for (i in 0 until 8) expected = (expected shl 8) or (digest[i].toLong() and 0xFF)
        assertEquals(expected, Lfix.keyOf('h', "a.com"))
        assertTrue(Lfix.keyOf('h', "a.com") != Lfix.keyOf('d', "a.com"))
    }

    @Test
    fun validIndexIsReadBackAndSearchedAsUnsigned() {
        val keys = listOf(5L, 9L, Long.MAX_VALUE, Long.MIN_VALUE, -1L).sortedWith { a, b ->
            java.lang.Long.compareUnsigned(a, b)
        }
        val file = write("ok.idx", keys)
        val header = Lfix.validate(file)
        assertNotNull(header)
        assertEquals(5L, header!!.entryCount)
        assertEquals(pslSha, header.pslSha256)
        val index = MappedIndex.open(file)!!
        keys.forEach { assertTrue(index.contains(it)) }
        listOf(0L, 6L, 10L, Long.MAX_VALUE - 1, -2L).forEach { assertFalse(index.contains(it)) }
    }

    @Test
    fun emptyIndexIsValidAndContainsNothing() {
        val file = write("empty.idx", emptyList())
        assertEquals(0L, Lfix.validate(file)!!.entryCount)
        assertFalse(MappedIndex.open(file)!!.contains(0L))
    }

    @Test
    fun malformedFilesAreRefused() {
        val good = BundleBuilder.indexOfKeys(1, listOf(1L, 2L, 3L), pslSha)
        fun bytes(change: (ByteArray) -> ByteArray) =
            File(folder.root, "x${System.nanoTime()}").also { it.writeBytes(change(good)) }
        assertNull(Lfix.validate(bytes { it.copyOf(63) }))
        assertNull(Lfix.validate(bytes { it.copyOf(it.size - 1) }))
        assertNull(Lfix.validate(bytes { it + ByteArray(8) }))
        assertNull(Lfix.validate(bytes { it.copyOf().also { b -> b[3] = 'Y'.code.toByte() } }))
        assertNull(Lfix.validate(bytes { it.copyOf().also { b -> b[5] = 2 } }))
        assertNull(Lfix.validate(bytes { it.copyOf().also { b -> b[31] = 1 } }))
        assertNull("count above the size", Lfix.validate(bytes { it.copyOf().also { b -> b[11] = 9 } }))
        assertNull(MappedIndex.open(bytes { it.copyOf(10) }))
        assertNull(Lfix.validate(File(folder.root, "absent")))
    }

    @Test
    fun orderMustBeStrictlyIncreasing() {
        assertNull(Lfix.validate(write("dup.idx", listOf(1L, 1L))))
        assertNull(Lfix.validate(write("desc.idx", listOf(2L, 1L))))
        assertNull("-1 is the largest unsigned value", Lfix.validate(write("signed.idx", listOf(-1L, 1L))))
        assertNotNull(Lfix.validate(write("asc.idx", listOf(1L, -1L))))
    }

    @Test
    fun largeIndexValidatesAndIntersectsQuickly() {
        val keys = BundleBuilder.syntheticKeys(2_000_000, 11)
        val big = write("big.idx", keys)
        val other = write("other.idx", BundleBuilder.syntheticKeys(50_000, 12))
        val start = System.nanoTime()
        assertEquals(2_000_000L, Lfix.validate(big)!!.entryCount)
        assertFalse(Lfix.intersects(big, other))
        val shared = write("shared.idx", listOf(keys[1_234_567]))
        assertTrue(Lfix.intersects(big, shared))
        assertTrue("took too long", (System.nanoTime() - start) / 1_000_000 < 5_000)
        val index = MappedIndex.open(big)!!
        assertTrue(index.contains(keys[777_777]))
    }
}
