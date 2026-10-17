package com.lookfirst.data.network

import java.net.InetAddress

private const val IPV4_LENGTH = 4
private const val IPV6_LENGTH = 16
private const val BITS_PER_BYTE = 8
private const val BYTE_MASK = 0xFF

/** Offset of the IPv4 address inside an IPv4-mapped (`::ffff:a.b.c.d`) or NAT64 (`64:ff9b::a.b.c.d`) address. */
private const val EMBEDDED_IPV4_AT = 12

/** Offset of the IPv4 address inside a 6to4 (`2002:aabb:ccdd::`) address. */
private const val SIX_TO_FOUR_IPV4_AT = 2

/** Bytes before the marker of an IPv4-mapped address are zero; the two marker bytes are 0xFF. */
private const val MAPPED_MARKER_AT = 10

/** A network prefix. Built from literals of this file only, so the parser never resolves a name. */
private class Cidr(text: String) {
    private val bits = text.substringAfter('/').toInt()
    private val prefix = InetAddress.getByName(text.substringBefore('/')).address

    fun contains(address: ByteArray, at: Int = 0): Boolean {
        if (address.size - at < prefix.size) return false
        val whole = bits / BITS_PER_BYTE
        val rest = bits % BITS_PER_BYTE
        for (i in 0 until whole) if (address[at + i] != prefix[i]) return false
        if (rest == 0) return true
        val mask = (BYTE_MASK shl (BITS_PER_BYTE - rest)) and BYTE_MASK
        return (address[at + whole].toInt() and mask) == (prefix[whole].toInt() and mask)
    }
}

/**
 * Which resolved addresses the tracer refuses to contact (threat model T-NET-2): the user's own network and every
 * range that is not a public host. Works on the raw bytes, so the textual form (`::ffff:127.0.0.1`, `0x7f.1`) cannot
 * hide anything. An address of any other length is blocked.
 */
internal object AddressFilter {
    private val blockedIpv4 = listOf(
        "0.0.0.0/8", // "this network"
        "10.0.0.0/8", // private
        "100.64.0.0/10", // carrier-grade NAT
        "127.0.0.0/8", // loopback
        "169.254.0.0/16", // link-local (cloud metadata lives here)
        "172.16.0.0/12", // private
        "192.0.0.0/24", // IETF protocol assignments
        "192.0.2.0/24", // documentation
        "192.168.0.0/16", // private
        "198.18.0.0/15", // benchmarking
        "198.51.100.0/24", // documentation
        "203.0.113.0/24", // documentation
        "224.0.0.0/4", // multicast
        "240.0.0.0/4", // reserved, broadcast
    ).map { Cidr(it) }

    private val blockedIpv6 = listOf(
        "::/96", // unspecified, loopback, IPv4-compatible
        "fc00::/7", // unique local
        "fe80::/10", // link-local
        "fec0::/10", // site-local (deprecated)
        "ff00::/8", // multicast
        "100::/64", // discard-only
        "2001::/32", // Teredo
        "2001:db8::/32", // documentation
        "64:ff9b:1::/48", // local-use NAT64
    ).map { Cidr(it) }

    private val nat64 = Cidr("64:ff9b::/96")
    private val sixToFour = Cidr("2002::/16")

    fun isBlocked(address: InetAddress): Boolean = isBlocked(address.address)

    fun isBlocked(bytes: ByteArray): Boolean = when (bytes.size) {
        IPV4_LENGTH -> isBlockedIpv4(bytes, 0)
        IPV6_LENGTH -> isBlockedIpv6(bytes)
        else -> true
    }

    private fun isBlockedIpv4(bytes: ByteArray, at: Int): Boolean =
        blockedIpv4.any { it.contains(bytes.copyOfRange(at, at + IPV4_LENGTH)) }

    private fun isBlockedIpv6(bytes: ByteArray): Boolean = when {
        isMapped(bytes) -> isBlockedIpv4(bytes, EMBEDDED_IPV4_AT)
        nat64.contains(bytes) -> isBlockedIpv4(bytes, EMBEDDED_IPV4_AT)
        sixToFour.contains(bytes) -> isBlockedIpv4(bytes, SIX_TO_FOUR_IPV4_AT)
        else -> blockedIpv6.any { it.contains(bytes) }
    }

    private fun isMapped(bytes: ByteArray): Boolean = (0 until MAPPED_MARKER_AT).all { bytes[it].toInt() == 0 } &&
        (bytes[MAPPED_MARKER_AT].toInt() and BYTE_MASK) == BYTE_MASK &&
        (bytes[MAPPED_MARKER_AT + 1].toInt() and BYTE_MASK) == BYTE_MASK
}
