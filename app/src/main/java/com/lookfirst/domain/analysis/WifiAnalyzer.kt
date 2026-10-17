package com.lookfirst.domain.analysis

import com.lookfirst.domain.model.ParseResult
import com.lookfirst.domain.model.ScanPayload
import com.lookfirst.domain.model.Signal
import com.lookfirst.domain.model.SignalCode
import com.lookfirst.domain.model.WifiSecurity

/**
 * Wi-Fi QR: no encryption (OPEN_WIFI), WEP (WIFI_WEP) or a security type nobody can name (WIFI_UNKNOWN_SECURITY).
 * A network name is never trusted nor judged here.
 */
object WifiAnalyzer : SignalAnalyzer {
    override fun analyze(result: ParseResult.Ok, context: AnalysisContext): List<Signal> {
        val wifi = result.payload as? ScanPayload.Wifi ?: return emptyList()
        return when (wifi.security) {
            WifiSecurity.NONE -> listOf(Signal(SignalCode.OPEN_WIFI))
            WifiSecurity.WEP -> listOf(Signal(SignalCode.WIFI_WEP))
            WifiSecurity.UNKNOWN -> listOf(Signal(SignalCode.WIFI_UNKNOWN_SECURITY))
            WifiSecurity.WPA, WifiSecurity.WPA3 -> emptyList()
        }
    }
}
