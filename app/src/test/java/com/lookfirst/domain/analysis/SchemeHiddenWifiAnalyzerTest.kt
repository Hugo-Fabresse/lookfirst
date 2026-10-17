package com.lookfirst.domain.analysis

import com.lookfirst.domain.model.Severity
import com.lookfirst.domain.model.SignalCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SchemeHiddenWifiAnalyzerTest {
    @Test
    fun dangerousSchemesAreDangerous() {
        listOf(
            "javascript:alert(document.cookie)",
            "intent://scan/#Intent;scheme=zxing;package=com.evil;end",
            "data:text/html;base64,PHNjcmlwdD4=",
            "file:///sdcard/secret.txt",
            "content://com.example.provider/x",
            "​javascript:alert(1)",
        ).forEach {
            val signal = SchemeAnalyzer.on(it).single { s -> s.code == SignalCode.DANGEROUS_SCHEME }
            assertEquals(Severity.DANGEROUS, signal.severity)
        }
    }

    @Test
    fun dangerousSchemeEvidenceIsTheSchemeOnly() {
        assertEquals("javascript", SchemeAnalyzer.on("javascript:alert(1)").single().evidence?.value)
    }

    @Test
    fun appLinkIsUnknownScheme() {
        val signal = SchemeAnalyzer.on("myapp://pay?to=attacker&amount=99").single()
        assertEquals(SignalCode.UNKNOWN_SCHEME, signal.code)
        assertEquals(Severity.CAUTION, signal.severity)
        assertEquals("myapp", signal.evidence?.value)
    }

    @Test
    fun webAndKnownSchemesHaveNoSchemeSignal() {
        listOf("https://example.com/", "tel:+33123456789", "WIFI:T:WPA;S:x;P:y;;", "hello").forEach {
            assertTrue(SchemeAnalyzer.on(it).isEmpty())
        }
    }

    @Test
    fun zeroWidthSpaceInUrlCountsOnce() {
        val signal = HiddenCharAnalyzer.on("https://example.com/pa​th").single()
        assertEquals(SignalCode.HIDDEN_CHARS, signal.code)
        assertEquals(Severity.SUSPICIOUS, signal.severity)
        assertEquals(1, signal.count)
    }

    @Test
    fun rawAndDecodedFieldCharsAreSummed() {
        val signals = HiddenCharAnalyzer.on("otpauth://totp/Goo​gle:bob?secret=JBSWY3DPEHPK3PXP&issuer=Goo%E2%80%8Bgle")
        assertTrue(signals.of(SignalCode.HIDDEN_CHARS).count >= 2)
    }

    @Test
    fun cleanContentHasNoHiddenSignal() {
        assertTrue(HiddenCharAnalyzer.on("https://example.com/menu").isEmpty())
        assertTrue(HiddenCharAnalyzer.on("Bonjour, é ñ 日本語").isEmpty())
    }

    @Test
    fun bidiOverrideInHostIsDangerous() {
        val signals = HiddenCharAnalyzer.on("https://exa‮mple.com/gpj.exe")
        assertEquals(Severity.DANGEROUS, signals.of(SignalCode.BIDI_IN_HOST).severity)
        assertTrue(SignalCode.HIDDEN_CHARS in signals.codes())
    }

    @Test
    fun percentEncodedBidiInHostIsDangerous() {
        assertTrue(SignalCode.BIDI_IN_HOST in HiddenCharAnalyzer.on("https://%E2%80%AEexample.com/").codes())
    }

    @Test
    fun bidiInPathOnlyIsHiddenCharsNotBidiInHost() {
        assertEquals(setOf(SignalCode.HIDDEN_CHARS), HiddenCharAnalyzer.on("https://example.com/‮path").codes())
    }

    @Test
    fun zeroWidthInHostIsHiddenCharsNotBidi() {
        assertEquals(setOf(SignalCode.HIDDEN_CHARS), HiddenCharAnalyzer.on("https://exa​mple.com/").codes())
    }

    @Test
    fun openWifiIsCautionAndWpaIsNot() {
        val signals = WifiAnalyzer.on("WIFI:T:nopass;S:Free_Parking_WiFi;;")
        assertEquals(Severity.CAUTION, signals.of(SignalCode.OPEN_WIFI).severity)
        assertEquals(1, signals.size)
        assertEquals(setOf(SignalCode.OPEN_WIFI), WifiAnalyzer.on("WIFI:S:Airport Free;;").codes())
        assertTrue(WifiAnalyzer.on("WIFI:T:WPA;S:Home;P:correct horse;;").isEmpty())
        assertTrue(WifiAnalyzer.on("WIFI:T:WPA3;S:Home;P:correct horse;;").isEmpty())
    }

    @Test
    fun wepIsCaution() {
        val signal = WifiAnalyzer.on("WIFI:T:WEP;S:Hotel;P:12345;;").single()
        assertEquals(SignalCode.WIFI_WEP, signal.code)
        assertEquals(Severity.CAUTION, signal.severity)
    }

    @Test
    fun wifiAnalyzerIgnoresOtherPayloads() {
        assertTrue(WifiAnalyzer.on("https://example.com/").isEmpty())
    }
}
