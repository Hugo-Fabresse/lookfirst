package com.lookfirst.data.reputation

import com.lookfirst.domain.host.HostNormalizer
import com.lookfirst.domain.host.HostResult
import com.lookfirst.domain.host.PublicSuffixList
import com.lookfirst.domain.model.HostKind
import com.lookfirst.domain.rdap.RdapPlan
import com.lookfirst.domain.rdap.RdapPlanner
import com.lookfirst.domain.rdap.RdapTarget
import com.lookfirst.domain.rdap.RdapUnavailable
import java.io.File
import javax.inject.Inject

/**
 * Which registry to ask, from the installed bundle only (nothing is fetched): the registrable domain comes from
 * [HostNormalizer] and the ICANN section of the bundle's own Public Suffix List, the server from the bundle's RDAP
 * bootstrap, read once per installed bundle. Offline and synchronous; reads a file of at most 512 KiB the first time.
 * Nothing about the host is logged.
 */
class BundleRdapPlanner @Inject internal constructor(private val store: ReputationStore) : RdapPlanner {
    private class Cached(val manifestSha256: String, val bootstrap: RdapBootstrap?)

    @Volatile private var cached: Cached? = null

    override fun plan(asciiHost: String): RdapPlan {
        val bundle = store.current() ?: return RdapPlan.Unavailable(RdapUnavailable.NO_BOOTSTRAP)
        val bootstrap = bootstrapOf(bundle) ?: return RdapPlan.Unavailable(RdapUnavailable.NO_BOOTSTRAP)
        return planIn(bundle.publicSuffixList, bootstrap, asciiHost)
    }

    private fun bootstrapOf(bundle: LoadedBundle): RdapBootstrap? {
        cached?.takeIf { it.manifestSha256 == bundle.manifestSha256 }?.let { return it.bootstrap }
        val read = BundleVerifier.readBounded(File(bundle.directory, BundleFile.RDAP.path), BundleFile.RDAP.maxSize)
        val parsed = (read as? BundleVerifier.Bounded.Data)?.let { RdapBootstrap.parseLenient(it.bytes) }
        cached = Cached(bundle.manifestSha256, parsed)
        return parsed
    }

    companion object {
        internal fun planIn(psl: PublicSuffixList, bootstrap: RdapBootstrap, host: String): RdapPlan {
            val domain = registrableDomainOf(psl, host)
                ?: return RdapPlan.Unavailable(RdapUnavailable.NOT_A_DOMAIN)
            val server = bootstrap.serverFor(domain.substringAfterLast('.'))
                ?: return RdapPlan.Unavailable(RdapUnavailable.NO_SERVER)
            return RdapPlan.Ready(RdapTarget(domain, server))
        }

        /** The domain a registry knows, lowercase LDH ASCII (letters, digits, hyphens, dots), or null. */
        private fun registrableDomainOf(psl: PublicSuffixList, host: String): String? {
            val normalized = (HostNormalizer.normalize(host) as? HostResult.Normalized)?.host
            val domain = normalized?.takeIf { it.kind == HostKind.DOMAIN }?.let { psl.icannRegistrableDomain(it.ascii) }
            return domain?.takeIf { name -> name.all { it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '.' } }
        }
    }
}
