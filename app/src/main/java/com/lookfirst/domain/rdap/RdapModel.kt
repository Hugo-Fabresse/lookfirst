package com.lookfirst.domain.rdap

/** Whole budget for a domain-age request: DNS, connection, answer (ADR-0004, same as the redirect trace). */
const val RDAP_TIMEOUT_MILLIS = 5_000L

/** Largest registry answer read, in bytes. A longer one is an attack on the parser, not a domain record. */
const val RDAP_MAX_BODY_BYTES = 256 * 1024

/** Redirects of the registry answered at most, each one to the same host only (decision D-rdap). */
const val RDAP_MAX_REDIRECTS = 3

/**
 * The registry server the IANA bootstrap names for a TLD: an `https` host (a domain name, never an address, no port,
 * no userinfo) and the path that ends with `/`, as RFC 9224 writes base URLs. Built only by the bootstrap reader.
 */
data class RdapServer(val host: String, val path: String)

/**
 * What a domain-age request is about: the registrable domain (lowercase ASCII, punycode for an IDN, the only thing
 * that is sent) and the server that is asked. The domain comes from a scanned code and never reaches logs.
 */
data class RdapTarget(val domain: String, val server: RdapServer) {
    override fun toString(): String = "RdapTarget(len=${domain.length})"
}

/** Why a domain-age request cannot be made on this phone, before anything is sent. */
enum class RdapUnavailable {
    /** The RDAP bootstrap or the Public Suffix List embedded in the app could not be read (a damaged APK). */
    NO_BOOTSTRAP,

    /** The address has no registrable domain: an IP address, a bare public suffix, a name that did not normalize. */
    NOT_A_DOMAIN,

    /** The embedded bootstrap names no usable (https) registry server for this TLD. */
    NO_SERVER,
}

sealed interface RdapPlan {
    data class Ready(val target: RdapTarget) : RdapPlan

    data class Unavailable(val reason: RdapUnavailable) : RdapPlan
}

/** Why no answer could be used. One generic sentence is shown; the detail never reaches the user or the logs. */
enum class RdapFailure {
    TIMEOUT,
    NETWORK,
    BAD_ANSWER,

    /** Both network workers are taken (blocked DNS lookups still running): nothing was sent, try again soon. */
    BUSY,
}

sealed interface RdapOutcome {
    /** The registry gave a registration date; [ageDays] is whole days since, never negative. */
    data class Registered(val registeredAtMillis: Long, val ageDays: Int) : RdapOutcome

    /** The registry answered "no such domain" (HTTP 404). */
    data object NotFound : RdapOutcome

    data class Failed(val reason: RdapFailure) : RdapOutcome
}

/** Port to the registry, on explicit user action only (ADR-0004). Never throws; cancelling the caller aborts. */
fun interface RdapClient {
    suspend fun lookup(target: RdapTarget): RdapOutcome
}

/** Port to the bootstrap embedded in the app: which server answers for this address, if any. Offline and synchronous. */
fun interface RdapPlanner {
    fun plan(asciiHost: String): RdapPlan
}
