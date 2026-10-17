package com.lookfirst.domain.analysis

import com.lookfirst.domain.host.Confusables

/**
 * A brand name people type or look for ([text], lowercase ASCII). [generic]: also an everyday word, so it never
 * matches alone as a sub-domain or in a name without a lure word. [noTypo]: one edit away from a common word
 * (twitch / switch), so only look-alike characters count. [alwaysTypo]: a top target, a small typo is enough even
 * for a short name.
 */
internal class BrandKeyword(
    val text: String,
    val generic: Boolean,
    val noTypo: Boolean = false,
    val alwaysTypo: Boolean = false,
) {
    /** UTS #39 skeleton of [text], compared with the skeleton of a host label. */
    val skeleton: String by lazy { Confusables.skeleton(text) ?: text }
}

/**
 * [domains] are the official registrable domains; the first one is what the user is shown. A [strict] brand (French
 * public services, banks, carriers) is reported on every TLD that is not an official domain.
 */
internal class Brand(val keywords: List<BrandKeyword>, val domains: List<String>, val strict: Boolean = false) {
    val primaryDomain: String get() = domains.first()

    /** Names that are fine on an unlisted TLD for a non-strict brand: its keywords and the names of its domains. */
    val ownNames: Set<String> by lazy { keywords.map { it.text }.toSet() + domains.map { it.substringBefore('.') } }
}

/**
 * Embedded list of brands that phishing imitates most: worldwide services, banks, carriers, telecom operators and
 * public services of France and the EU. Local, no network. One line per brand:
 * `[!]keyword1/keyword2 domain1 domain2` (any number of each). Prefixes: `!` on the line = strict brand; on a
 * keyword `~` everyday word, `^` no typo rule, `+` typo rule even for a short name. An exact name on another
 * TLD is NOT flagged for a non-strict brand (google.de is fine); that leaves a residual risk (paypal.xyz), covered
 * later by reputation lists. The list is refreshed with the reputation bundle later.
 */
internal object BrandList {
    val brands: List<Brand> by lazy { parse(DATA) }

    /** Every official registrable domain: a host that ends in one of them is never reported as an imitation. */
    val officialDomains: Set<String> by lazy { brands.flatMap { it.domains }.toSet() }

    internal fun parse(data: String): List<Brand> = data.lines().mapNotNull { line ->
        val parts = line.trim().split(' ').filter { it.isNotEmpty() }
        if (parts.size < 2 || parts[0].startsWith("#")) return@mapNotNull null
        val strict = parts[0].startsWith("!")
        val keywords = parts[0].removePrefix("!").split('/').map { raw ->
            BrandKeyword(
                raw.trimStart('~', '^', '+'),
                '~' in raw.takeWhile { !it.isLetterOrDigit() },
                '^' in raw.takeWhile { !it.isLetterOrDigit() },
                '+' in raw.takeWhile { !it.isLetterOrDigit() },
            )
        }
        Brand(keywords, parts.drop(1), strict)
    }

