package com.lookfirst.ui.screens.result

import com.lookfirst.domain.analysis.ok
import com.lookfirst.domain.model.ScanPayload
import org.junit.Assert.assertEquals
import org.junit.Test

class HostDisplayPlatformTest {
    private fun strongFrom(host: String): Int =
        (ok("https://$host/").payload as ScanPayload.Url).hostDisplay()!!.strongFrom

    @Test
    fun theTenantOfAnOpenPlatformIsTheStrongPartLikeOnBlogspot() {
        assertEquals(0, strongFrom("paypal-login.wordpress.com"))
        assertEquals(0, strongFrom("paypal.blogspot.com"))
        assertEquals("a.".length, strongFrom("a.paypal-login.wordpress.com"))
        assertEquals("a.b.".length, strongFrom("a.b.vps-1.vps.ovh.net"))
    }

    @Test
    fun ordinaryHostsAndOwnerHostsKeepTheRegistrableDomainStrong() {
        assertEquals("a.".length, strongFrom("a.example.com"))
        assertEquals(0, strongFrom("wordpress.com"))
        assertEquals("mobile.".length, strongFrom("mobile.free.fr"))
        assertEquals("www.".length, strongFrom("www.wordpress.com"))
    }
}
