package com.lookfirst.domain.decoder

import com.lookfirst.domain.model.HiddenCharKind
import com.lookfirst.domain.model.ParseResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentSanitizerTest {
    @Test
    fun oneMegabyteIsTooLargeAndRejectedWithoutParsing() {
        val start = System.nanoTime()
        val result = PayloadParser.parse("a".repeat(1_000_000))
        assertEquals(ParseResult.TooLarge(1_000_000, ContentSanitizer.MAX_CHARS), result)
        assertTrue((System.nanoTime() - start) / 1_000_000 < 500)
    }

    @Test
    fun exactlyMaxCharsIsAccepted() {
        assertTrue(PayloadParser.parse("a".repeat(ContentSanitizer.MAX_CHARS)) is ParseResult.Ok)
        assertTrue(PayloadParser.parse("a".repeat(ContentSanitizer.MAX_CHARS + 1)) is ParseResult.TooLarge)
    }

    @Test
    fun emptyAndWhitespaceOnlyAreEmpty() {
        assertEquals(ParseResult.Empty, PayloadParser.parse(""))
        assertEquals(ParseResult.Empty, PayloadParser.parse(" \t\r\n "))
    }

    @Test
    fun invisibleOnlyContentIsNotEmpty() {
        val ok = parseOk("​")
        assertEquals(listOf(HiddenCharKind.ZERO_WIDTH), ok.hiddenChars.map { it.kind })
    }

    @Test
    fun contentIsNormalizedToNfcAndRawKeepsTheNormalizedText() {
        val decomposed = "café"
        assertEquals("café", parseOk(decomposed).payload.raw.value)
    }

    @Test
    fun bidiOverrideIsReportedWithPositionAndNotRemoved() {
        val ok = parseOk("https://pay‮pal.com")
        val hidden = ok.hiddenChars.single()
        assertEquals(HiddenCharKind.BIDI, hidden.kind)
        assertEquals(0x202E, hidden.codePoint)
        assertEquals(11, hidden.index)
        assertTrue(ok.payload.raw.value.contains('‮'))
    }

    @Test
    fun zeroWidthControlSeparatorAndSpaceAreClassified() {
        val kinds =
            parseOk("a​b\u0000c d ef\uD800g").hiddenChars.map { it.kind }
        assertEquals(
            listOf(
                HiddenCharKind.ZERO_WIDTH,
                HiddenCharKind.CONTROL,
                HiddenCharKind.LINE_SEPARATOR,
                HiddenCharKind.UNUSUAL_SPACE,
                HiddenCharKind.PRIVATE_USE,
                HiddenCharKind.INVALID_SURROGATE,
            ),
            kinds,
        )
    }

    @Test
    fun allBidiControlsAreDetected() {
        val controls =
            listOf(0x061C, 0x200E, 0x200F, 0x202A, 0x202B, 0x202C, 0x202D, 0x202E, 0x2066, 0x2067, 0x2068, 0x2069)
        controls.forEach { cp ->
            val kinds = parseOk("a${String(Character.toChars(cp))}b").hiddenChars.map { it.kind }
            assertEquals("U+${cp.toString(16)}", listOf(HiddenCharKind.BIDI), kinds)
        }
    }

    @Test
    fun invisibleFillersAndVariationSelectorsAreReported() {
        listOf(0x034F, 0xFE00, 0xFE0F, 0x115F, 0x1160, 0x3164, 0xFFA0, 0x2800, 0x00AD, 0xE0041).forEach { cp ->
            val kinds = parseOk("a${String(Character.toChars(cp))}b").hiddenChars.map { it.kind }
            assertEquals("U+${cp.toString(16)}", 1, kinds.size)
        }
        val kind = parseOk("a\u3164b").hiddenChars.single().kind
        assertEquals(HiddenCharKind.INVISIBLE, kind)
    }

    @Test
    fun ordinaryTextHasNoHiddenChars() {
        assertTrue(parseOk("Hello world").hiddenChars.isEmpty())
    }
}
