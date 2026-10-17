package com.lookfirst.ui.screens.result

import com.lookfirst.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InvisibleCharsTest {
    @Test
    fun plainTextIsOneSegment() {
        val url = "https://example.com"
        assertEquals(listOf<ExactSegment>(ExactSegment.Plain(url)), segmentExact(url))
    }

    @Test
    fun bidiOverrideAndLineFeedBecomeVisibleTokens() {
        val segments = segmentExact("a\u202Eb\nc")
        assertEquals(
            listOf(
                ExactSegment.Plain("a"),
                ExactSegment.Hidden("<U+202E>", "U+202E", R.string.a11y_bidi_override),
                ExactSegment.Plain("b"),
                ExactSegment.Hidden("<LF>", "LF", R.string.a11y_line_break),
                ExactSegment.Plain("c"),
            ),
            segments,
        )
    }

    @Test
    fun zeroWidthNullAndOddSpacesAreNamedToo() {
        val segments = segmentExact("\u200B\u0000\u00A0")
        assertEquals(
            listOf(
                ExactSegment.Hidden("<U+200B>", "U+200B", R.string.a11y_zero_width),
                ExactSegment.Hidden("<U+0000>", "U+0000", R.string.a11y_null_char),
                ExactSegment.Hidden("<U+00A0>", "U+00A0", R.string.a11y_invisible_other),
            ),
            segments,
        )
    }

    @Test
    fun noRawInvisibleCharacterSurvivesInTheSegments() {
        val raw = "x\u202E\u2066\u200D\u0007y"
        val drawn = segmentExact(raw).filterIsInstance<ExactSegment.Plain>().joinToString("") { it.text }
        assertEquals("xy", drawn)
    }

    @Test
    fun displayIsCappedAtTwoThousandCharacters() {
        val (shown, left) = capForDisplay("a".repeat(2500))
        assertEquals(MAX_DISPLAY_CHARS, shown.length)
        assertEquals(500, left)
        assertEquals(0, capForDisplay("a".repeat(2000)).second)
    }

    @Test
    fun theCapNeverCutsASurrogatePair() {
        val text = "a".repeat(MAX_DISPLAY_CHARS - 1) + "😀" + "tail"
        val (shown, left) = capForDisplay(text)
        assertTrue(shown.length < MAX_DISPLAY_CHARS)
        assertTrue(!Character.isHighSurrogate(shown.last()))
        assertEquals(text.length - shown.length, left)
    }
}
