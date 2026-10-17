package com.lookfirst.data.platform

import android.content.Context
import android.telephony.TelephonyManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject

private const val ISO_COUNTRY_LENGTH = 2

/** The user's country (ISO 3166-1 alpha-2, upper case) for rules that depend on it, or null when unknown. */
fun interface RegionHintProvider {
    fun regionHint(): String?
}

/** The first valid code of [candidates], upper case: the SIM country, then the device language region. */
internal fun pickRegion(vararg candidates: String?): String? = candidates
    .asSequence()
    .mapNotNull { it?.trim()?.uppercase(Locale.ROOT) }
    .firstOrNull { it.length == ISO_COUNTRY_LENGTH && it.all { char -> char in 'A'..'Z' } }

/** SIM country first (no permission needed), then the locale. Reads nothing else, sends nothing. */
class AndroidRegionHintProvider @Inject constructor(@ApplicationContext private val context: Context) :
    RegionHintProvider {
    @Suppress("TooGenericExceptionCaught")
    override fun regionHint(): String? = try {
        val sim = (context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager)?.simCountryIso
        pickRegion(sim, Locale.getDefault().country)
    } catch (ignored: RuntimeException) {
        null
    }
}
