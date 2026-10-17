package com.lookfirst.domain.analysis

import com.lookfirst.domain.model.ParseResult
import com.lookfirst.domain.model.ScanPayload
import com.lookfirst.domain.model.Signal
import com.lookfirst.domain.model.SignalCode
import com.lookfirst.domain.model.WifiSecurity

/** Wi-Fi QR: no encryption (OPEN_WIFI) or WEP (WIFI_WEP). A network name is never trusted nor judged here. */
object WifiAnalyzer : SignalAnalyzer {
    override fun analyze(result: ParseResult.Ok): List<Signal> {
        val wifi = result.payload as? ScanPayload.Wifi ?: return emptyList()
        return when (wifi.security) {
            WifiSecurity.NONE -> listOf(Signal(SignalCode.OPEN_WIFI))
            WifiSecurity.WEP -> listOf(Signal(SignalCode.WIFI_WEP))
            else -> emptyList()
        }
    }
}
