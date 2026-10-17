package com.lookfirst.domain.analysis

import com.lookfirst.domain.host.Confusables
import com.lookfirst.domain.host.NormalizedHost
import com.lookfirst.domain.host.PublicSuffixList
import com.lookfirst.domain.model.LookalikeKind
import com.lookfirst.domain.model.Signal
import com.lookfirst.domain.model.SignalCode
import com.lookfirst.domain.model.Untrusted

/** Words that dress a fake address up as a service page ("paypal-login", "colis-laposte"), English and French. */
private val LURE_WORDS =
    setOf(
        "login", "signin", "secure", "security", "verify", "verification", "account", "update", "support", "help",
        "service", "billing", "payment", "pay", "wallet", "auth", "confirm", "confirmation", "recovery", "unlock",
        "suspended", "alert", "alerte", "official", "customer", "client", "connect", "connexion", "compte", "securite",
        "verifier", "paiement", "facture", "refund", "remboursement", "reward", "gift", "claim", "access", "portal",
        "espace", "colis", "livraison", "suivi", "tracking", "delivery", "parcel", "gouv", "amende", "amendes",
        "stationnement", "parking", "rdv", "aide", "assure", "mon", "banque", "id", "fr", "app",
    )

/** Cheap or abused TLDs: a small typo of a brand next to one of them is a stronger hint. */
private val RISKY_TLDS =
    setOf(
        "xyz", "top", "info", "click", "link", "icu", "cyou", "buzz", "online", "site", "shop", "vip", "cc", "tk",
        "ml", "ga", "cf", "gq", "work", "live", "rest", "support", "bond", "sbs", "cfd", "lat", "club", "store",
    )

private const val MIN_TYPO_LENGTH = 6
private const val MIN_TWO_EDITS_LENGTH = 10
private const val MIN_DISTINCTIVE_LENGTH = 6
private const val MIN_UNHINTED_TYPO_LENGTH = 8
private const val MAX_TWO_EDITS = 2

private const val RANK_HOMOGLYPH = 0
private const val RANK_EMBEDDED = 1
private const val RANK_TYPO = 2
private const val RANK_COMBINED = 3

/**
 * Compares the registrable name of one host with [BrandList]. Official domains are never reported. An exact name on
 * another TLD (google.de) is not reported for an ordinary brand, but it is for a strict one (ameli.info), on a
 * platform anyone can use (paypal.blogspot.com), and when the official domain is followed by more labels
 * (paypal.com.cn, impots.gouv.fr.ma).
 */
internal class BrandMatcher(host: NormalizedHost, suffixes: PublicSuffixList) {
    private val ascii = host.asciiLabels
    private val unicode = host.unicodeLabels
    private val pslCount = suffixes.suffixLabelCount(ascii)
    private val pslRegistrable = ascii.takeLast(pslCount + 1).joinToString(".")

    /** A tenant of an open platform (`x.wordpress.com`) counts the platform as a suffix, like `x.blogspot.com`. */
    private val tenantName = UserContentPlatforms.registrableFor(host.ascii, pslRegistrable)
    private val isTenant = tenantName != pslRegistrable
    private val suffixCount = if (isTenant) tenantName.count { it == '.' } else pslCount

    /** Index of the label just left of the public suffix; negative when the host is a public suffix itself. */
    private val nameIndex = ascii.size - suffixCount - 1
    private val onPlatform = isTenant || suffixes.isPrivateSuffix(ascii, suffixCount)
    private val skeletons: List<String> =
        if (nameIndex < 0) emptyList() else unicode.take(nameIndex + 1).map { Confusables.skeleton(it) ?: it }
    private val hasLure = (0..nameIndex).any { isLure(ascii[it]) || isLure(skeletons[it]) }
    private val hint = hasLure || ascii.last() in RISKY_TLDS

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

    private fun isLure(label: String) = label.split('-').any { it in LURE_WORDS }

    private fun matchBrand(brand: Brand): Match? {
        if (brand.domains.any { isEmbedded(it) }) return Match(brand, RANK_EMBEDDED, LookalikeKind.COMBINED)
        if (unicode[nameIndex] in brand.ownNames) {
            // The brand's own name on an unlisted TLD: fine, unless the brand is strict or the TLD is open to all.
            return if (onPlatform || brand.strict) Match(brand, RANK_COMBINED, LookalikeKind.COMBINED) else null
        }
        return brand.keywords.mapNotNull { matchKeyword(brand, it) }.minByOrNull { it.rank }
    }

    /** The official domain written out before more labels: `paypal.com.secure-login.example`, `ameli.fr.ru`. */
    private fun isEmbedded(domain: String): Boolean {
        val parts = domain.split('.')
        return (0..ascii.size - parts.size - 1).any { start -> parts.indices.all { ascii[start + it] == parts[it] } }
    }

    private fun matchKeyword(brand: Brand, keyword: BrandKeyword): Match? {
        val name = unicode[nameIndex]
        val sk = skeletons[nameIndex]
        val rank = when {
            sk == keyword.skeleton -> RANK_HOMOGLYPH
            isTypo(sk, keyword) -> RANK_TYPO
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

    /** Separator removed (`la-poste`), or a small edit: needs a hint for short names (DECISIONS: TYPO). */
    private fun isTypo(sk: String, keyword: BrandKeyword): Boolean {
        val keywordSk = keyword.skeleton
        if (keyword.generic) return false
        if (sk.replace("-", "") == keywordSk.replace("-", "")) return true
        val budget = typoBudget(keyword)
        val needsHint = keyword.text.length < MIN_UNHINTED_TYPO_LENGTH && !keyword.alwaysTypo
        return budget > 0 && (hint || !needsHint) && editDistance(sk, keywordSk, budget) <= budget
    }

    private fun isDistinctive(keyword: BrandKeyword) = !keyword.generic && keyword.text.length >= MIN_DISTINCTIVE_LENGTH

    /** `paypal-login.com`, `secure-paypal.com`: the brand as a whole hyphen-separated part, alone if distinctive. */
    private fun isCombinedInName(name: String, sk: String, keyword: BrandKeyword): Boolean =
        (hasLure || isDistinctive(keyword)) && (containsPart(name, keyword.text) || containsPart(sk, keyword.skeleton))

    /** `paypal.example.com`: a distinctive brand as a whole sub-domain label, or any brand with a lure word. */
    private fun isInSubdomain(keyword: BrandKeyword): Boolean {
        if (!isDistinctive(keyword) && !hasLure) return false
        return (0 until nameIndex).any {
            containsPart(ascii[it], keyword.text) || containsPart(skeletons[it], keyword.skeleton)
        }
    }
}

/**
 * Edit budget of the typo rule, sized on the letters the brand is written with ([BrandKeyword.text]); the skeleton
 * is only used to measure the distance (`m` becomes `rn`, so `tmobile` has an 8-character skeleton).
 */
internal fun typoBudget(keyword: BrandKeyword): Int = when {
    keyword.noTypo -> 0
    keyword.text.length >= MIN_TWO_EDITS_LENGTH -> MAX_TWO_EDITS
    keyword.text.length >= MIN_TYPO_LENGTH -> 1
    else -> 0
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
