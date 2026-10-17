package com.lookfirst.domain.decoder

import com.lookfirst.domain.model.ScanPayload
import com.lookfirst.domain.model.WifiSecurity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiParsingTest {
    private fun wifi(raw: String) = decode<ScanPayload.Wifi>(raw)

    private fun isUnknownWifi(raw: String): Boolean =
        (parseOk(raw).payload as? ScanPayload.Unknown)?.declaredType == "WIFI"

    @Test
    fun standardWpaNetwork() {
        val w = wifi("WIFI:T:WPA;S:HomeNet;P:secret123;;")
        assertEquals("HomeNet", w.ssid.value)
        assertEquals(WifiSecurity.WPA, w.security)
        assertEquals("secret123", w.password?.value)
        assertFalse(w.hidden)
    }

    @Test
    fun fieldsInAnyOrderAndSchemeInAnyCase() {
        val w = wifi("wifi:H:true;P:pw;S:Net;T:WEP;;")
        assertEquals("Net", w.ssid.value)
        assertEquals(WifiSecurity.WEP, w.security)
        assertEquals("pw", w.password?.value)
        assertTrue(w.hidden)
    }

    @Test
    fun escapesAreUnescaped() {
        val w = wifi("WIFI:S:a\\;b\\,c\\:d\\\\e\\\"f;P:p\\;w;;")
        assertEquals("a;b,c:d\\e\"f", w.ssid.value)
        assertEquals("p;w", w.password?.value)
    }

    @Test
    fun unknownEscapeKeepsTheBackslash() {
        assertEquals("a\\nb", wifi("WIFI:S:a\\nb;;").ssid.value)
    }

    @Test
    fun openNetworkHasNoPassword() {
        val w = wifi("WIFI:T:nopass;S:Cafe;;")
        assertEquals(WifiSecurity.NONE, w.security)
        assertNull(w.password?.value)
        assertEquals(WifiSecurity.NONE, wifi("WIFI:S:Cafe;;").security)
    }

    @Test
    fun securityVariants() {
        assertEquals(WifiSecurity.WPA3, wifi("WIFI:T:SAE;S:x;P:y;;").security)
        assertEquals(WifiSecurity.WPA3, wifi("WIFI:T:WPA3;S:x;P:y;;").security)
        assertEquals(WifiSecurity.WPA, wifi("WIFI:T:WPA2;S:x;P:y;;").security)
        assertEquals(WifiSecurity.UNKNOWN, wifi("WIFI:T:WPA2-EAP;S:x;P:y;;").security)
    }

    @Test
    fun missingTerminatorIsAccepted() {
        assertEquals("Net", wifi("WIFI:S:Net;P:pw").ssid.value)
    }

    @Test
    fun brokenWifiIsUnknownNotACrash() {
        listOf(
            "WIFI:",
            "WIFI:;;",
            "WIFI:T:WPA;P:pw;;",
            "WIFI:S:;P:pw;;",
            "WIFI:garbage",
            "WIFI:S",
            "WIFI:T:WPA;S;P:x;;",
            "WIFI:H:maybe;S:x;;",
        ).forEach { assertTrue(it, isUnknownWifi(it)) }
    }

    @Test
    fun duplicatedCriticalFieldsAreRejected() {
        assertTrue(isUnknownWifi("WIFI:S:Good;S:Evil;P:x;;"))
        assertTrue(isUnknownWifi("WIFI:S:x;P:a;P:b;;"))
        assertTrue(isUnknownWifi("WIFI:S:x;T:WPA;T:nopass;;"))
    }

    @Test
    fun tooManyFieldsAreRejected() {
        assertTrue(isUnknownWifi("WIFI:S:x;" + "K:v;".repeat(100)))
    }

    @Test
    fun toStringNeverContainsScannedContent() {
        assertFalse(wifi("WIFI:S:NetName;P:topsecret;;").toString().contains("topsecret"))
        assertFalse(wifi("WIFI:S:NetName;P:topsecret;;").toString().contains("NetName"))
        val ambiguous = parseOk("WIFI:S:x;P:pwdone;P:pwdtwo;;").payload.toString()
        assertFalse(ambiguous.contains("pwd"))
        assertFalse(ambiguous.contains("WIFI:S"))
    }
}
