package com.lookfirst.data.platform

import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiNetworkSuggestion
import android.provider.Settings
import androidx.annotation.RequiresApi
import com.lookfirst.domain.model.ScanPayload
import com.lookfirst.domain.model.WifiSecurity
import java.math.BigDecimal

private const val SCHEME_TEL = "tel"
private const val SCHEME_SMSTO = "smsto"
private const val SCHEME_MAILTO = "mailto"
private const val SCHEME_GEO = "geo"
private const val EXTRA_SMS_BODY = "sms_body"
private const val MAX_NUMBER_LENGTH = 64
private const val MAX_SSID_BYTES = 32
private const val MIN_PASSPHRASE = 8
private const val MAX_PASSPHRASE = 63
private const val DELETE_CODE = 0x7F
private const val DIALABLE = "+*#,;-.() "

/**
 * Builds the Intents of [ActionExecutor] from parsed fields. Each builder returns null when the content cannot be
 * turned into an Intent without guessing; none of them reads the raw scanned text. No extra comes from scan data
 * except the SMS body and the Wi-Fi suggestion; no URI permission, no PendingIntent, no `Intent.parseUri`.
 */
internal object OpenIntents {
    /** Dial pad with the number filled in; never a call (`ACTION_CALL`). */
    fun dial(tel: ScanPayload.Tel): Intent? = dialable(tel.number.value)?.let {
        Intent(Intent.ACTION_DIAL, Uri.fromParts(SCHEME_TEL, it, null))
    }

    /** `smsto:` + body: the user still presses send in the messaging app. */
    fun sms(sms: ScanPayload.Sms): Intent? = dialable(sms.number.value)?.let { number ->
        Intent(Intent.ACTION_SENDTO, Uri.fromParts(SCHEME_SMSTO, number, null)).apply {
            sms.body?.value?.let { putExtra(EXTRA_SMS_BODY, it) }
        }
    }

    /**
     * `mailto:` with recipients, subject and body in the URI itself. Cc and bcc are left out: only the "to" is
     * shown to the user, a hidden recipient would be a leak (T-SCH-7). Never an attachment.
     */
    fun mail(mail: ScanPayload.Mail): Intent? {
        if (mail.to.any { !isPlainAddress(it.value) }) return null
        val recipients = mail.to.joinToString(",") { Uri.encode(it.value, "@") }
        val query = listOfNotNull(
            mail.subject?.let { "subject=" + Uri.encode(it.value) },
            mail.body?.let { "body=" + Uri.encode(it.value) },
        ).joinToString("&")
        val text = "$SCHEME_MAILTO:$recipients" + if (query.isEmpty()) "" else "?$query"
        return Intent(Intent.ACTION_SENDTO, Uri.parse(text))
    }

    /** `geo:lat,lon` rebuilt from the parsed numbers, with the optional label as an encoded `q`. */
    fun geo(geo: ScanPayload.Geo): Intent? {
        if (!geo.lat.isFinite() || !geo.lon.isFinite()) return null
        val point = "${plain(geo.lat)},${plain(geo.lon)}"
        val query = geo.query?.value?.takeIf { it.isNotEmpty() }?.let { "?q=" + Uri.encode(it) } ?: ""
        return Intent(Intent.ACTION_VIEW, Uri.parse("$SCHEME_GEO:$point$query"))
    }

    /** The Intent for an already verified canonical URL, sent to [browserPackage] only. */
    fun browse(uri: Uri, browserPackage: String): Intent = Intent(Intent.ACTION_VIEW, uri)
        .addCategory(Intent.CATEGORY_BROWSABLE)
        .setPackage(browserPackage)

    /** The same URL without a target, only to ask which apps would handle it. */
    fun browseProbe(uri: Uri): Intent = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)

    /** A neutral address: it matches the browsers and nothing that claims a specific site (App Links). */
    fun browserProbe(): Intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.invalid"))
        .addCategory(Intent.CATEGORY_BROWSABLE)

    /** The system confirmation to add one network (API 30+). Null for a network that cannot be suggested. */
    @RequiresApi(android.os.Build.VERSION_CODES.R)
    fun addWifiNetwork(wifi: ScanPayload.Wifi): Intent? {
        val suggestion = suggestionOf(wifi) ?: return null
        return Intent(Settings.ACTION_WIFI_ADD_NETWORKS)
            .putParcelableArrayListExtra(Settings.EXTRA_WIFI_NETWORK_LIST, arrayListOf(suggestion))
    }

    fun wifiSettings(): Intent = Intent(Settings.ACTION_WIFI_SETTINGS)

    @RequiresApi(android.os.Build.VERSION_CODES.R)
    private fun suggestionOf(wifi: ScanPayload.Wifi): WifiNetworkSuggestion? {
        val password = wifi.password?.value
        if (wifi.ssid.value.isEmpty() || wifi.ssid.value.toByteArray().size > MAX_SSID_BYTES) return null
        if (password != null && password.length !in MIN_PASSPHRASE..MAX_PASSPHRASE) return null
        val builder = WifiNetworkSuggestion.Builder().setSsid(wifi.ssid.value).setIsHiddenSsid(wifi.hidden)
        return try {
            when {
                wifi.security == WifiSecurity.NONE && password == null -> builder
                wifi.security == WifiSecurity.WPA && password != null -> builder.setWpa2Passphrase(password)
                wifi.security == WifiSecurity.WPA3 && password != null -> builder.setWpa3Passphrase(password)
                // WEP and unknown types cannot be suggested: the settings page is the way.
                else -> return null
            }.build()
        } catch (ignored: IllegalArgumentException) {
            null
        }
    }
}

private fun dialable(number: String): String? = number.takeIf {
    it.isNotEmpty() && it.length <= MAX_NUMBER_LENGTH && it.all { c -> c in '0'..'9' || c in DIALABLE }
}

private fun isPlainAddress(address: String): Boolean = address.isNotEmpty() &&
    address.all {
        it > ' ' && it.code != DELETE_CODE && it !in ",<>\""
    }

private fun plain(value: Double): String = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
