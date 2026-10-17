package com.lookfirst.data.reputation

import com.lookfirst.domain.analysis.BrandList
import com.lookfirst.domain.analysis.ReputationResult
import com.lookfirst.domain.analysis.UserContentPlatforms
import com.lookfirst.domain.host.HostNormalizer
import com.lookfirst.domain.host.HostResult
import com.lookfirst.domain.host.NormalizedHost
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

    private var clock = TEST_NOW
    private val cert = testSource(id = SourceIds.CERT_PL, minEntries = 1)
    private val other = testSource(id = OTHER_SOURCE_ID, minEntries = 1)

    private fun host(text: String): NormalizedHost = (HostNormalizer.normalize(text) as HostResult.Normalized).host

    private fun lookupWith(
        entries: Parsed? = null,
        officials: Set<String> = setOf("good.com"),
        second: Parsed? = null,
    ): LocalReputationLookup {
        val store = ListStore(File(folder.root, "rep"), listOf(cert, other), { clock }, {})
        if (entries != null) assertTrue(store.install(cert, entries, null) is ListInstallResult.Installed)
        if (second != null) assertTrue(store.install(other, second, null) is ListInstallResult.Installed)
        return LocalReputationLookup(store, officials) { TEST_PSL }
    }

    private fun LocalReputationLookup.result(text: String) = lookup(host(text))

    private val listed = ReputationResult.Listed("Test list ${SourceIds.CERT_PL}")
    private val standard get() = keyed("h:evil.example.com", "d:phish.net", "d:bad.blogspot.com")

    @Test
    fun noListMeansListsMissing() {
        assertEquals(ReputationResult.ListsMissing, lookupWith(null).result("evil.example.com"))
    }

    @Test
    fun exactHostEntryIsListedButNotItsSiblings() {
        val lookup = lookupWith(standard)
        assertEquals(listed, lookup.result("evil.example.com"))
        assertEquals(ReputationResult.NotListed, lookup.result("other.example.com"))
        assertEquals(ReputationResult.NotListed, lookup.result("deep.evil.example.com"))
        assertEquals(ReputationResult.NotListed, lookup.result("example.com"))
    }

    @Test
    fun domainEntryCoversTheDomainAndEverySubdomain() {
        val lookup = lookupWith(standard)
        assertEquals(listed, lookup.result("phish.net"))
        assertEquals(listed, lookup.result("www.phish.net"))
        assertEquals(listed, lookup.result("a.b.c.phish.net"))
        assertEquals(ReputationResult.NotListed, lookup.result("notphish.net"))
        assertEquals(ReputationResult.NotListed, lookup.result("phish.net.evil.org"))
        assertEquals(ReputationResult.NotListed, lookup.result("net"))
    }

    @Test
    fun privateSuffixSectionDecidesWhatIsRegistrable() {
        val lookup = lookupWith(standard)
        assertEquals(listed, lookup.result("bad.blogspot.com"))
        assertEquals(listed, lookup.result("x.bad.blogspot.com"))
        assertEquals(ReputationResult.NotListed, lookup.result("good2.blogspot.com"))
        assertEquals(ReputationResult.NotListed, lookup.result("blogspot.com"))
    }

    @Test
    fun theSuffixItselfIsNeverProbedAsADomain() {
        val lookup = lookupWith(keyed("d:co.uk", "d:uk"))
        assertEquals(ReputationResult.NotListed, lookup.result("anything.co.uk"))
        assertEquals(ReputationResult.NotListed, lookup.result("anything.uk"))
    }

    @Test
    fun theEmbeddedAllowlistIsConsultedFirstForTheDomainAndEverythingUnderIt() {
        // The list holds good.com in every shape (a poisoned list or a hash collision): the allow-list wins.
        val lookup = lookupWith(
            keyed("d:good.com", "h:good.com", "h:www.good.com", "h:cdn.good.com", "d:other.good.com"),
        )
        assertEquals(ReputationResult.NotListed, lookup.result("good.com"))
        assertEquals(ReputationResult.NotListed, lookup.result("www.good.com"))
        assertEquals(ReputationResult.NotListed, lookup.result("x.other.good.com"))
        assertEquals(ReputationResult.NotListed, lookup.result("cdn.good.com"))
    }

    @Test
    fun tenantsOfAnOpenPlatformAreLookedUpByLabelAndPlatformButTheOwnerIsNot() {
        val lookup = lookupWith(
            keyed(
                "d:office365emailverification1.wordpress.com",
                "d:officeemailverification3653.wordpress.com",
                "d:wordpress.com",
                "h:www.wordpress.com",
                "d:accounts.google.com",
            ),
            officials = BrandList.officialDomains,
        )
        assertEquals(listed, lookup.result("office365emailverification1.wordpress.com"))
        assertEquals(listed, lookup.result("x.y.officeemailverification3653.wordpress.com"))
        assertEquals(ReputationResult.NotListed, lookup.result("other.wordpress.com"))
        assertEquals(ReputationResult.NotListed, lookup.result("wordpress.com"))
        assertEquals(ReputationResult.NotListed, lookup.result("www.wordpress.com"))
        assertEquals(ReputationResult.NotListed, lookup.result("accounts.google.com"))
    }

    @Test
    fun reservedServiceLabelsOfAPlatformGoThroughTheParserAndTheLookupAsOfficial() {
        for (platform in UserContentPlatforms.all) {
            for (label in platform.reserved) {
                val host = "$label.${platform.domain}"
                val deep = "x.$host"
                val out = parsed(host, deep, "ok.com", officials = BrandList.officialDomains)
                assertEquals(host, 2, out.droppedOfficial)
                assertEquals(host, 1, out.keys.size)
                val lookup = lookupWith(
                    keyed("d:$host", "h:$deep", "d:${platform.domain}"),
                    officials = BrandList.officialDomains,
                )
                assertEquals(host, ReputationResult.NotListed, lookup.result(host))
                assertEquals(deep, ReputationResult.NotListed, lookup.result(deep))
            }
        }
    }

    @Test
    fun anOvhVpsEntryDoesNotCoverOtherVps() {
        val out = parsed("vps-1a.vps.ovh.net", "vps.ovh.net", officials = BrandList.officialDomains)
        assertEquals(1, out.droppedOfficial) // vps.ovh.net is the owner's sub-suffix, never indexed
        val lookup = lookupWith(keyed("d:vps-1a.vps.ovh.net"), officials = BrandList.officialDomains)
        assertEquals(listed, lookup.result("vps-1a.vps.ovh.net"))
        assertEquals(ReputationResult.NotListed, lookup.result("vps-2b.vps.ovh.net"))
        assertEquals(ReputationResult.NotListed, lookup.result("vps.ovh.net"))
    }

    @Test
    fun withoutThePublicSuffixListNothingIsListedAndTheAnswerIsListsMissing() {
        val store = ListStore(File(folder.root, "rep"), listOf(cert), { clock }, {})
        store.install(cert, standard, null)
        val lookup = LocalReputationLookup(store, emptySet()) { null }
        assertEquals(ReputationResult.ListsMissing, lookup.result("evil.example.com"))
        assertEquals(ReputationResult.ListsMissing, lookup.result("phish.net"))
    }

    @Test
    fun aListCanOnlyAddAWarningNeverRemoveOne() {
        // With no list, an official domain is just "lists missing": the lookup never produces a clean verdict.
        assertEquals(ReputationResult.ListsMissing, lookupWith(null).result("good.com"))
    }

    @Test
    fun ipAddressesAreOnlyLookedUpAsHosts() {
        val lookup = lookupWith(keyed("h:192.0.2.7", "d:0.2.7"))
        assertEquals(listed, lookup.result("192.0.2.7"))
        assertEquals(ReputationResult.NotListed, lookup.result("192.0.2.8"))
    }

    @Test
    fun idnHostsAreLookedUpInTheirAsciiForm() {
        val lookup = lookupWith(keyed("d:xn--bcher-kva.com"))
        assertEquals(listed, lookup.result("bücher.com"))
        assertEquals(listed, lookup.result("www.xn--bcher-kva.com"))
    }

    @Test
    fun anySourceThatListsAHostWins() {
        val lookup = lookupWith(standard, second = keyed("d:only-in-second.com"))
        val inSecond = ReputationResult.Listed("Test list ${OTHER_SOURCE_ID}")
        assertEquals(inSecond, lookup.result("only-in-second.com"))
        assertEquals(listed, lookup.result("phish.net"))
    }

    @Test
    fun listedWinsOverStaleButStaleListsReplaceNotListed() {
        val lookup = lookupWith(standard)
        clock = TEST_NOW + 20 * TEST_DAY
        assertEquals(listed, lookup.result("evil.example.com"))
        assertEquals(ReputationResult.ListsStale(20), lookup.result("fine.example.org"))
        clock = TEST_NOW + 3 * TEST_DAY
        assertEquals(ReputationResult.NotListed, lookup.result("fine.example.org"))
    }

    @Test
    fun exactlyFourteenDaysIsNotYetStaleAndAClockSetBackDoesNotRejuvenate() {
        val lookup = lookupWith(standard)
        clock = TEST_NOW + 14 * TEST_DAY
        assertEquals(ReputationResult.NotListed, lookup.result("fine.example.org"))
        clock += TEST_DAY
        assertEquals(ReputationResult.ListsStale(15), lookup.result("fine.example.org"))
        clock = TEST_NOW - 400 * TEST_DAY
        assertEquals(ReputationResult.NotListed, lookup.result("fine.example.org"))
    }

    @Test
    fun hundredLabelHostCostsAtMostSixteenProbes() {
        val lookup = lookupWith(standard)
        val long = (1..100).joinToString(".") { "l$it" } + ".phish.net"
        val candidates = lookup.domainCandidates(long, "phish.net")
        assertTrue(candidates.size + 1 <= 16)
        assertEquals(listOf(long, "phish.net"), candidates)
        assertEquals(listed, lookup.lookup(NormalizedHost(long, long, HostKind.DOMAIN, false, false)))
    }

    @Test
    fun shortHostsProbeEveryParentUpToTheRegistrableDomain() {
        val lookup = lookupWith(standard)
        assertEquals(
            listOf("a.b.phish.net", "b.phish.net", "phish.net"),
            lookup.domainCandidates("a.b.phish.net", "phish.net"),
        )
        assertEquals(listOf("phish.net"), lookup.domainCandidates("phish.net", "phish.net"))
    }

    @Test
    fun lookupOfHostileHostsIsFastAndNeverThrows() {
        val lookup = lookupWith(standard)
        val start = System.nanoTime()
        repeat(2_000) { i ->
            val name = "a".repeat(60) + ".x$i." + "b".repeat(60) + ".com"
            lookup.lookup(NormalizedHost(name, name, HostKind.DOMAIN, false, false))
        }
        assertTrue((System.nanoTime() - start) / 1_000_000 < 3_000)
    }
}

private typealias Parsed = ParseOutcome.Parsed
