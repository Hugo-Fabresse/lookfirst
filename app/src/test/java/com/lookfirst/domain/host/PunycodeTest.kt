package com.lookfirst.domain.host

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PunycodeTest {
    // RFC 3492 section 7.1 samples plus well-known IDN labels (cross-checked with Python's punycode codec).
    private val vectors =
        mapOf(
            "bücher" to "bcher-kva",
            "münchen" to "mnchen-3ya",
            "日本語" to "wgv71a119e",
            "3年B組金八先生" to "3B-ww4c5e180e575a65lsy2b",
            "Pročprostěnemluvíčesky" to "Proprostnemluvesky-uyb24dma41a",
            "他们为什么不说中文" to "ihqwcrb4cv8a8dqg056pqjye",
            "他們爲什麽不說中文" to "ihqwctvzc91f659drss3x8bo0yb",
            "ليهمابتكلموشعربي؟" to "egbpdaj6bu4bxfgehfvwxn",
            "למההםפשוטלאמדבריםעברית" to "4dbcagdahymbxekheh6e0a7fei0b",
            "pаypal" to "pypal-4ve",
        )

    private fun cps(text: String) = codePointsOf(text)

    @Test
    fun encodesTheReferenceVectors() {
        for ((plain, encoded) in vectors) assertEquals(plain, encoded, Punycode.encode(cps(plain)))
    }

    @Test
    fun decodesTheReferenceVectors() {
        for ((plain, encoded) in vectors) assertArrayEquals(plain, cps(plain), Punycode.decode(encoded))
    }

    @Test
    fun decodingAcceptsUpperCaseDigits() {
        assertNotNull(Punycode.decode("BCHER-KVA"))
    }

    @Test
    fun malformedInputIsRejectedNotThrown() {
        assertNull(Punycode.decode("aé-b"))
        assertNull(Punycode.decode("abc-*"))
        assertNull(Punycode.decode("zzzzzzzzzzzzzzzzzzzzzzzz"))
        assertNull(Punycode.decode("a".repeat(PUNYCODE_MAX_OUTPUT + 1)))
        assertNull(Punycode.encode(IntArray(PUNYCODE_MAX_OUTPUT + 1) { 0x4E00 }))
    }

    @Test
    fun decodedCodePointsAreAlwaysValidScalarValues() {
        // Whatever the digits say, the output is never a surrogate or beyond U+10FFFF.
        for (i in 0 until 2000) {
            val out = Punycode.decode("a-" + i.toString(36) + "9z".repeat(i % 5)) ?: continue
            assertTrue(out.all { it in 0..0x10FFFF && it !in 0xD800..0xDFFF })
        }
    }
}
