package com.lookfirst.domain.analysis

import com.lookfirst.domain.host.PublicSuffixList

/**
 * Names that lead to the user's own network or nowhere on the Internet (LF-34): a single label (`router`),
 * `localhost`, the reserved suffixes of RFC 6761 / 6762 / 8375, the private-use suffixes boxes and companies make up,
 * and any TLD the ICANN section of the embedded Public Suffix List does not list. One signal covers all of them.
 */
internal object LocalHosts {
    private val SUFFIXES: Set<String> =
        setOf(
            "localhost", "test", "example", "invalid", "local", "home.arpa",
            "internal", "lan", "box", "intranet", "corp", "home",
        )

    /** [host] is a DOMAIN-kind ASCII host, lowercase, without trailing dot. */
    fun matches(host: String): Boolean {
        if ('.' !in host) return true
        if (SUFFIXES.any { host.endsWith(".$it") }) return true
        // Without the list the TLD check is skipped; the host is not accused on missing data.
        val list = PublicSuffixList.instance ?: return false
        return !list.isIcannTld(host.substringAfterLast('.'))
    }
}
