package com.lookfirst.domain.analysis

/**
 * One open platform: [domain] is an official domain of [BrandList]. [reserved] are labels the platform keeps for its
 * own services (`mobile.free.fr` and anything under it is the owner's, nobody can register them). [subSuffixes] are
 * names under the platform below which every label is a tenant (`vps-1.vps.ovh.net`): the sub-suffix itself is the
 * owner's, so one entry never covers all tenants.
 */
internal class UserContentPlatform(
    val domain: String,
    val reserved: Set<String> = emptySet(),
    val subSuffixes: Set<String> = emptySet(),
)

/**
 * Official domains of [BrandList] where anyone (or any customer) gets a sub-domain of their own, without the suffix
 * being in the private section of the Public Suffix List: `paypal-login.wordpress.com` is a page somebody
 * registered, not a service of the owner. For these, only the exact host, `www.` and the platform's reserved
 * service labels are official; every other host is a registrable name on an open platform (like `x.blogspot.com`):
 * the brand matcher judges its label with `onPlatform`, and the reputation lists index and look it up by label +
 * platform. A domain that is already a private PSL suffix (blogspot.com, github.io) needs no entry here. Service
 * sub-domains of a real official domain that is not a platform (`accounts.google.com`) stay protected.
 *
 * Residual risk: a service label missing from `reserved` is treated as a tenant (a possible false positive of the
 * brand matcher, and a list entry on it would be honoured). An unlisted platform stays a gap: add it here when
 * found. Reserved labels come from the platforms' public documentation and are reviewed with each release together
 * with [BrandList].
 */
internal object UserContentPlatforms {
    private val PLATFORMS: List<UserContentPlatform> =
        listOf(
            // Free blogs: anyone creates name.wordpress.com.
            UserContentPlatform(
                "wordpress.com",
                reserved = setOf(
                    "api", // REST API host
                    "public-api", // WordPress.com REST API v1.1
                    "developer", // developer.wordpress.com, developer documentation
                    "support", // support.wordpress.com, help centre
                    "wordpress", // the platform's own name, reserved at sign-up
                ),
            ),
            // Personal pages and customer sites of the ISP: name.free.fr, perso.free.fr.
            UserContentPlatform(
                "free.fr",
                reserved = setOf(
                    "mobile", // mobile.free.fr, mobile subscriber area
                    "adsl", // adsl.free.fr, Freebox subscriber area
                    "assistance", // assistance.free.fr, support
                    "subscribe", // subscribe.free.fr, new subscriptions
                    "portail", // portail.free.fr, portal
                    "webmail", // webmail.free.fr
                    "zimbra", // zimbra.free.fr, webmail
                    "imp", // imp.free.fr, legacy webmail
                    "imap", // imap.free.fr, mail server
                    "smtp", // smtp.free.fr, mail server
                    "pop", // pop.free.fr, mail server
                ),
            ),
            // Customer machines get generated names: vps-1a2b3c4d.vps.ovh.net. The PSL private section only knows
            // hosting.ovh.net and webpaas.ovh.net.
            UserContentPlatform(
                "ovh.net",
                reserved = setOf(
                    "mail", // mail.ovh.net, webmail
                    "travaux", // travaux.ovh.net, incident tracker
                    "help", // help.ovh.net, help centre
                ),
                subSuffixes = setOf("vps.ovh.net"),
            ),
            // Anyone creates a free workspace: name.slack.com.
            UserContentPlatform(
                "slack.com",
                reserved = setOf(
                    "app", // app.slack.com, web client
                    "api", // api.slack.com, API and documentation
                    "status", // status.slack.com, service status
                    "files", // files.slack.com, file downloads
                    "hooks", // hooks.slack.com, incoming webhooks
                    "edgeapi", // edgeapi.slack.com, client API endpoint
                    "join", // join.slack.com, invitation links
                ),
            ),
            // Examined and left out (not sub-domain based, or not open): google.com (Sites are paths), the code
            // host (pages are on a PSL suffix), gitlab.com (gitlab.io), dropbox.com, shopify.com (myshopify.com),
            // cloudflare.com (pages.dev), zoom.us (vanity names need a paid plan), alibaba.com (paid supplier
            // shops), orange.fr and sfr.fr (personal pages are paths), microsoft.com / live.com / office.com (no
            // tenant names), paypal.me, t.me, wa.me (paths), wetransfer.com (name.wetransfer.com exists only on
            // paid Pro/Teams plans and its service sub-domains cannot be listed with certainty: false positives
            // would cost more than the gap).
        )

    private val byDomain: Map<String, UserContentPlatform> = PLATFORMS.associateBy { it.domain }

    val domains: Set<String> get() = byDomain.keys

    /** Every entry with its reserved labels and sub-suffixes (tests walk them through all three analyses). */
    val all: List<UserContentPlatform> get() = PLATFORMS

    /** True for the platform host itself, `www.`, and a reserved service label with everything under it. */
    fun isOwnerHost(ascii: String, platform: String): Boolean {
        if (ascii == platform || ascii == "www.$platform") return true
        val platformInfo = byDomain[platform]
        if (platformInfo == null || !ascii.endsWith(".$platform")) return false
        if (ascii in platformInfo.subSuffixes) return true
        val labels = ascii.split('.')
        val index = labels.size - (platform.count { it == '.' } + 1) - 1
        return index >= 0 && labels[index] in platformInfo.reserved
    }

    /**
     * The registrable name the analyses use for [ascii], whose PSL registrable domain is [registrable]: the tenant
     * name (`x.wordpress.com`, `vps-1.vps.ovh.net`) on a platform of [platforms], [registrable] itself otherwise
     * or for the owner's hosts.
     */
    fun registrableFor(ascii: String, registrable: String, platforms: Set<String> = domains): String {
        if (registrable !in platforms) return registrable
        val subSuffix = byDomain[registrable]?.subSuffixes?.firstOrNull { ascii.endsWith(".$it") }
        if (subSuffix == null && isOwnerHost(ascii, registrable)) return registrable
        val labelCount = (subSuffix ?: registrable).count { it == '.' } + 2
        return ascii.split('.').takeLast(labelCount).joinToString(".")
    }
}
