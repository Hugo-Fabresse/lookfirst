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
        assertTrue(brandCodes("adsl.free.fr").isEmpty())
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
