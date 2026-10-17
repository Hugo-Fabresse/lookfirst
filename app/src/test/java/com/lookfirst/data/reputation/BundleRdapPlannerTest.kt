package com.lookfirst.data.reputation

import com.lookfirst.domain.host.PublicSuffixList
import com.lookfirst.domain.rdap.RdapPlan
import com.lookfirst.domain.rdap.RdapServer
import com.lookfirst.domain.rdap.RdapUnavailable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BundleRdapPlannerTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val psl = PublicSuffixList.instance!!
    private val bootstrap = RdapBootstrap.parseStrict(
        (
            """{"services":[[["com","net","io"],["https://rdap.verisign.com/com/v1/"]],""" +
                """[["uk"],["https://rdap.nominet.uk/uk/"]]]}"""
            ).toByteArray(),
    )!!
    private val verisign = RdapServer("rdap.verisign.com", "/com/v1/")

    private fun plan(host: String) = BundleRdapPlanner.planIn(psl, bootstrap, host)

    private fun unavailable(host: String) = (plan(host) as RdapPlan.Unavailable).reason

    private fun ready(host: String) = (plan(host) as RdapPlan.Ready).target

    @Test
    fun `only the registrable domain is the target, never a path or a subdomain`() {
        val target = ready("login.secure.paypal.com")
        assertEquals("paypal.com", target.domain)
        assertEquals(verisign, target.server)
        assertEquals("bbc.co.uk", ready("news.bbc.co.uk").domain)
        assertEquals(RdapServer("rdap.nominet.uk", "/uk/"), ready("news.bbc.co.uk").server)
    }

    @Test
    fun `an internationalized name is asked for in punycode`() {
        assertEquals("xn--bcher-kva.com", ready("xn--bcher-kva.com").domain)
        assertEquals("xn--bcher-kva.com", ready("www.bücher.com").domain)
    }

    @Test
    fun `a name registered on a hosting platform is asked for at the platform's registry domain`() {
        assertEquals("github.io", ready("someone.github.io").domain)
        assertEquals("blogspot.com", ready("shop.blogspot.com").domain)
    }

    @Test
    fun `addresses and bare suffixes have no registrable domain`() {
        val hosts = listOf("93.184.216.34", "0x7f.1", "2130706433", "[::1]", "com", "co.uk", "", "a..com", "a_b.com")
        for (host in hosts) assertEquals(host, RdapUnavailable.NOT_A_DOMAIN, unavailable(host))
    }

    @Test
    fun `a tld with no server in the bootstrap has no registry to ask`() {
        assertEquals(RdapUnavailable.NO_SERVER, unavailable("example.org"))
        assertEquals(RdapUnavailable.NO_SERVER, unavailable("example.unknowntld"))
    }

    @Test
    fun `without an installed bundle there is no bootstrap`() {
        val store = ReputationStore(
            File(folder.root, "reputation"),
            null,
            { NOW },
            directorySync = {},
            acceptTestKeys = true,
        )
        val plan = BundleRdapPlanner(store).plan("example.com")
        assertEquals(RdapPlan.Unavailable(RdapUnavailable.NO_BOOTSTRAP), plan)
    }

    @Test
    fun `an installed bundle's bootstrap names the server, and one without a bootstrap file names none`() {
        val store = ReputationStore(
            File(folder.root, "reputation"),
            null,
            { NOW },
            directorySync = {},
            acceptTestKeys = true,
        )
        val bare = BundleBuilder.build(store.newCandidateDirectory(), BundleSpec(generatedAt = "2026-10-10T03:12:00Z"))
        assertTrue(store.install(bare.directory) is InstallResult.Installed)
        val planner = BundleRdapPlanner(store)
        assertEquals(RdapPlan.Unavailable(RdapUnavailable.NO_BOOTSTRAP), planner.plan("example.com"))

        val rdap = """{"services":[[["com"],["https://rdap.verisign.com/com/v1/"]]]}"""
        val spec = BundleSpec(generatedAt = "2026-10-12T03:12:00Z", rdapJson = rdap)
        val full = BundleBuilder.build(store.newCandidateDirectory(), spec)
        assertTrue(store.install(full.directory) is InstallResult.Installed)
        val ready = BundleRdapPlanner(store).plan("www.example.com") as RdapPlan.Ready
        assertEquals("example.com", ready.target.domain)
        assertEquals(verisign, ready.target.server)
    }
}
