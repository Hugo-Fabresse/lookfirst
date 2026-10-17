package com.lookfirst.data.network

import java.net.InetAddress
import java.net.UnknownHostException
import java.security.cert.CertificateParsingException
import java.security.cert.X509Certificate
import java.util.Locale

private const val SAN_DNS = 2
private const val SAN_IP = 7
private const val MIN_WILDCARD_LABELS = 3
private const val IPV4_GROUPS = 4
private const val MAX_IPV4_GROUP_DIGITS = 3
private const val LAST_PRINTABLE_ASCII = 0x7E

/**
 * Does the certificate name the host we meant to reach? Only the subject alternative names count (no fallback to the
 * common name, like current browsers). A DNS name may start with one `*` label that stands for exactly one label, never
 * in a name of fewer than three labels (`*.com`). An IP host needs an IP SAN. This is checked here, on the socket,
 * because a raw TLS socket has no hostname verification of its own.
 */
internal object CertificateHostCheck {
    fun matches(host: String, certificate: X509Certificate): Boolean {
        val names = try {
            certificate.subjectAlternativeNames
        } catch (ignored: CertificateParsingException) {
            null
        }
        return matchesNames(host, names)
    }

    /** [names] as `X509Certificate.getSubjectAlternativeNames` gives them: entries of (type, value). */
    fun matchesNames(host: String, names: Collection<List<*>>?): Boolean {
        val literal = ipLiteral(host)
        return names.orEmpty().any { entry ->
            val type = entry.getOrNull(0) as? Int
            val value = entry.getOrNull(1) as? String
            when {
                value == null -> false
                literal != null -> type == SAN_IP && ipLiteral(value)?.contentEquals(literal) == true
                else -> type == SAN_DNS && dnsMatches(host, value)
            }
        }
    }

    fun dnsMatches(host: String, pattern: String): Boolean {
        val h = host.trimEnd('.').lowercase(Locale.ROOT)
        val p = pattern.trimEnd('.').lowercase(Locale.ROOT)
        val usable = h.isNotEmpty() && p.isNotEmpty() && h.none { it <= ' ' || it.code > LAST_PRINTABLE_ASCII }
        return when {
            !usable -> false
            !p.contains('*') -> h == p
            else -> wildcardMatches(h.split('.'), p.split('.'))
        }
    }

    /** `*` alone in the first label, standing for exactly one non-empty label, and at least three labels. */
    private fun wildcardMatches(hostLabels: List<String>, patternLabels: List<String>): Boolean {
        val wildcardOk = patternLabels[0] == "*" && patternLabels.size >= MIN_WILDCARD_LABELS
        val restIsPlain = patternLabels.drop(1).none { it.contains('*') }
        val sameShape = hostLabels.size == patternLabels.size && hostLabels[0].isNotEmpty()
        return wildcardOk && restIsPlain && sameShape && hostLabels.drop(1) == patternLabels.drop(1)
    }

    private fun isDottedQuad(text: String): Boolean {
        val groups = text.split('.')
        return groups.size == IPV4_GROUPS &&
            groups.all { g -> g.length in 1..MAX_IPV4_GROUP_DIGITS && g.all { it in '0'..'9' } }
    }

    /** The bytes of an IP literal, null for anything else; never a DNS lookup (only literals reach the parser). */
    private fun ipLiteral(text: String): ByteArray? {
        if (!text.contains(':') && !isDottedQuad(text)) return null
        return try {
            InetAddress.getByName(text.removePrefix("[").removeSuffix("]")).address
        } catch (ignored: UnknownHostException) {
            null
        }
    }
}
