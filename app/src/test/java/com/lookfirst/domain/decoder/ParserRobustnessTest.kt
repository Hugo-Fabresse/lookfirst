package com.lookfirst.domain.decoder

import com.lookfirst.domain.model.ParseResult
import com.lookfirst.domain.model.ScanPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ParserRobustnessTest {
    private val max = ContentSanitizer.MAX_CHARS

    private fun assertFast(label: String, raw: String) {
        PayloadParser.parse(raw) // warm-up: class loading and JIT must not count
        val start = System.nanoTime()
        PayloadParser.parse(raw)
        val millis = (System.nanoTime() - start) / 1_000_000
        assertTrue("$label took ${millis}ms", millis < 500)
    }

    @Test
    fun pathologicalMaxSizeInputsAreParsedQuickly() {
        val pathological =
            mapOf(
                "a-repeat" to "a".repeat(max),
                "slashes" to "https://" + "/".repeat(max - 8),
                "ats" to "https://" + "@".repeat(max - 8),
                "colons" to "https://" + ":".repeat(max - 8),
                "dots" to "http://" + ".".repeat(max - 7),
                "numeric" to "http://" + "1.".repeat((max - 7) / 2),
                "wifi-escapes" to "WIFI:S:" + "\\".repeat(max - 7),
                "wifi-semicolons" to "WIFI:" + "S:a;".repeat((max - 5) / 4),
                "wifi-keys" to "WIFI:" + "K".repeat(max - 5),
                "query-amps" to "mailto:a@b?" + "&".repeat(max - 11),
                "query-pairs" to "sms:1?" + "a=b&".repeat((max - 6) / 4),
                "percent" to "tel:" + "%".repeat(max - 4),
                "percent-valid" to "tel:" + "%41".repeat((max - 4) / 3),
                "percent-utf8" to "tel:" + "%F0%9F".repeat((max - 4) / 6),
                "otp" to "otpauth://totp/" + ":".repeat(max - 15),
                "epc-lines" to "BCD\n" + "\n".repeat(max - 4),
                "scheme-chars" to "a".repeat(40) + ":" + "b".repeat(max - 41),
                "nfc-expand" to "\u0344".repeat(max / 2),
                "bidi" to "\u202E".repeat(max),
                "surrogates" to "\uD800".repeat(max),
            )
        pathological.forEach { (label, raw) -> assertFast(label, raw) }
    }

    @Test
    fun hugeInputIsRejectedImmediately() {
        assertFast("1MB", "https://" + "a".repeat(1_048_576))
        assertEquals(ParseResult.TooLarge(1_048_584, max), PayloadParser.parse("https://" + "a".repeat(1_048_576)))
    }

    @Test
    fun nfcExpansionBeyondTheLimitIsRejected() {
        // U+0344 normalizes to two chars: 3000 of them exceed the limit only after NFC.
        assertTrue(PayloadParser.parse("\u0344".repeat(3000)) is ParseResult.TooLarge)
    }

    @Test
    fun randomHostileInputsNeverThrow() {
        val tokens =
            listOf(
                "http", "https", "://", ":", "//", "/", "\\", "@", "?", "#", "&", "=", ";", ",", "%", "%41", "%zz",
                "[", "]", "WIFI:", "S:", "P:", "T:", "H:", "sms:", "smsto:", "tel:", "mailto:", "MATMSG:", "geo:",
                "otpauth://", "totp/", "bitcoin:", "ethereum:", "BCD\n", "001", "SCT", "\n", "\r\n", "\u202E",
                "\u200B", "\u0000", "\uD800", "é", "0x", "::", ".", "-", "1", "99999", "javascript:", "intent://",
                "#Intent;end", "secret=", "amount=", "EUR", "IBAN", " ",
            )
        val random = Random(42)
        repeat(20_000) {
            val raw = buildString { repeat(random.nextInt(1, 14)) { append(tokens[random.nextInt(tokens.size)]) } }
            val result = PayloadParser.parse(raw)
            assertTrue(raw, result is ParseResult.Ok || result is ParseResult.Empty)
        }
    }

    @Test
    fun everyOkPayloadKeepsTheSanitizedRawText() {
        listOf("hello", "https://example.com", "WIFI:S:a;;", "sms:1", "javascript:1", "x://y").forEach {
            assertEquals(it, parseOk(it).payload.raw.value)
        }
    }

    @Test
    fun plainTextIsTextAndMalformedKnownFormatsAreNotText() {
        assertTrue(parseOk("hello world").payload is ScanPayload.Text)
        assertTrue(parseOk("WIFI:broken").payload is ScanPayload.Unknown)
    }

    @Test
    fun parsingIsDeterministic() {
        val raw = "https://paypal.com@evil.example/\u202Ex?a=1"
        assertEquals(PayloadParser.parse(raw), PayloadParser.parse(raw))
    }
}
