package com.lookfirst.domain.decoder

import com.lookfirst.domain.model.ScanPayload
import com.lookfirst.domain.model.Untrusted
import com.lookfirst.domain.model.WifiSecurity
import java.util.Locale

/**
 * `WIFI:T:WPA;S:ssid;P:pass;H:true;;` with `\` escapes, fields in any order.
 * A repeated S, T, P or H is ambiguous (which one will the phone take?) and is rejected.
 */
internal object WifiParser {
    fun parse(text: String, afterScheme: String): ScanPayload {
        val fields = EscapedFields.scan(afterScheme) ?: return unknown(text)
        val byKey = HashMap<String, String>()
        for ((key, value) in fields) {
            if (key in SINGLE_KEYS && byKey.put(key, value) != null) return unknown(text)
        }
        val ssid = byKey["S"]
        val hidden = parseHidden(byKey["H"])
        if (ssid.isNullOrEmpty() || hidden == null) return unknown(text)
        return ScanPayload.Wifi(
            raw = Untrusted(text),
            ssid = Untrusted(ssid),
            security = parseSecurity(byKey["T"]),
            password = byKey["P"]?.takeIf { it.isNotEmpty() }?.let { Untrusted(it) },
            hidden = hidden,
        )
    }

    private fun unknown(text: String) = ScanPayload.Unknown(Untrusted(text), "WIFI")

    private fun parseHidden(value: String?): Boolean? = when (value?.lowercase(Locale.ROOT)) {
        null, "", "false" -> false
        "true" -> true
        else -> null
    }

    private fun parseSecurity(value: String?): WifiSecurity {
        val t = value?.uppercase(Locale.ROOT).orEmpty()
        return when {
            t.isEmpty() || t == "NOPASS" -> WifiSecurity.NONE
            t == "WEP" -> WifiSecurity.WEP
            t.startsWith("WPA3") || t == "SAE" -> WifiSecurity.WPA3
            t == "WPA" || t == "WPA2" || t == "WPA/WPA2" -> WifiSecurity.WPA
            else -> WifiSecurity.UNKNOWN
        }
    }

    private val SINGLE_KEYS = setOf("S", "T", "P", "H")
}