    private val DATA =
        """
        # Web and tech
        +google/gmail google.com gmail.com google.com.br google.com.au google.com.mx google.com.tr google.com.ar google.com.sg google.com.hk google.com.tw google.com.ua google.com.eg google.com.sa google.com.pk google.com.ph google.com.my google.com.co google.com.pe google.com.vn google.com.ng
        youtube youtube.com youtu.be
        microsoft/outlook/office365/onedrive/microsoftonline microsoft.com live.com office.com office365.com onedrive.com microsoftonline.com outlook.com
        ~apple/icloud/appleid apple.com icloud.com apple.com.cn
        facebook facebook.com fb.com messenger.com
        ~meta meta.com
        instagram instagram.com
        whatsapp whatsapp.com wa.me
        twitter twitter.com x.com
        linkedin linkedin.com lnkd.in
        tiktok tiktok.com
        snapchat snapchat.com
        telegram telegram.org t.me telegram.me
        discord discord.com discord.gg
        reddit reddit.com
        ^pinterest pinterest.com
        ^amazon amazon.com amazon.fr amazon.de amazon.co.uk amazon.es amazon.it amazon.ca amzn.eu amazon.com.br amazon.com.mx amazon.com.au amazon.com.tr amazon.com.sg amazon.co.jp amazon.in
        ebay ebay.com ebay.fr ebay.de ebay.co.uk ebay.com.au ebay.com.sg ebay.com.my
        aliexpress aliexpress.com
        alibaba alibaba.com
        temu temu.com
        shein shein.com shein.fr
        netflix netflix.com
        spotify spotify.com
        disneyplus/disney disneyplus.com disney.com
        ^twitch twitch.tv
        ~steam/steamcommunity steampowered.com steamcommunity.com
        epicgames epicgames.com
        playstation playstation.com
        xbox xbox.com
        nintendo nintendo.com
        roblox roblox.com
        dropbox dropbox.com
        github github.com
        gitlab gitlab.com
        adobe adobe.com
        ~zoom zoom.us
        slack slack.com
        skype skype.com
        yahoo yahoo.com yahoo.fr
        protonmail/proton proton.me protonmail.com
        docusign docusign.com docusign.net
        wetransfer wetransfer.com we.tl
        shopify shopify.com
        wordpress wordpress.com wordpress.org
        godaddy godaddy.com
        ovh/ovhcloud ovhcloud.com ovh.com ovh.net
        gandi gandi.net
        cloudflare cloudflare.com
        ionos ionos.com ionos.fr ionos.de
        deezer deezer.com
        ~canal/canalplus canalplus.com mycanal.fr
        # Security and password managers
        norton norton.com
        mcafee mcafee.com
        kaspersky kaspersky.com
        avast avast.com
        bitdefender bitdefender.com
        nordvpn nordvpn.com
        expressvpn expressvpn.com
        lastpass lastpass.com
        bitwarden bitwarden.com
        1password 1password.com
        dashlane dashlane.com
        # Payment, fintech and crypto
        +paypal paypal.com paypal.me paypal.com.br paypal.com.au paypal.com.mx paypal.com.ar paypal.com.hk paypal.com.sg
        ^binance binance.com
        coinbase coinbase.com
        kraken kraken.com
        metamask metamask.io
        ~ledger ledger.com
        trezor trezor.io
        ~blockchain blockchain.com
        bitpanda bitpanda.com
        cryptocom crypto.com
        trustwallet trustwallet.com
        uniswap uniswap.org
        opensea opensea.io
        bybit bybit.com
        okx okx.com
        kucoin kucoin.com
        ^revolut revolut.com
        ~wise wise.com
        n26 n26.com
        ^stripe stripe.com
        ~visa visa.com
        mastercard mastercard.com
        americanexpress americanexpress.com
        westernunion westernunion.com
        moneygram moneygram.com
        cashapp cash.app cashapp.com
        venmo venmo.com
        zelle zellepay.com
        qonto qonto.com
        lydia lydia-app.com lydia.me
        paylib paylib.fr
        # Carriers and postal services
        fedex fedex.com
        ~ups ups.com
        dhl dhl.com dhl.de
        usps usps.com
        royalmail royalmail.com
        !colissimo/laposte colissimo.fr laposte.fr
        !chronopost chronopost.fr
        !mondialrelay mondialrelay.fr
        gls gls-group.com
        dpd dpd.com dpd.fr
        tnt tnt.com
        evri evri.com
        correos correos.es
        posteitaliane poste.it posteitaliane.it
        deutschepost deutschepost.de
        postnl postnl.nl
        bpost bpost.be
        swisspost post.ch swisspost.ch
        # Retail, travel, mobility, food, cars
        ikea ikea.com ikea.fr
        walmart walmart.com
        costco costco.com
        ~target target.com
        bestbuy bestbuy.com
        lidl lidl.fr lidl.com
        carrefour carrefour.fr carrefour.com
        leclerc e.leclerc
        auchan auchan.fr
        intermarche intermarche.com
        fnac fnac.com
        darty darty.com
        decathlon decathlon.fr decathlon.com
        leroymerlin leroymerlin.fr
        boulanger boulanger.com
        ^cdiscount cdiscount.com
        vinted vinted.fr vinted.com
        leboncoin leboncoin.fr
        zalando zalando.fr zalando.com
        asos asos.com
        kiabi kiabi.com
        mediamarkt mediamarkt.de
        booking booking.com
        airbnb airbnb.com airbnb.fr
        expedia expedia.com
        tripadvisor tripadvisor.com tripadvisor.fr
        trivago trivago.com
        uber uber.com
        ~bolt bolt.eu
        blablacar blablacar.fr blablacar.com
        airfrance airfrance.fr airfrance.com
        klm klm.com
        lufthansa lufthansa.com
        ryanair ryanair.com
        easyjet easyjet.com
        sncf sncf.com sncf-connect.com oui.sncf sncf.fr
        ouigo ouigo.com
        eurostar eurostar.com
        ratp ratp.fr
        flixbus flixbus.com flixbus.fr
        trainline trainline.com trainline.fr
        ubereats ubereats.com
        deliveroo deliveroo.fr deliveroo.com
        justeat just-eat.fr
        mcdonalds mcdonalds.com mcdonalds.fr
        starbucks starbucks.com
        burgerking burgerking.com burgerking.fr
        tesla tesla.com
        renault renault.fr renault.com
        peugeot peugeot.fr
        ^citroen citroen.fr
        paybyphone paybyphone.com paybyphone.fr
        easypark easypark.com easypark.fr
        bipandgo bipandgo.com
        ulys ulys.com
        sanef sanef.com
        vinci-autoroutes vinci-autoroutes.com
        # Telecom operators
        ~orange orange.fr orange.com
        sfr sfr.fr
        bouygues/bouyguestelecom bouyguestelecom.fr bouygues.com
        ~free free.fr
        sosh sosh.fr
        vodafone vodafone.com vodafone.fr vodafone.de vodafone.co.uk
        telekom telekom.de
        tmobile t-mobile.com tmobile.com t-mobile.de
        ~att att.com
        verizon verizon.com
        xfinity/comcast xfinity.com comcast.com
        movistar movistar.es
        telefonica telefonica.com
        proximus proximus.be
        swisscom swisscom.ch
        ~tim tim.it
        ^telenet telenet.be
        ziggo ziggo.nl
        # Utilities and insurance
        totalenergies totalenergies.fr totalenergies.com
        edf edf.fr
        engie engie.fr
        enedis enedis.fr
        veolia veolia.fr
        axa axa.fr axa.com
        allianz allianz.fr allianz.com
        ^generali generali.fr
        maif maif.fr
        macif macif.fr
        matmut matmut.fr
        groupama groupama.fr
        # Banks, France
        !bnpparibas bnpparibas.com bnpparibas.fr mabanque.bnpparibas
        !societegenerale societegenerale.fr societegenerale.com
        !creditagricole/credit-agricole credit-agricole.fr credit-agricole.com
        !caisse-epargne/caisseepargne caisse-epargne.fr caisseepargne.fr
        !labanquepostale labanquepostale.fr
        !~lcl lcl.fr
        !creditmutuel creditmutuel.fr
        !cic cic.fr
        !boursorama/boursobank boursorama.com boursobank.com
        !hellobank hellobank.fr
        ~ing ing.fr ing.com ing.de
        !fortuneo fortuneo.fr
        !monabanq monabanq.com
        !banquepopulaire banquepopulaire.fr
        !bred bred.fr
        !creditdunord creditdunord.fr
        !cetelem cetelem.fr
        !cofidis cofidis.fr
        # Banks, Europe, UK, US
        santander santander.com santander.es santander.co.uk santander.com.br santander.com.mx santander.com.ar
        bbva bbva.es bbva.com
        caixabank caixabank.es
        sabadell bancsabadell.com
        unicredit unicredit.it
        intesasanpaolo intesasanpaolo.com
        postepay postepay.it
        deutschebank/deutsche-bank deutsche-bank.de deutschebank.de db.com
        commerzbank commerzbank.de
        sparkasse sparkasse.de
        postbank postbank.de
        abnamro abnamro.nl
        rabobank rabobank.nl
        belfius belfius.be
        kbc kbc.be kbc.com
        bnpparibasfortis bnpparibasfortis.be
        ubs ubs.com
        postfinance postfinance.ch
        raiffeisen raiffeisen.ch raiffeisen.at
        barclays barclays.co.uk barclays.com
        hsbc hsbc.com hsbc.co.uk hsbc.fr hsbc.com.hk hsbc.com.cn hsbc.com.sg hsbc.com.mx hsbc.com.au
        lloydsbank lloydsbank.com lloydsbank.co.uk
        natwest natwest.com
        ~halifax halifax.co.uk
        monzo monzo.com
        ~chase chase.com
        bankofamerica bankofamerica.com
        wellsfargo wellsfargo.com
        ~citi citi.com citibank.com
        capitalone capitalone.com
        usbank usbank.com
        tdbank td.com tdbank.com
        # Public services (France, UK, US, DE, IT)
        !impots-gouv/impotsgouv/~impots impots.gouv.fr
        !service-public/servicepublic service-public.fr service-public.gouv.fr
        !ameli ameli.fr
        !~caf caf.fr
        !franceconnect franceconnect.gouv.fr
        !ants ants.gouv.fr
        !francetravail/poleemploi francetravail.fr pole-emploi.fr
        !urssaf urssaf.fr
        !amendes amendes.gouv.fr
        !antai antai.gouv.fr
        !moncompteformation moncompteformation.gouv.fr
        doctolib doctolib.fr doctolib.com
        !securite-sociale securite-sociale.fr
        !lassuranceretraite lassuranceretraite.fr
        !mesdroitssociaux mesdroitssociaux.gouv.fr
        hmrc hmrc.gov.uk
        irs irs.gov
        elster elster.de
        agenziaentrate agenziaentrate.gov.it
        inps inps.it
        """.trimIndent()
}
