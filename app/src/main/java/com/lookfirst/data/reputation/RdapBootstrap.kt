package com.lookfirst.data.reputation

import com.lookfirst.domain.rdap.RdapServer

private const val MAX_SERVICES = 2_000
private const val MAX_TLDS = 10_000
private const val MAX_URLS_PER_SERVICE = 16
private const val MAX_LABEL_LENGTH = 63
private const val MAX_HOST_LENGTH = 253
private const val MAX_URL_LENGTH = 256
private const val MAX_PATH_LENGTH = 128
private const val HTTPS_PREFIX = "https://"
private const val PATH_SAFE = "._~-"

/**
 * The IANA RDAP bootstrap for domain names (RFC 9224): which server answers for a TLD. Read from the file embedded
 * in the APK (`rdap-dns.json`, pinned by SHA-256 in scripts/gen-host-data.py) under [JsonLimits.BOOTSTRAP]; a service
 * is `[[tlds], [urls]]`. [parse] keeps only the conforming URLs (the real file also lists `http://` servers) and
 * drops the services left with none; the first conforming URL of a service wins, and so does the first service for a
 * TLD. A conforming URL is `https`, has a host name (never an address), no port, no userinfo, no query, no
 * fragment, and a plain path that ends with `/`. A file that is not the bootstrap shape at all gives null.
 */
internal class RdapBootstrap private constructor(private val servers: Map<String, RdapServer>) {
    val size: Int get() = servers.size

    /** The first conforming server of [tld] (lowercase ASCII, as the last label of an ASCII domain). */
    fun serverFor(tld: String): RdapServer? = servers[tld]

    companion object {
        fun parse(bytes: ByteArray): RdapBootstrap? {
            val root = StrictJson.parse(bytes, JsonLimits.BOOTSTRAP) as? Json.Obj ?: return null
            val services = (root.fields["services"] as? Json.Arr)?.items ?: return null
            if (services.size > MAX_SERVICES) return null
            val collector = Collector()
            return if (services.all { collector.add(it) }) RdapBootstrap(collector.servers) else null
        }

        /** The server of [url], or null when the URL does not conform. Linear, no regular expression. */
        fun serverOf(url: String): RdapServer? {
            if (url.length > MAX_URL_LENGTH || !url.startsWith(HTTPS_PREFIX)) return null
            val rest = url.substring(HTTPS_PREFIX.length)
            val slash = rest.indexOf('/')
            if (slash <= 0) return null
            val host = rest.substring(0, slash)
            val path = rest.substring(slash)
            return if (isHostName(host) && isPath(path)) RdapServer(host, path) else null
        }
    }

    /** Collects the services one by one; [add] is false when the file is not the bootstrap shape. */
    private class Collector {
        val servers = LinkedHashMap<String, RdapServer>()
        private var tldCount = 0

        fun add(service: Json): Boolean {
            val parts = (service as? Json.Arr)?.items
            val tlds = (parts?.getOrNull(0) as? Json.Arr)?.items
            val urls = (parts?.getOrNull(1) as? Json.Arr)?.items
            if (parts?.size != 2 || tlds == null || urls == null) return false
            tldCount += tlds.size
            if (tldCount > MAX_TLDS || urls.size > MAX_URLS_PER_SERVICE) return false
            val checked = urls.map { (it as? Json.Str)?.value?.let(Companion::serverOf) }
            val server = checked.firstOrNull { it != null }
            tlds.forEach { addTld(it, server) }
            return true
        }

        private fun addTld(node: Json, server: RdapServer?) {
            val tld = (node as? Json.Str)?.value?.takeIf(::isTld)
            if (tld != null && server != null && !servers.containsKey(tld)) servers[tld] = server
        }
    }
}

/** A TLD as the bootstrap lists it: lowercase ASCII letters, digits and hyphens (`xn--` for an IDN). */
private fun isTld(text: String): Boolean = isLabel(text)

private fun isLabel(label: String): Boolean = label.length in 1..MAX_LABEL_LENGTH &&
    label.all { it in 'a'..'z' || it in '0'..'9' || it == '-' } &&
    label[0] != '-' &&
    label.last() != '-'

/**
 * A host name of at least two labels (letters, digits, hyphens), whose last label starts with a letter: an IP
 * literal, a decimal, octal or hexadecimal address and a port or userinfo marker can not pass.
 */
private fun isHostName(host: String): Boolean {
    if (host.length > MAX_HOST_LENGTH) return false
    val labels = host.split('.')
    return labels.size >= 2 && labels.all(::isLabel) && labels.last()[0] in 'a'..'z'
}

/** `/` or `/seg/seg/`: unreserved characters only, no empty, `.` or `..` segment, ends with `/`. */
private fun isPath(path: String): Boolean {
    if (path.length > MAX_PATH_LENGTH || path[0] != '/' || path.last() != '/') return false
    val plain = path.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '/' || it in PATH_SAFE }
    if (!plain) return false
    if (path == "/") return true
    return path.substring(1, path.length - 1).split('/').none { it.isEmpty() || it == "." || it == ".." }
}
