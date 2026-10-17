package com.lookfirst.domain.analysis

import com.lookfirst.domain.model.LookalikeKind
import com.lookfirst.domain.model.SignalCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** French fraud patterns and a corpus of legitimate hosts (false-positive rate must stay at zero). */
class BrandFraudCorpusTest {
    private fun brandCodes(host: String) = LookalikeAnalyzer.on("https://$host/").codes()

    private val fraud =
        listOf(
            "laposte-colis.com", "colis-laposte.info", "la-poste.fr", "mon-ameli.com", "ameli-fr.com",
            "ameli-assure.fr", "caf-aide.fr", "ants-gouv.fr", "antai-gouv.fr", "chronopost-livraison.com",
            "dhl-delivery.com", "ups-tracking.com", "credit-agricole-connexion.com", "societegenerale-alerte.com",
            "boursorama-banque.com", "doctolib-rdv.com", "apple-id.com", "appleid-verify.com",
            "steam-community.com", "microsoftonline-login.com", "stationnement-amendes.fr",
            "paypal-donations.example", "impots.gouv.fr.ma", "ameli.fr.ru", "paypal.com.cn", "ameli.info",
            "ameli.top", "impots.info", "colissimo.xyz",
        )

    @Test
    fun frenchFraudPatternsAreReported() {
        for (host in fraud) assertTrue(host, SignalCode.BRAND_LOOKALIKE in brandCodes(host))
    }

    @Test
    fun officialDomainsWithAnExtraSuffixKnownToBeLegitimateAreFine() {
        for (host in listOf("google.com.br", "amazon.com.br", "google.de", "amazon.co.uk", "paypal.com.au")) {
            assertTrue(host, brandCodes(host).isEmpty())
        }
    }

    @Test
    fun everyOfficialDomainAndKeywordIsClean() {
        for (brand in BrandList.brands) {
            for (domain in brand.domains) {
                // Tenants of an open platform (wordpress.com) are registrable names: see UserContentPlatforms.
                val hosts = if (domain in UserContentPlatforms.domains) {
                    listOf(domain, "www.$domain")
                } else {
                    listOf(domain, "www.$domain", "login.$domain") + brand.keywords.map { "${it.text}.$domain" }
                }
                for (host in hosts) assertTrue(host, brandCodes(host).isEmpty())
            }
        }
    }

    @Test
    fun brandsOneEditFromACommonWordAreNotMatchedByTypo() {
        val hosts = listOf(
            "switch.com", "interest.com", "finance.com", "discount.com", "general.com", "revolt.com",
            "strip.com", "telnet.com", "citron.com", "amazone.com", "amazom.com",
        )
        for (host in hosts) assertTrue(host, SignalCode.BRAND_LOOKALIKE !in brandCodes(host))
    }

    @Test
    fun shortBrandTypoNeedsAHint() {
        fun kind(host: String) = LookalikeAnalyzer.on("https://$host/").of(SignalCode.BRAND_LOOKALIKE).details.single()
        assertTrue(SignalCode.BRAND_LOOKALIKE !in brandCodes("netfiix.com"))
        assertEquals(LookalikeKind.TYPO, kind("netfiix.xyz"))
        assertTrue(SignalCode.BRAND_LOOKALIKE in brandCodes("microsofft.com")) // long name: no hint needed
        assertTrue(SignalCode.BRAND_LOOKALIKE in brandCodes("paypall.com")) // top target: always
    }

    @Test
    fun cyrillicLookalikesOfLatinWordsAreCaughtOnWholeScript() {
        for (label in listOf("сосо", "ехро", "ѕсоре")) {
            val signals = LookalikeAnalyzer.on("https://$label.com/")
            assertTrue(label, SignalCode.WHOLE_SCRIPT_CONFUSABLE in signals.codes())
            assertTrue(label, SignalCode.MIXED_SCRIPT !in signals.codes())
        }
        assertTrue(brandCodes("пример.рф").isEmpty())
        assertTrue(brandCodes("пример.com").isEmpty())
        assertTrue(brandCodes("日本語.com").isEmpty())
    }

