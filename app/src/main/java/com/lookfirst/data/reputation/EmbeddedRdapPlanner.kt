package com.lookfirst.data.reputation

import com.lookfirst.domain.host.HostNormalizer
import com.lookfirst.domain.host.HostResources
import com.lookfirst.domain.host.HostResult
import com.lookfirst.domain.host.PublicSuffixList
import com.lookfirst.domain.model.HostKind
import com.lookfirst.domain.rdap.RdapPlan
import com.lookfirst.domain.rdap.RdapPlanner
import com.lookfirst.domain.rdap.RdapTarget
import com.lookfirst.domain.rdap.RdapUnavailable
import javax.inject.Inject

private const val BOOTSTRAP_FILE = "rdap-dns.json"

/**
 * Which registry to ask, from data embedded in the APK (nothing is fetched, nothing depends on the lists): the
 * registrable domain comes from [HostNormalizer] and the ICANN section of the embedded Public Suffix List, the server
 * from the embedded IANA RDAP bootstrap, parsed once. Both are refreshed with each app version
 * (scripts/gen-host-data.py). Offline and synchronous. Nothing about the host is logged.
 */
class EmbeddedRdapPlanner @Inject constructor() : RdapPlanner {
    override fun plan(asciiHost: String): RdapPlan {
        val bootstrap = embedded ?: return RdapPlan.Unavailable(RdapUnavailable.NO_BOOTSTRAP)
        val psl = PublicSuffixList.instance ?: return RdapPlan.Unavailable(RdapUnavailable.NO_BOOTSTRAP)
        return planIn(psl, bootstrap, asciiHost)
    }

    internal companion object {
        /** The embedded bootstrap, read once; null if the file is missing or not the expected shape. */
        val embedded: RdapBootstrap? by lazy {
            HostResources.bytes(BOOTSTRAP_FILE)?.let { RdapBootstrap.parse(it) }
        }

        fun planIn(psl: PublicSuffixList, bootstrap: RdapBootstrap, host: String): RdapPlan {
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
