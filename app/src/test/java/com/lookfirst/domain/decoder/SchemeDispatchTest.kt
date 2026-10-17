package com.lookfirst.domain.decoder

import com.lookfirst.domain.model.ScanPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SchemeDispatchTest {
    private fun dangerous(raw: String) = decode<ScanPayload.DangerousScheme>(raw).scheme

    @Test
    fun javascriptIsDangerousWhateverTheCase() {
        assertEquals("javascript", dangerous("javascript:alert(1)"))
        assertEquals("javascript", dangerous("JaVaScRiPt:alert(1)"))
        assertEquals("javascript", dangerous("  javascript:alert(document.cookie)"))
    }

    @Test
    fun javascriptHiddenByTabsAndNewlinesIsStillSeen() {
        assertEquals("javascript", dangerous("java\nscript:alert(1)"))
        assertEquals("javascript", dangerous("java\tscript:alert(1)"))
        assertEquals("javascript", dangerous("\u0001javascript:alert(1)"))
    }

    @Test
    fun intentUrisAreDangerousAndNeverParsed() {
        val raw = "intent://scan/#Intent;scheme=zxing;package=com.evil;S.browser_fallback_url=http%3A%2F%2Fevil;end"
        assertEquals("intent", dangerous(raw))
        assertEquals(raw, decode<ScanPayload.DangerousScheme>(raw).raw)
    }

    @Test
    fun localAndInlineSchemesAreDangerous() {
        listOf(
            "file:///sdcard/secret" to "file",
            "content://com.app.provider/x" to "content",
            "data:text/html,<script>alert(1)</script>" to "data",
            "vbscript:msgbox(1)" to "vbscript",
            "android-app://com.evil/x" to "android-app",
            "blob:https://x/1" to "blob",
            "view-source:https://x" to "view-source",
        ).forEach { (raw, scheme) -> assertEquals(raw, scheme, dangerous(raw)) }
    }

    @Test
    fun unknownSchemeWithoutSpacesIsAppLink() {
        val link = decode<ScanPayload.AppLink>("MyBank://pay?to=123&amount=99")
        assertEquals("mybank", link.scheme)
        listOf("market://details?id=com.x", "whatsapp://send?text=hi", "ftp://x/y").forEach {
            assertTrue(it, parseOk(it).payload is ScanPayload.AppLink)
        }
    }

    @Test
    fun colonInProseIsText() {
        assertTrue(parseOk("Note: buy milk").payload is ScanPayload.Text)
        assertTrue(parseOk("Total: 12.50").payload is ScanPayload.Text)
        assertTrue(parseOk("hello").payload is ScanPayload.Text)
        assertTrue(parseOk("12:30").payload is ScanPayload.Text)
        assertTrue(parseOk("é:x").payload is ScanPayload.Text)
    }

    @Test
    fun overlongSchemeIsText() {
        assertTrue(parseOk("a".repeat(100) + ":x").payload is ScanPayload.Text)
    }
}
