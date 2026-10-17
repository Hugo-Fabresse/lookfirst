package com.lookfirst.data.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class AddressFilterTest {
    private fun blocked(literal: String) = AddressFilter.isBlocked(InetAddress.getByName(literal))

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `private loopback link-local and reserved IPv4 ranges are blocked`() {
        listOf(
            "0.0.0.0", "0.1.2.3", "10.0.0.1", "10.255.255.255", "100.64.0.1", "100.127.255.254", "127.0.0.1",
            "127.255.0.9", "169.254.169.254", "172.16.0.1", "172.31.255.255", "192.168.0.1", "192.0.0.1", "192.0.2.1",
            "198.18.0.1", "198.19.255.255", "198.51.100.7", "203.0.113.9", "224.0.0.1", "239.255.255.250", "240.0.0.1",
            "255.255.255.255",
        ).forEach { assertTrue("$it must be blocked", blocked(it)) }
    }

    @Test
    fun `public IPv4 addresses next to the blocked ranges are allowed`() {
        listOf(
            "1.1.1.1", "8.8.8.8", "9.255.255.255", "11.0.0.1", "100.63.255.255", "100.128.0.1", "126.255.255.255",
            "128.0.0.1", "169.253.0.1", "169.255.0.1", "172.15.255.255", "172.32.0.1", "192.167.255.255", "192.169.0.1",
            "198.17.255.255", "198.20.0.1", "223.255.255.255", "93.184.216.34",
        ).forEach { assertFalse("$it must be allowed", blocked(it)) }
    }

    @Test
    fun `IPv6 loopback unspecified unique-local link-local and multicast are blocked`() {
        listOf("::1", "::", "fc00::1", "fd12:3456::1", "fe80::1", "febf::1", "fec0::1", "ff02::1", "ff00::")
            .forEach { assertTrue("$it must be blocked", blocked(it)) }
    }

    @Test
    fun `public IPv6 addresses are allowed`() {
        listOf("2606:4700:4700::1111", "2a00:1450:4007:80d::200e", "2001:4860:4860::8888")
            .forEach { assertFalse("$it must be allowed", blocked(it)) }
    }

    @Test
    fun `an IPv4-mapped IPv6 address is judged as its embedded IPv4`() {
        // Built from raw bytes: Java turns a mapped literal into an Inet4Address, the filter must not rely on that.
        fun mapped(a: Int, b: Int, c: Int, d: Int) = bytes(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xFF, 0xFF, a, b, c, d)
        assertTrue(AddressFilter.isBlocked(mapped(127, 0, 0, 1)))
        assertTrue(AddressFilter.isBlocked(mapped(10, 1, 2, 3)))
        assertTrue(AddressFilter.isBlocked(mapped(169, 254, 169, 254)))
        assertFalse(AddressFilter.isBlocked(mapped(93, 184, 216, 34)))
        assertTrue(blocked("::ffff:192.168.1.1"))
    }

    @Test
    fun `IPv4-compatible NAT64 6to4 and Teredo forms cannot smuggle a private address`() {
        assertTrue(AddressFilter.isBlocked(bytes(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 127, 0, 0, 1)))
        assertTrue(AddressFilter.isBlocked(bytes(0, 0x64, 0xFF, 0x9B, 0, 0, 0, 0, 0, 0, 0, 0, 10, 0, 0, 1)))
        assertFalse(AddressFilter.isBlocked(bytes(0, 0x64, 0xFF, 0x9B, 0, 0, 0, 0, 0, 0, 0, 0, 93, 184, 216, 34)))
        assertTrue(AddressFilter.isBlocked(bytes(0x20, 0x02, 192, 168, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)))
        assertTrue(AddressFilter.isBlocked(bytes(0x20, 0x01, 0, 0, 1, 2, 3, 4, 0, 0, 0, 0, 0, 0, 0, 1)))
        assertTrue(AddressFilter.isBlocked(bytes(0x20, 0x01, 0x0D, 0xB8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)))
    }

    @Test
    fun `an address of an unexpected length is blocked`() {
        assertTrue(AddressFilter.isBlocked(bytes()))
        assertTrue(AddressFilter.isBlocked(bytes(1, 2, 3)))
        assertTrue(AddressFilter.isBlocked(ByteArray(17)))
    }
}
