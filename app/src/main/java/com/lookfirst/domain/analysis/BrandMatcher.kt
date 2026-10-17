package com.lookfirst.domain.analysis

import com.lookfirst.domain.host.Confusables
import com.lookfirst.domain.host.NormalizedHost
import com.lookfirst.domain.host.PublicSuffixList
import com.lookfirst.domain.model.LookalikeKind
import com.lookfirst.domain.model.Signal
import com.lookfirst.domain.model.SignalCode
import com.lookfirst.domain.model.Untrusted

/** Words that dress a fake address up as a service page ("paypal-login", "secure-impots"), English and French. */
private val LURE_WORDS =
    setOf(
        "login", "signin", "secure", "security", "verify", "verification", "account", "update", "support", "help",
        "service", "billing", "payment", "pay", "wallet", "auth", "confirm", "confirmation", "recovery", "unlock",
        "suspended", "alert", "official", "customer", "connect", "connexion", "compte", "securite", "verifier",
        "paiement", "facture", "refund", "remboursement", "reward", "gift", "claim", "access", "portal", "espace",
    )

private const val MIN_TYPO_LENGTH = 6
private const val MIN_TWO_EDITS_LENGTH = 10
private const val MIN_DISTINCTIVE_LENGTH = 6
private const val MAX_TWO_EDITS = 2

private const val RANK_HOMOGLYPH = 0
private const val RANK_EMBEDDED = 1
private const val RANK_TYPO = 2
private const val RANK_COMBINED = 3

/**
 * Compares the registrable name of one host with [BrandList]. An exact name on another TLD (google.de) is not
 * reported, but the same name on a platform anyone can use (blogspot.com, github.io) is. Official domains never are.
 */
internal class BrandMatcher(host: NormalizedHost, suffixes: PublicSuffixList) {
    private val ascii = host.asciiLabels
    private val unicode = host.unicodeLabels
    private val suffixCount = suffixes.suffixLabelCount(ascii)

    /** Index of the label just left of the public suffix; negative when the host is a public suffix itself. */
    private val nameIndex = ascii.size - suffixCount - 1
    private val onPlatform = suffixes.isPrivateSuffix(ascii, suffixCount)
    private val skeletons: List<String> =
        if (nameIndex < 0) emptyList() else unicode.take(nameIndex + 1).map { Confusables.skeleton(it) ?: it }
    private val hasLure = ascii.take(nameIndex + 1).any { label -> label.split('-').any { it in LURE_WORDS } }

    private class Match(val brand: Brand, val rank: Int, val kind: LookalikeKind)

    fun signal(): Signal? {
        if (nameIndex < 0) return null
        if (ascii.drop(nameIndex).joinToString(".") in BrandList.officialDomains) return null
        val best = BrandList.brands.mapNotNull { matchBrand(it) }.minByOrNull { it.rank } ?: return null
        return Signal(
            SignalCode.BRAND_LOOKALIKE,
            evidence = Untrusted(best.brand.primaryDomain),
            details = listOf(best.kind),
        )
    }

    private fun matchBrand(brand: Brand): Match? {
        if (brand.domains.any { isEmbedded(it) }) return Match(brand, RANK_EMBEDDED, LookalikeKind.COMBINED)
        return brand.keywords.mapNotNull { matchKeyword(brand, it) }.minByOrNull { it.rank }
    }

    /** The official domain written out inside sub-domain labels: `paypal.com.secure-login.example`. */
    private fun isEmbedded(domain: String): Boolean {
        val parts = domain.split('.')
        return (0..nameIndex - parts.size).any { start -> parts.indices.all { ascii[start + it] == parts[it] } }
    }

    private fun matchKeyword(brand: Brand, keyword: BrandKeyword): Match? {
        val name = unicode[nameIndex]
        val sk = skeletons[nameIndex]
        val keywordSk = keyword.skeleton
        val exact = name == keyword.text
        val rank = when {
            exact && brand.usesName(name) -> if (onPlatform) RANK_COMBINED else null
            exact -> RANK_TYPO
            sk == keywordSk -> RANK_HOMOGLYPH
            isTypo(sk, keywordSk, keyword) -> RANK_TYPO
            isCombinedInName(name, sk, keyword) || isInSubdomain(keyword) -> RANK_COMBINED
            else -> null
        }
        val kind = when (rank) {
            RANK_HOMOGLYPH -> LookalikeKind.HOMOGLYPH
            RANK_TYPO -> LookalikeKind.TYPO
            else -> LookalikeKind.COMBINED
        }
        return rank?.let { Match(brand, it, kind) }
    }

    private fun isTypo(sk: String, keywordSk: String, keyword: BrandKeyword): Boolean {
        if (keyword.generic) return false
        if (sk.replace("-", "") == keywordSk.replace("-", "")) return true
        val budget = when {
            keywordSk.length >= MIN_TWO_EDITS_LENGTH -> MAX_TWO_EDITS
            keywordSk.length >= MIN_TYPO_LENGTH -> 1
            else -> 0
        }
        return budget > 0 && editDistance(sk, keywordSk, budget) <= budget
    }

    /** `paypal-login.com`, `secure-paypal.com`: the brand as a whole hyphen-separated part, with a lure word. */
    private fun isCombinedInName(name: String, sk: String, keyword: BrandKeyword): Boolean =
        hasLure && (containsPart(name, keyword.text) || containsPart(sk, keyword.skeleton))

    /** `paypal.example.com`: a distinctive brand as a whole sub-domain label, or any brand with a lure word. */
    private fun isInSubdomain(keyword: BrandKeyword): Boolean {
        val distinctive = !keyword.generic && keyword.text.length >= MIN_DISTINCTIVE_LENGTH
        if (!distinctive && !hasLure) return false
        return (0 until nameIndex).any {
            containsPart(ascii[it], keyword.text) || containsPart(skeletons[it], keyword.skeleton)
        }
    }
}

/** True when [part] is [label] or a hyphen-delimited piece of it (`login-paypal-fr` contains `paypal`). */
internal fun containsPart(label: String, part: String): Boolean {
    var at = if (part.isEmpty()) -1 else label.indexOf(part)
    while (at >= 0) {
        if (isDelimited(label, at, at + part.length)) return true
        at = label.indexOf(part, at + 1)
    }
    return false
}

private fun isDelimited(label: String, start: Int, end: Int): Boolean =
    (start == 0 || label[start - 1] == '-') && (end == label.length || label[end] == '-')

/**
 * Optimal string alignment distance (insertion, deletion, substitution, adjacent swap), stopping early once every
 * cell of a row exceeds [limit]: returns limit + 1 then. Labels are at most 63 characters, so this is cheap.
 */
internal fun editDistance(a: String, b: String, limit: Int): Int {
    if (kotlin.math.abs(a.length - b.length) > limit) return limit + 1
    var previous2 = IntArray(b.length + 1)
    var previous = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        val current = IntArray(b.length + 1)
        current[0] = i
        var rowMin = i
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            var value = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
            if (isSwap(a, b, i, j)) value = minOf(value, previous2[j - 2] + 1)
            current[j] = value
            rowMin = minOf(rowMin, value)
        }
        if (rowMin > limit) return limit + 1
        previous2 = previous
        previous = current
    }
    return previous[b.length]
}

private fun isSwap(a: String, b: String, i: Int, j: Int): Boolean =
    i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]
