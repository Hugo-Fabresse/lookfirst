package com.lookfirst.domain.host

import java.util.Locale

private const val PRIVATE_MARKER = "#PRIVATE"

/**
 * Public Suffix List rules (publicsuffix.org algorithm): plain rules, `*.` wildcards and `!` exceptions, embedded
 * as a dated copy (see the header of public-suffix-list.txt). Hosts are lowercase ASCII (punycode) names.
 * Rules of the private section (blogspot.com, github.io...) are known too: anyone can register a name there.
 */
internal class PublicSuffixList private constructor(
    private val rules: Set<String>,
    private val wildcards: Set<String>,
    private val exceptions: Set<String>,
    private val privateRules: Set<String>,
    private val privateWildcards: Set<String>,
) {
    /**
     * Number of labels of the public suffix of [labels]; the unlisted-TLD default rule `*` gives 1. A matching
     * exception rule wins and removes its leftmost label; otherwise the longest rule wins.
     */
    fun suffixLabelCount(labels: List<String>): Int = suffixLabelCount(labels, includePrivate = true)

    /** [includePrivate] false: the rules of the private section (blogspot.com, github.io...) are ignored. */
    private fun suffixLabelCount(labels: List<String>, includePrivate: Boolean): Int {
        var best = 1
        for (start in labels.indices) {
            val candidate = labels.subList(start, labels.size).joinToString(".")
            val count = labels.size - start
            if (candidate in exceptions) return count - 1
            if (candidate in rules && counts(candidate, privateRules, includePrivate)) best = maxOf(best, count)
            val parent = if (start + 1 < labels.size) labels.subList(start + 1, labels.size).joinToString(".") else null
            if (parent != null && parent in wildcards && counts(parent, privateWildcards, includePrivate)) {
                best = maxOf(best, count)
            }
        }
        return best
    }

    /** A rule counts when private rules are wanted, or when it is not one of them. */
    private fun counts(rule: String, privateOnes: Set<String>, includePrivate: Boolean): Boolean =
        includePrivate || rule !in privateOnes

    /**
     * The registrable domain (public suffix plus one label) of [asciiHost], or null if the host is itself a public
     * suffix or has an empty label.
     */
    fun registrableDomain(asciiHost: String): String? {
        val labels = asciiHost.lowercase(Locale.ROOT).split('.')
        if (labels.any { it.isEmpty() }) return null
        val suffix = suffixLabelCount(labels)
        return if (labels.size <= suffix) null else labels.takeLast(suffix + 1).joinToString(".")
    }

    /**
     * The registrable domain under the ICANN section only: what a registry knows. `user.github.io` gives `github.io`,
     * not `user.github.io` (nobody can ask the `io` registry about a name its owner registered at GitHub). Null if the
     * host is itself a public suffix of the ICANN section or has an empty label.
     */
    fun icannRegistrableDomain(asciiHost: String): String? {
        val labels = asciiHost.lowercase(Locale.ROOT).split('.')
        if (labels.any { it.isEmpty() }) return null
        val suffix = suffixLabelCount(labels, includePrivate = false)
        return if (labels.size <= suffix) null else labels.takeLast(suffix + 1).joinToString(".")
    }

    /** True when [tld] (one ASCII label) is a top-level domain of the ICANN section: the root zone delegates it. */
    fun isIcannTld(tld: String): Boolean {
        val key = tld.lowercase(Locale.ROOT)
        return (key in rules && key !in privateRules) || (key in wildcards && key !in privateWildcards)
    }

    /** True when the suffix of [labels] (its last [suffixCount] labels) is a private-section rule. */
    fun isPrivateSuffix(labels: List<String>, suffixCount: Int): Boolean {
        val suffix = labels.takeLast(suffixCount)
        val text = suffix.joinToString(".")
        return text in privateRules || (suffix.size > 1 && suffix.drop(1).joinToString(".") in privateWildcards)
    }

    companion object {
        /** Null when the embedded list is missing: callers then report an incomplete analysis. */
        val instance: PublicSuffixList? by lazy { load() }

        fun parse(lines: List<String>): PublicSuffixList {
            val rules = HashSet<String>()
            val wildcards = HashSet<String>()
            val exceptions = HashSet<String>()
            val privateRules = HashSet<String>()
            val privateWildcards = HashSet<String>()
            var inPrivate = false
            for (line in lines) {
                val rule = line.trim()
                when {
                    rule == PRIVATE_MARKER -> inPrivate = true
                    rule.isEmpty() || rule[0] == '#' -> Unit
                    rule[0] == '!' -> exceptions += rule.substring(1)
                    rule.startsWith("*.") -> {
                        wildcards += rule.substring(2)
                        if (inPrivate) privateWildcards += rule.substring(2)
                    }
                    else -> {
                        rules += rule
                        if (inPrivate) privateRules += rule
                    }
                }
            }
            return PublicSuffixList(rules, wildcards, exceptions, privateRules, privateWildcards)
        }

        private fun load(): PublicSuffixList? = HostResources.lines("public-suffix-list.txt")?.let { parse(it) }
    }
}
