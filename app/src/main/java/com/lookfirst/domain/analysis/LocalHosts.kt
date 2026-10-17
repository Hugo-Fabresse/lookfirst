package com.lookfirst.domain.analysis

import com.lookfirst.domain.host.PublicSuffixList

/**
 * Names that lead to the user's own network or nowhere on the Internet (LF-34): a single label (`router`),
 * `localhost`, the reserved suffixes of RFC 6761 / 6762 / 8375, the private-use suffixes networks make up (`.internal`, `.lan`...),
 * a few well-known box names, and any TLD the ICANN section of the embedded Public Suffix List does not list. One
 * signal covers all of them. `.box` is a real ICANN gTLD: only the frozen box names below are flagged, never `*.box`.
 * The frozen suffixes are also the fallback when the list is missing.
 */
internal object LocalHosts {
    private val SUFFIXES: Set<String> =
        setOf(
            "localhost", "test", "example", "invalid", "local", "home.arpa",
            "internal", "lan", "intranet", "corp", "home",
        )

    /** Default names of home boxes under the real gTLD `.box` (vendor documentation); exact hosts, not suffixes. */
    private val BOX_HOSTS: Set<String> = setOf("fritz.box", "speedport.box", "easy.box", "mediacenter.box")

    /** [host] is a DOMAIN-kind ASCII host, lowercase, without trailing dot. */
    fun matches(host: String): Boolean {
        if ('.' !in host) return true
        if (host in BOX_HOSTS || SUFFIXES.any { host == it || host.endsWith(".$it") }) return true
        // Without the list the TLD check is skipped; the host is not accused on missing data.
        val list = PublicSuffixList.instance ?: return false
        return !list.isIcannTld(host.substringAfterLast('.'))
    }
}
