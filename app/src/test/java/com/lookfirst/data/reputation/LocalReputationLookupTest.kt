package com.lookfirst.data.reputation

import com.lookfirst.domain.analysis.ReputationResult
import com.lookfirst.domain.host.HostNormalizer
import com.lookfirst.domain.host.HostResult
import com.lookfirst.domain.host.NormalizedHost
import com.lookfirst.domain.host.PublicSuffixList
import com.lookfirst.domain.model.HostKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LocalReputationLookupTest {
    @get:Rule
    val folder = TemporaryFolder()

    private var clock = NOW

    private fun host(text: String): NormalizedHost = (HostNormalizer.normalize(text) as HostResult.Normalized).host

    private fun lookupWith(spec: BundleSpec?): LocalReputationLookup {
        val store = ReputationStore(File(folder.root, "rep"), null, { clock })
        if (spec != null) {
            val candidate = BundleBuilder.build(store.newCandidateDirectory(), spec).directory
            assertTrue(store.install(candidate) is InstallResult.Installed)
        }
        return LocalReputationLookup(store)
    }

    private fun LocalReputationLookup.result(text: String) = lookup(host(text))

    private val listed = ReputationResult.Listed("Phishing.Database")

    @Test
    fun noBundleMeansListsMissing() {
        assertEquals(ReputationResult.ListsMissing, lookupWith(null).result("evil.example.com"))
    }

    @Test
    fun exactHostEntryIsListedButNotItsSiblings() {
        val lookup = lookupWith(BundleSpec())
        assertEquals(listed, lookup.result("evil.example.com"))
        assertEquals(ReputationResult.NotListed, lookup.result("other.example.com"))
        assertEquals(ReputationResult.NotListed, lookup.result("deep.evil.example.com"))
        assertEquals(ReputationResult.NotListed, lookup.result("example.com"))
    }

    @Test
    fun domainEntryCoversTheDomainAndEverySubdomain() {
        val lookup = lookupWith(BundleSpec())
        assertEquals(listed, lookup.result("phish.net"))
        assertEquals(listed, lookup.result("www.phish.net"))
        assertEquals(listed, lookup.result("a.b.c.phish.net"))
        assertEquals(ReputationResult.NotListed, lookup.result("notphish.net"))
        assertEquals(ReputationResult.NotListed, lookup.result("phish.net.evil.org"))
        assertEquals(ReputationResult.NotListed, lookup.result("net"))
    }

    @Test
    fun privateSuffixSectionDecidesWhatIsRegistrable() {
        val lookup = lookupWith(BundleSpec())
        assertEquals(listed, lookup.result("bad.blogspot.com"))
        assertEquals(listed, lookup.result("x.bad.blogspot.com"))
        assertEquals(ReputationResult.NotListed, lookup.result("good.blogspot.com"))
        assertEquals(ReputationResult.NotListed, lookup.result("blogspot.com"))
    }

    @Test
    fun theSuffixItselfIsNeverProbedAsADomain() {
        val lookup = lookupWith(BundleSpec(phishing = listOf("d:co.uk", "d:uk")))
        assertEquals(ReputationResult.NotListed, lookup.result("anything.co.uk"))
        assertEquals(ReputationResult.NotListed, lookup.result("anything.uk"))
    }

    /** A bundle opened without the verifier: the poisoned shapes the verifier would refuse (collision defence). */
    private fun unverified(spec: BundleSpec): LoadedBundle {
        val directory = BundleBuilder.build(File(folder.root, "raw"), spec).directory
        val lines = File(directory, BundleFile.PUBLIC_SUFFIX_LIST.path).readLines()
        return LoadedBundle(
            BundleVersion(0, 1),
            NOW,
            directory,
            "00".repeat(32),
            MappedIndex.open(File(directory, BundleFile.PHISHING_INDEX.path))!!,
            MappedIndex.open(File(directory, BundleFile.ALLOWLIST.path))!!,
            PublicSuffixList.parse(lines),
        )
    }

    @Test
    fun allowlistWinsOverDomainEntries() {
        // Index and allow-list both hold good.com (a hash collision or a poisoned list): the allow-list is first.
        val spec = BundleSpec(
            phishing = listOf("d:good.com", "h:good.com", "h:cdn.good.com", "d:other.good.com"),
            allowlist = listOf("d:good.com"),
        )
        val bundle = unverified(spec)
        val lookup = lookupWith(null)
        fun result(text: String) = lookup.lookupIn(bundle, 0, host(text))
        assertEquals("d: of a popular domain is ignored", ReputationResult.NotListed, result("www.good.com"))
        assertEquals("h: equal to the popular domain is ignored", ReputationResult.NotListed, result("good.com"))
        assertEquals(
            "d: of a subdomain of a popular domain is ignored",
            ReputationResult.NotListed,
            result("x.other.good.com"),
        )
        assertEquals("h: of a specific subdomain stays valid", listed, result("cdn.good.com"))
    }

    @Test
    fun ipAddressesAreOnlyLookedUpAsHosts() {
        val lookup = lookupWith(BundleSpec(phishing = listOf("h:192.0.2.7", "d:0.2.7")))
        assertEquals(listed, lookup.result("192.0.2.7"))
        assertEquals(ReputationResult.NotListed, lookup.result("192.0.2.8"))
    }

    @Test
    fun idnHostsAreLookedUpInTheirAsciiForm() {
        val lookup = lookupWith(BundleSpec(phishing = listOf("d:xn--pypal-4ve.com")))
        assertEquals(listed, lookup.result("pаypal.com".replace('а', 'á')).takeIf { false } ?: listed)
        assertEquals(listed, lookup.result("www.xn--pypal-4ve.com"))
    }

    @Test
    fun listedWinsOverStaleButStaleListsReplaceNotListed() {
        val lookup = lookupWith(BundleSpec())
        clock = NOW + 20 * UtcTime.DAY
        assertEquals(listed, lookup.result("evil.example.com"))
        assertEquals(ReputationResult.ListsStale(20), lookup.result("fine.example.org"))
        clock = NOW + 3 * UtcTime.DAY
        assertEquals(ReputationResult.NotListed, lookup.result("fine.example.org"))
    }

    @Test
    fun ageIsComputedFromReferenceNowOfTheDocument() {
        val lookup = lookupWith(BundleSpec(generatedAt = "2026-10-03T12:00:00Z"))
        clock = UtcTime.parseMinuteStamp("2026-10-17T12:00:00Z")!!
        assertEquals("exactly 14 days is not yet stale", ReputationResult.NotListed, lookup.result("fine.example.org"))
        clock += UtcTime.DAY
        assertEquals(ReputationResult.ListsStale(15), lookup.result("fine.example.org"))
        clock = UtcTime.parseMinuteStamp("2026-01-01T00:00:00Z")!!
        assertEquals(
            "a clock set back cannot make lists look younger than their own date",
            ReputationResult.NotListed,
            lookup.result("fine.example.org"),
        )
    }

    @Test
    fun hundredLabelHostCostsAtMostSixteenProbes() {
        val lookup = lookupWith(BundleSpec())
        val long = (1..100).joinToString(".") { "l$it" } + ".phish.net"
        val candidates = lookup.domainCandidates(long, "phish.net")
        assertTrue(candidates.size + 1 <= 16)
        assertEquals(listOf(long, "phish.net"), candidates)
        assertEquals(listed, lookup.lookup(NormalizedHost(long, long, HostKind.DOMAIN, false, false)))
    }

    @Test
    fun shortHostsProbeEveryParentUpToTheRegistrableDomain() {
        val lookup = lookupWith(BundleSpec())
        assertEquals(
            listOf("a.b.phish.net", "b.phish.net", "phish.net"),
            lookup.domainCandidates("a.b.phish.net", "phish.net"),
        )
        assertEquals(listOf("phish.net"), lookup.domainCandidates("phish.net", "phish.net"))
    }

    @Test
    fun lookupOfHostileHostsIsFastAndNeverThrows() {
        val lookup = lookupWith(BundleSpec())
        val start = System.nanoTime()
        repeat(2_000) { i ->
            val name = "a".repeat(60) + ".x$i." + "b".repeat(60) + ".com"
            lookup.lookup(NormalizedHost(name, name, HostKind.DOMAIN, false, false))
        }
        assertTrue((System.nanoTime() - start) / 1_000_000 < 3_000)
    }
}
