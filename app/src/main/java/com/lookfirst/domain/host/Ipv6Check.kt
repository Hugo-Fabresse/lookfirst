package com.lookfirst.domain.host

private const val MAX_LENGTH = 45
private const val MAX_GROUPS = 8
private const val MAX_GROUP_DIGITS = 4
private const val IPV4_GROUPS = 2
private const val IPV4_PARTS = 4
private const val MAX_OCTET_DIGITS = 3
private const val MAX_OCTET = 255
private const val HEX_RADIX = 16
private const val ASCII_LIMIT = 0x80

/** Structural check of an IPv6 literal (RFC 4291 text forms, no zone id), bounded in length. */
internal object Ipv6Check {
    /** [host] is what is between the brackets. At most 8 groups, one `::` at most, an optional IPv4 tail. */
    fun isValid(host: String): Boolean {
        if (host.isEmpty() || host.length > MAX_LENGTH || host.count { it == ':' } < 2) return false
        val doubleAt = host.indexOf("::")
        if (doubleAt >= 0 && host.indexOf("::", doubleAt + 1) >= 0) return false
        val compressed = doubleAt >= 0
        val head = if (compressed) host.substring(0, doubleAt) else host
        val tail = if (compressed) host.substring(doubleAt + 2) else ""
        val headGroups = groups(head, ipv4Allowed = !compressed)
        val tailGroups = groups(tail, ipv4Allowed = true)
        if (headGroups == null || tailGroups == null) return false
        val total = headGroups + tailGroups
        return if (compressed) total < MAX_GROUPS else total == MAX_GROUPS
    }

    /** Number of 16-bit groups in [part] (an IPv4 tail counts for two), or null when malformed. */
    private fun groups(part: String, ipv4Allowed: Boolean): Int? {
        if (part.isEmpty()) return 0
        val items = part.split(':')
        var count = 0
        for ((index, item) in items.withIndex()) {
            count += when {
                isHexGroup(item) -> 1
                index == items.lastIndex && ipv4Allowed && isDottedQuad(item) -> IPV4_GROUPS
                else -> return null
            }
        }
        return count
    }

    private fun isHexGroup(item: String): Boolean = item.length in 1..MAX_GROUP_DIGITS &&
        item.all { it.code < ASCII_LIMIT && Character.digit(it, HEX_RADIX) >= 0 }

    private fun isDottedQuad(item: String): Boolean {
        val parts = item.split('.')
        return parts.size == IPV4_PARTS &&
            parts.all { part ->
                part.length in 1..MAX_OCTET_DIGITS &&
                    part.all { it in '0'..'9' } &&
                    (part.length == 1 || part[0] != '0') &&
                    part.toInt() <= MAX_OCTET
            }
    }
}
