package com.lookfirst.data.network

import com.lookfirst.data.reputation.Json
import com.lookfirst.data.reputation.JsonLimits
import com.lookfirst.data.reputation.StrictJson
import com.lookfirst.data.reputation.UtcTime

/** Registrations before this are not credible: the first `.com` dates from 1985. */
private val EARLIEST_REGISTRATION = UtcTime.midnight(1985, 1, 1)

private const val REGISTRATION = "registration"
private const val MAX_EVENTS = 200

/**
 * Reads the registration date out of a registry's answer (RFC 9083), which is untrusted: strict JSON under
 * [JsonLimits.RDAP] (the body is already capped by the transport), and of the whole document only `objectClassName`,
 * `ldhName` and `events[]` are looked at. Nothing from the answer is kept besides one instant.
 */
internal object RdapResponse {
    /**
     * The registration instant for [domain] in epoch milliseconds, or null when the answer is not a usable domain
     * record: not JSON, not an object, another object class or another name than the one asked for, no
     * `registration` event, or no event with a date that is plausible against [nowMillis].
     * With several `registration` events the latest one counts: the younger the domain looks, the more careful.
     */
    fun registrationOf(body: ByteArray, domain: String, nowMillis: Long): Long? {
        val root = StrictJson.parse(body, JsonLimits.RDAP) as? Json.Obj ?: return null
        if (!describes(root, domain)) return null
        val events = (root.fields["events"] as? Json.Arr)?.items ?: return null
        return events.asSequence()
            .take(MAX_EVENTS)
            .mapNotNull { (it as? Json.Obj)?.fields }
            .filter { (it["eventAction"] as? Json.Str)?.value == REGISTRATION }
            .mapNotNull { (it["eventDate"] as? Json.Str)?.value?.let(UtcTime::parseInstant) }
            .filter { it in EARLIEST_REGISTRATION..nowMillis + MAX_FUTURE_MILLIS }
            .maxOrNull()
    }

    /** A domain object that names itself, and names the domain that was asked for (case ignored). */
    private fun describes(root: Json.Obj, domain: String): Boolean {
        val objectClass = (root.fields["objectClassName"] as? Json.Str)?.value
        val name = (root.fields["ldhName"] as? Json.Str)?.value
        return objectClass == "domain" && name != null && name.equals(domain, ignoreCase = true)
    }

    /** A date a little ahead of the phone's clock is a clock that is behind; further than a day is not credible. */
    private const val MAX_FUTURE_MILLIS = UtcTime.DAY
}
