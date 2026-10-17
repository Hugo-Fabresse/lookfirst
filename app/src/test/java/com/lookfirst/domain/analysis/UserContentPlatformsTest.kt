package com.lookfirst.domain.analysis

import com.lookfirst.domain.model.SignalCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserContentPlatformsTest {
    private fun brandCodes(host: String) = LookalikeAnalyzer.on("https://$host/").codes()

    @Test
    fun everyPlatformIsAnOfficialDomainOfABrand() {
        assertTrue(UserContentPlatforms.domains.isNotEmpty())
        for (platform in UserContentPlatforms.domains) assertTrue(platform, platform in BrandList.officialDomains)
    }

    @Test
    fun tenantsOfAnOpenPlatformAreJudgedLikeBlogspot() {
        for (platform in UserContentPlatforms.domains) {
            val brand = BrandList.brands.first { platform in it.domains }
            val host = "${brand.keywords.first().text}-login.$platform"
            assertTrue(host, SignalCode.BRAND_LOOKALIKE in brandCodes(host))
        }
        assertTrue(SignalCode.BRAND_LOOKALIKE in brandCodes("paypal-login.wordpress.com"))
        assertTrue(SignalCode.BRAND_LOOKALIKE in brandCodes("paypal.wordpress.com"))
        assertTrue(SignalCode.BRAND_LOOKALIKE in brandCodes("paypal.blogspot.com"))
        assertTrue(SignalCode.BRAND_LOOKALIKE in brandCodes("secure.paypal.free.fr"))
        assertTrue(SignalCode.BRAND_LOOKALIKE in brandCodes("impots-gouv.ovh.net"))
        assertTrue(SignalCode.BRAND_LOOKALIKE in brandCodes("microsoft-support.slack.com"))
    }

    @Test
    fun theOwnerHostAndWwwAreNeverReportedAndOrdinaryNamesAreNotEither() {
        for (platform in UserContentPlatforms.domains) {
            assertTrue(platform, brandCodes(platform).isEmpty())
            assertTrue(platform, brandCodes("www.$platform").isEmpty())
        }
        assertTrue(brandCodes("support.wordpress.com").isEmpty())
        assertTrue(brandCodes("mobile.free.fr").isEmpty())
    }

    @Test
    fun reservedServiceLabelsAndEverythingUnderThemAreTheOwners() {
        for (platform in UserContentPlatforms.all) {
            for (label in platform.reserved) {
                val host = "$label.${platform.domain}"
                assertTrue(host, UserContentPlatforms.isOwnerHost(host, platform.domain))
                assertTrue("a.b.$host", UserContentPlatforms.isOwnerHost("a.b.$host", platform.domain))
                assertEquals(host, platform.domain, UserContentPlatforms.registrableFor(host, platform.domain))
                assertTrue(host, brandCodes(host).isEmpty())
                assertTrue("paypal-login.$host", brandCodes("paypal-login.$host").isEmpty())
            }
        }
    }

    @Test
    fun ovhVpsIsASubSuffixAndNeverOneEntryForAllVps() {
        assertEquals("vps-1a.vps.ovh.net", UserContentPlatforms.registrableFor("vps-1a.vps.ovh.net", "ovh.net"))
        assertEquals("vps-1a.vps.ovh.net", UserContentPlatforms.registrableFor("x.vps-1a.vps.ovh.net", "ovh.net"))
        assertEquals("ovh.net", UserContentPlatforms.registrableFor("vps.ovh.net", "ovh.net"))
        assertEquals("x.ovh.net", UserContentPlatforms.registrableFor("x.ovh.net", "ovh.net"))
        assertTrue(brandCodes("vps.ovh.net").isEmpty())
        assertTrue(SignalCode.BRAND_LOOKALIKE in brandCodes("paypal-login.vps.ovh.net"))
    }

    @Test
    fun knownOpenPlatformsAreEitherNotOfficialDomainsOrListedHere() {
        // wetransfer.com is an official domain left out on purpose (paid plans only, see UserContentPlatforms).
        val known = listOf(
            "sharepoint.com", "onmicrosoft.com", "force.com", "my.salesforce.com", "zendesk.com", "atlassian.net",
            "weebly.com", "glitch.me", "typeform.com", "wordpress.com", "free.fr", "ovh.net",
            "slack.com", "myshopify.com", "squarespace.com", "wixsite.com", "webflow.io", "herokuapp.com",
        )
        for (d in known) {
            assertTrue(d, d !in BrandList.officialDomains || d in UserContentPlatforms.domains)
        }
    }

    @Test
    fun serviceSubdomainsOfOtherOfficialDomainsStayProtected() {
        for (host in listOf(
            "accounts.google.com",
            "login.microsoftonline.com",
            "paypal.google.com",
            "mail.google.com",
        )) {
            assertTrue(host, brandCodes(host).isEmpty())
        }
    }

    @Test
    fun registrableNameOfATenantIsPlatformPlusOneLabel() {
        assertEquals("x.wordpress.com", UserContentPlatforms.registrableFor("a.b.x.wordpress.com", "wordpress.com"))
        assertEquals("x.wordpress.com", UserContentPlatforms.registrableFor("x.wordpress.com", "wordpress.com"))
        assertEquals("wordpress.com", UserContentPlatforms.registrableFor("www.wordpress.com", "wordpress.com"))
        assertEquals("wordpress.com", UserContentPlatforms.registrableFor("wordpress.com", "wordpress.com"))
        assertEquals("google.com", UserContentPlatforms.registrableFor("accounts.google.com", "google.com"))
    }
}
