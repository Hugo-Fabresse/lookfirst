package com.lookfirst.domain.host

import com.lookfirst.domain.model.HostKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostNormalizerTest {
    private fun ok(raw: String): NormalizedHost {
        val result = HostNormalizer.normalize(raw)
        assertTrue("$raw -> $result", result is HostResult.Normalized)
        return (result as HostResult.Normalized).host
    }

    private fun error(raw: String): HostError {
        val result = HostNormalizer.normalize(raw)
        assertTrue("$raw -> $result", result is HostResult.Invalid)
        return (result as HostResult.Invalid).error
    }

    @Test
    fun plainHostIsLowercased() {
        val host = ok("WWW.Example.COM")
        assertEquals("www.example.com", host.ascii)
        assertEquals("www.example.com", host.unicode)
        assertEquals(HostKind.DOMAIN, host.kind)
        assertFalse(host.isInternational)
    }

    @Test
    fun fullwidthAndIdeographicDotAreMapped() {
        val host = ok("ｂｉｔ.ｌｙ。")
        assertEquals("bit.ly", host.ascii)
        assertTrue(host.hadTrailingDot)
        assertEquals("bit.ly", ok("bit.ly。").ascii)
        assertEquals("a.b.c", ok("a．b｡c").ascii)
    }

    @Test
    fun fullwidthDigitsBecomeAnIpv4() {
        val host = ok("１２７.０.０.１")
        assertEquals("127.0.0.1", host.ascii)
        assertEquals(HostKind.IPV4, host.kind)
        assertEquals(HostKind.IPV4_OBFUSCATED, ok("０ｘ７ｆ.1").kind)
        assertEquals(HostKind.IPV4_OBFUSCATED, ok("２１３０７０６４３３").kind)
    }

    @Test
    fun percentEncodingIsDecodedAsUtf8() {
        val host = ok("%70aypal.com")
        assertEquals("paypal.com", host.ascii)
        assertTrue(host.wasPercentEncoded)
        assertEquals("xn--caf-dma.example", ok("caf%C3%A9.example").ascii)
        assertEquals("café.example", ok("caf%C3%A9.example").unicode)
    }

    @Test
    fun badPercentEncodingIsRejected() {
        assertEquals(HostError.BAD_PERCENT_ENCODING, error("a%2.com"))
        assertEquals(HostError.BAD_PERCENT_ENCODING, error("a%zz.com"))
        assertEquals(HostError.BAD_PERCENT_ENCODING, error("a%C3.com"))
        assertEquals(HostError.BAD_PERCENT_ENCODING, error("a%ff.com"))
        assertEquals(HostError.FORBIDDEN_CHARACTER, error("a%2Fb.com"))
        assertEquals(HostError.FORBIDDEN_CHARACTER, error("a%00b.com"))
        assertEquals(HostError.FORBIDDEN_CHARACTER, error("a%25b.com"))
    }

    @Test
    fun cyrillicHomographGivesBothForms() {
        val host = ok("pаypal.com")
        assertEquals("xn--pypal-4ve.com", host.ascii)
        assertEquals("pаypal.com", host.unicode)
        assertTrue(host.isInternational)
    }

    @Test
    fun punycodeLabelIsDecodedAndCanonical() {
        val host = ok("XN--PYPAL-4VE.com")
        assertEquals("pаypal.com", host.unicode)
        assertEquals("xn--pypal-4ve.com", host.ascii)
        assertEquals(host.asciiLabels.size, host.unicodeLabels.size)
    }

    @Test
    fun invalidPunycodeIsAnError() {
        assertEquals(HostError.BAD_PUNYCODE, error("xn--.com"))
        assertTrue(HostNormalizer.normalize("xn--a.com") is HostResult.Invalid) // decodes to U+0080
        assertEquals(HostError.BAD_PUNYCODE, error("xn--ab-.com"))
        assertEquals(HostError.BAD_PUNYCODE, error("xn--" + "9".repeat(80) + ".com"))
        assertEquals(HostError.BAD_PUNYCODE, error("xn--é.com"))
    }

    @Test
    fun forbiddenCharactersAfterMappingAreRejected() {
        // U+FF20 (fullwidth @) maps to "@": must not smuggle a userinfo separator into the host.
        assertEquals(HostError.FORBIDDEN_CHARACTER, error("bit.ly＠evil.example"))
        assertEquals(HostError.FORBIDDEN_CHARACTER, error("a／b.com"))
        assertEquals(HostError.FORBIDDEN_CHARACTER, error("a b.com"))
        assertEquals(HostError.FORBIDDEN_CHARACTER, error("a\u0000b.com"))
        assertEquals(HostError.FORBIDDEN_CHARACTER, error("a�b.com"))
        assertEquals(HostError.FORBIDDEN_CHARACTER, error("a\uD800b.com"))
    }

    @Test
    fun emptyAndOversizedHostsAreRejected() {
        assertEquals(HostError.EMPTY, error(""))
        assertEquals(HostError.EMPTY_LABEL, error("."))
        assertEquals(HostError.EMPTY_LABEL, error("a..com"))
        assertEquals(HostError.EMPTY_LABEL, error(".com"))
        assertEquals(HostError.EMPTY_LABEL, error("a.com.."))
        assertEquals(HostError.LABEL_TOO_LONG, error("a".repeat(64) + ".com"))
        assertEquals(HostError.TOO_LONG, error(("b".repeat(60) + ".").repeat(5) + "com"))
        assertEquals(HostError.TOO_LONG, error("a".repeat(5000)))
    }

    @Test
    fun joinersAndBidiFollowTheStandard() {
        assertEquals(HostError.BAD_JOINER, error("a‍b.com"))
        assertEquals(HostError.BAD_BIDI, error("0א.com"))
        assertTrue(ok("אב.com").isInternational)
        assertEquals(HostError.BAD_LABEL, error("̀a.com"))
    }

    @Test
    fun hyphensAreNotJudgedButAceLookalikesAre() {
        assertEquals("ab--cd.com", ok("ab--cd.com").ascii)
        assertEquals("-a-.com", ok("-a-.com").ascii)
        assertEquals("a_b.example", ok("a_b.example").ascii)
    }

    @Test
    fun trailingDotIsStrippedAndRemembered() {
        val host = ok("example.com.")
        assertEquals("example.com", host.ascii)
        assertTrue(host.hadTrailingDot)
        assertFalse(ok("example.com").hadTrailingDot)
    }

    @Test
    fun hugeOrHostileInputsFinishQuickly() {
        val started = System.nanoTime()
        val inputs =
            listOf(
                "a".repeat(100_000),
                "xn--" + "a-".repeat(50_000),
                "%".repeat(100_000),
                "%C3".repeat(30_000),
                "‍".repeat(100_000),
                "̀".repeat(100_000),
                ("a.").repeat(50_000),
                "ａ".repeat(100_000),
            )
        for (input in inputs) assertTrue(HostNormalizer.normalize(input) is HostResult.Invalid)
        assertTrue("took ${(System.nanoTime() - started) / 1_000_000} ms", System.nanoTime() - started < 2_000_000_000L)
    }

    @Test
    fun worstCaseWithinTheBoundIsFast() {
        val started = System.nanoTime()
        val inputs =
            listOf(
                "\u0300".repeat(1000),
                "ß".repeat(1000),
                ("xn--" + "a".repeat(59) + ".").repeat(16),
                "\u05D0".repeat(500) + "." + "a".repeat(500),
                "%C3%A9".repeat(160),
            )
        for (input in inputs) HostNormalizer.normalize(input)
        assertTrue("took ${(System.nanoTime() - started) / 1_000_000} ms", System.nanoTime() - started < 2_000_000_000L)
    }

    @Test
    fun hostileInputsOfAllocatedSizeNeverThrow() {
        val pieces = listOf("%", "%C", "%C3%A", "xn--", "‮", "\uD800", "\uDC00", ".", "。", "-", "ß", "ς", "‌")
        var seed = 12345L
        repeat(3000) {
            val text = buildString {
                repeat(1 + (seed % 12).toInt()) {
                    seed = seed * 6364136223846793005L + 1442695040888963407L
                    append(pieces[((seed ushr 33) % pieces.size).toInt()])
                }
            }
            HostNormalizer.normalize(text) // must return, whatever the answer
        }
    }
}
