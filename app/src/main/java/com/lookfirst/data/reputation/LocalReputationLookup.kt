package com.lookfirst.data.reputation

import com.lookfirst.domain.analysis.BrandList
import com.lookfirst.domain.analysis.LISTS_STALE_AFTER_DAYS
import com.lookfirst.domain.analysis.ReputationLookup
import com.lookfirst.domain.analysis.ReputationResult
import com.lookfirst.domain.analysis.UserContentPlatforms
import com.lookfirst.domain.host.NormalizedHost
import com.lookfirst.domain.host.PublicSuffixList
import com.lookfirst.domain.model.HostKind
import javax.inject.Inject

private const val MAX_PROBES = 16

/** One probe is for `h:`; the rest are `d:`. */
private const val MAX_DOMAIN_PROBES = MAX_PROBES - 1

/**
 * Looks a host up in the indexes the phone built from the public lists (ADR-0008). Offline and synchronous: the
 * indexes are searched in place, nothing about the host leaves the phone and nothing is logged.
 *
 * The embedded allow-list of official domains ([BrandList.officialDomains]) is consulted first: a list can add a
 * warning for any other host but never for an official registrable domain or anything under it. Without the
 * Public Suffix List nothing can be classified and the answer is "lists missing", never a clean result. Then, for
 * each source, `h:` for the host and `d:` for the host and each parent down to
 * the registrable domain included, never the public suffix. At most [MAX_PROBES] probes per source.
 */
class LocalReputationLookup internal constructor(
    private val store: ListStore,
    private val officialDomains: Set<String>,
    private val publicSuffixList: () -> PublicSuffixList?,
) : ReputationLookup {
    @Inject
    internal constructor(store: ListStore) : this(store, BrandList.officialDomains, { PublicSuffixList.instance })

    override fun lookup(host: NormalizedHost): ReputationResult {
        val lists = store.current() ?: return ReputationResult.ListsMissing
        return lookupIn(lists, store.staleAgeDays(lists), host)
    }

    internal fun lookupIn(lists: LoadedLists, ageDays: Int, host: NormalizedHost): ReputationResult {
        val suffixes = publicSuffixList() ?: return ReputationResult.ListsMissing
        val ascii = host.ascii
        val registrable = if (host.kind == HostKind.DOMAIN) {
            suffixes.registrableDomain(ascii)?.let { UserContentPlatforms.registrableFor(ascii, it) }
        } else {
            null
        }
        val official = registrable != null && registrable in officialDomains
        val domains = if (!official && registrable != null) domainCandidates(ascii, registrable) else emptyList()
        val hostKey = Lfix.keyOf(Lfix.KIND_HOST, ascii)
        for (loaded in lists.sources) {
            val hostListed = !official && loaded.index.contains(hostKey)
            val domainListed = domains.any { loaded.index.contains(Lfix.keyOf(Lfix.KIND_DOMAIN, it)) }
            if (hostListed || domainListed) return ReputationResult.Listed(loaded.source.displayName)
        }
        return if (ageDays > LISTS_STALE_AFTER_DAYS || ageDays < 0) {
            ReputationResult.ListsStale(ageDays)
        } else {
            ReputationResult.NotListed
        }
    }

    /** [host] and its parents down to [registrable] included. Beyond the probe cap, only the two ends. */
    internal fun domainCandidates(host: String, registrable: String): List<String> {
        val names = ArrayList<String>()
        var name: String? = host
        while (name != null && names.size < MAX_DOMAIN_PROBES) {
            names += name
            name = if (name == registrable) null else name.substringAfter('.', "").takeIf { it.isNotEmpty() }
        }
        return if (names.lastOrNull() == registrable) names else listOf(host, registrable).distinct()
    }
}
