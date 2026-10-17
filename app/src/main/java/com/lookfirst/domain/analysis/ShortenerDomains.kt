package com.lookfirst.domain.analysis

/**
 * Common link shorteners, embedded (no network). A host matches when it equals an entry or is a subdomain of one.
 * Best effort and short on purpose: services appear and die, the list is refreshed with app releases.
 */
internal object ShortenerDomains {
    private val DOMAINS: Set<String> =
        setOf(
            "bit.ly", "bitly.com", "bit.do", "t.co", "tinyurl.com", "tiny.cc", "tiny.one", "goo.gl", "is.gd",
            "v.gd", "ow.ly", "buff.ly", "rebrand.ly", "rb.gy", "cutt.ly", "shorturl.at", "t.ly", "lnkd.in",
            "fb.me", "amzn.to", "j.mp", "x.co", "su.pr", "po.st", "ift.tt", "dlvr.it", "trib.al", "mcaf.ee",
            "s.id", "short.io", "bl.ink", "zpr.io", "soo.gd", "1url.com", "snip.ly", "clck.ru", "vk.cc",
            "adf.ly", "ouo.io", "shorte.st", "tr.ee", "qrco.de", "qr.net", "hyperurl.co", "l.ead.me",
        )

    /** [host] is an ASCII domain, lowercase, without trailing dot. */
    fun matches(host: String): Boolean = DOMAINS.any { host == it || host.endsWith(".$it") }
}
