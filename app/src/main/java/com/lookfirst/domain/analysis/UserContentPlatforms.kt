package com.lookfirst.domain.analysis

/**
 * Official domains of [BrandList] where anyone (or any customer) gets a sub-domain of their own, without the suffix
 * being in the private section of the Public Suffix List: `paypal-login.wordpress.com` is a page somebody
 * registered, not a service of the owner. For these, only the exact host and `www.` are official; every other
 * host is a registrable name on an open platform (like `x.blogspot.com`): the brand matcher judges its label with
 * `onPlatform`, and the reputation lists index and look it up by label + platform. A domain that is already a
 * private PSL suffix (blogspot.com, github.io) needs no entry here. Service sub-domains of a real official domain
 * (`accounts.google.com`) stay protected: only the entries below change behaviour.
 *
 * One line per entry: `domain reason`. Reviewed with each release together with [BrandList].
 */
internal object UserContentPlatforms {
    val domains: Set<String> by lazy { parse(DATA) }

    internal fun parse(data: String): Set<String> = data.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && it[0] != '#' }
        .map { it.substringBefore(' ') }
        .toSet()

    /** True for the platform host itself and `www.`: the only hosts of a platform that are the owner's. */
    fun isOwnerHost(ascii: String, platform: String): Boolean = ascii == platform || ascii == "www.$platform"

    /**
     * The registrable name the analyses use for [ascii], whose PSL registrable domain is [registrable]: the tenant
     * name (`x.wordpress.com`) on a platform of [platforms], [registrable] itself otherwise or for the owner hosts.
     */
    fun registrableFor(ascii: String, registrable: String, platforms: Set<String> = domains): String {
        if (registrable !in platforms || isOwnerHost(ascii, registrable)) return registrable
        val labelCount = registrable.count { it == '.' } + 2
        return ascii.split('.').takeLast(labelCount).joinToString(".")
    }

    private val DATA =
        """
        # Included: sub-domains are open to anyone, free or cheap, no review
        wordpress.com free blogs: any visitor creates name.wordpress.com
        free.fr free personal pages and ISP customer sites: name.free.fr, perso.free.fr
        ovh.net customer web hosting and servers answer under name.ovh.net (PSL covers only hosting and webpaas)
        slack.com anyone creates a free workspace: name.slack.com
        # Examined and left out (not sub-domain based, or not open): google.com (Sites are paths), the code host
        # (pages are on a PSL suffix), gitlab.com (gitlab.io), dropbox.com, shopify.com (myshopify.com),
        # cloudflare.com (pages.dev), zoom.us (vanity names need a paid plan), alibaba.com (paid supplier shops),
        # orange.fr and sfr.fr (personal pages are paths), microsoft.com / live.com / office.com (no tenant names),
        # paypal.me, t.me, wa.me (paths)
        """.trimIndent()
}