    private val legitimate =
        """
        google.com youtube.com facebook.com baidu.com wikipedia.org twitter.com instagram.com linkedin.com
        netflix.com microsoft.com apple.com amazon.com yahoo.com reddit.com whatsapp.com zoom.us bing.com live.com
        office.com cloudflare.com github.com stackoverflow.com medium.com nytimes.com bbc.co.uk cnn.com theguardian.com
        forbes.com bloomberg.com reuters.com washingtonpost.com wsj.com weather.com imdb.com twitch.tv spotify.com
        paypal.com ebay.com etsy.com walmart.com target.com bestbuy.com homedepot.com ikea.com booking.com airbnb.com
        tripadvisor.com expedia.com kayak.com uber.com lyft.com doordash.com adobe.com salesforce.com oracle.com
        ibm.com intel.com nvidia.com amd.com samsung.com sony.com mozilla.org python.org kotlinlang.org
        developer.android.com gradle.org jetbrains.com npmjs.com docker.com wordpress.org tumblr.com pinterest.com
        quora.com vimeo.com dropbox.com notion.so slack.com trello.com atlassian.com docs.google.com mail.google.com
        accounts.google.com store.steampowered.com aws.amazon.com www.amazon.de www.google.fr support.apple.com
        login.microsoftonline.com outlook.office.com apple.stackexchange.com security.stackexchange.com
        lemonde.fr lefigaro.fr francetvinfo.fr ouest-france.fr 20minutes.fr leparisien.fr liberation.fr lequipe.fr
        bfmtv.com franceinter.fr radiofrance.fr tf1.fr france.tv arte.tv allocine.fr marmiton.org doctissimo.fr
        seloger.com pagesjaunes.fr meteofrance.com mappy.com cdiscount.com fnac.com darty.com boulanger.com
        leroymerlin.fr decathlon.fr carrefour.fr auchan.fr lidl.fr vinted.fr leboncoin.fr blablacar.fr
        sncf-connect.com oui.sncf airfrance.fr ratp.fr orange.fr sfr.fr bouyguestelecom.fr free.fr sosh.fr
        laposte.fr colissimo.fr chronopost.fr mondialrelay.fr service-public.fr www.service-public.gouv.fr
        impots.gouv.fr www.impots.gouv.fr ameli.fr assure.ameli.fr caf.fr mon-compte.caf.fr ants.gouv.fr
        francetravail.fr candidat.francetravail.fr urssaf.fr gouvernement.fr education.gouv.fr interieur.gouv.fr
        justice.gouv.fr legifrance.gouv.fr data.gouv.fr elysee.fr assemblee-nationale.fr senat.fr cnil.fr ademe.fr
        inpi.fr insee.fr pole-emploi.fr mesdroitssociaux.gouv.fr amendes.gouv.fr antai.gouv.fr
        bnpparibas.fr mabanque.bnpparibas societegenerale.fr particuliers.societegenerale.fr credit-agricole.fr
        www.credit-agricole.fr labanquepostale.fr lcl.fr creditmutuel.fr cic.fr boursorama.com hellobank.fr
        fortuneo.fr ing.fr caisse-epargne.fr banquepopulaire.fr ca-paris.fr bnpparibasfortis.be abnamro.nl
        deutsche-bank.de sparkasse.de barclays.co.uk hsbc.co.uk lloydsbank.com monzo.com revolut.com wise.com
        stripe.com shopify.com godaddy.com ovhcloud.com gandi.net ionos.fr proton.me doctolib.fr
        ameli-sante.example.org cafe-de-paris.fr applepie.example pommes-apple.example orangerie.fr
        support.wordpress.com developer.wordpress.com www.wordpress.com adsl.free.fr assistance.free.fr www.free.fr
        www.slack.com status.slack.com api.slack.com www.ovh.net
        """.trimIndent().split(Regex("\\s+")).filter {
            it.isNotEmpty()
        }

    @Test
    fun legitimateCorpusHasNoBrandSignal() {
        assertTrue("corpus too small: ${legitimate.size}", legitimate.size >= 208)
        val flagged = legitimate.filter { brandCodes(it).isNotEmpty() }
        assertTrue("false positives ${flagged.size}/${legitimate.size}: $flagged", flagged.isEmpty())
    }
}
