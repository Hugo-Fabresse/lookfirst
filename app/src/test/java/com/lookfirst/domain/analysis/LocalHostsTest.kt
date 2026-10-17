package com.lookfirst.domain.analysis

import com.lookfirst.domain.model.Severity
import com.lookfirst.domain.model.SignalCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalHostsTest {
    private fun local(host: String) = SignalCode.LOCAL_HOST in UrlStructureAnalyzer.on("https://$host/x").codes()

    @Test
    fun localhostIsNoLongerWithoutSignal() {
        val signals = UrlStructureAnalyzer.on("https://localhost/x")
        assertEquals(Severity.SUSPICIOUS, signals.of(SignalCode.LOCAL_HOST).severity)
        assertEquals("localhost", signals.of(SignalCode.LOCAL_HOST).evidence?.value)
    }

    @Test
    fun localAndReservedNamesAreFlagged() {
        val hosts =
            listOf(
                "localhost", "app.localhost", "router", "fritz.box", "nas.local", "x.home.arpa", "site.internal",
                "printer.lan", "wiki.intranet", "srv.corp", "tv.home", "a.test", "a.example", "a.invalid",
                "a.notatld", "ROUTER", "nas.local.", "a.b.c.internal",
            )
        val missed = hosts.filterNot { local(it) }
        assertTrue("not flagged: $missed", missed.isEmpty())
    }

    @Test
    fun oneSignalWhenSeveralRulesOverlap() {
        val signals = UrlStructureAnalyzer.on("https://x.internal/")
        assertEquals(1, signals.count { it.code == SignalCode.LOCAL_HOST })
    }

    @Test
    fun unknownIdnTldIsFlaggedButARealOneIsNot() {
        assertTrue(local("пример.нетакоготлд"))
        assertFalse(local("xn--bcher-kva.example.xn--p1ai"))
        assertFalse(local("bücher.de"))
        assertFalse(local("пример.рф"))
    }

    @Test
    fun numericHostsKeepOnlyIpHost() {
        val codes = UrlStructureAnalyzer.on("https://192.168.1.1/").codes()
        assertTrue(SignalCode.IP_HOST in codes)
        assertFalse(SignalCode.LOCAL_HOST in codes)
        assertFalse(SignalCode.LOCAL_HOST in UrlStructureAnalyzer.on("https://[::1]/").codes())
    }

    @Test
    fun suffixLookalikesAreNotMatchedAsSuffix() {
        assertFalse(local("notlocal.com"))
        assertFalse(local("internal.fr"))
        assertFalse(local("lan.com"))
        assertFalse(local("box.com"))
        assertFalse(local("testing.org"))
    }

    @Test
    fun legitimateHostsAreNotFlagged() {
        val hosts =
            """
            example.com example.org example.net www.google.com paypal.com www.paypal.com impots.gouv.fr ameli.fr
            laposte.fr lemonde.fr orange.fr www.service-public.fr amazon.fr amazon.co.uk github.com github.io
            user.github.io foo.blogspot.com bank.bzh www.bretagne.bzh mairie.paris monsite.paris nic.bzh
            shop.shopify.com sncf-connect.com oui.sncf bbc.co.uk gov.uk europa.eu bund.de admin.ch vlaanderen.be
            wikipedia.org mozilla.org apple.com microsoft.com netflix.com spotify.com ovh.net free.fr sfr.fr
            doctolib.fr ouest-france.fr leboncoin.fr vinted.fr mobile.de nhs.uk gouv.fr cloudflare.com
            university.edu nasa.gov www.canada.ca example.co.jp tokyo.jp mairie.alsace shop.app dev.page
            my.cloud store.shop bit.ly t.co
            """.trimIndent().split(Regex("\\s+"))
                .filter { it.isNotEmpty() }
        val flagged = hosts.filter { local(it) }
        assertTrue("false positives: $flagged", flagged.isEmpty())
    }
}
