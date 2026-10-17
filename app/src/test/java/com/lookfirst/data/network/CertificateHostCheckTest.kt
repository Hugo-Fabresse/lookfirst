package com.lookfirst.data.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CertificateHostCheckTest {
    private fun names(vararg entries: Pair<Int, String>): List<List<*>> = entries.map { listOf(it.first, it.second) }

    private fun dns(name: String) = 2 to name

    private fun ip(literal: String) = 7 to literal

    private fun matches(host: String, names: List<List<*>>?) = CertificateHostCheck.matchesNames(host, names)

    @Test
    fun `an exact DNS name matches ignoring case and a trailing dot`() {
        val cert = names(dns("Example.COM"))
        assertTrue(matches("example.com", cert))
        assertTrue(matches("example.com.", cert))
        assertFalse(matches("www.example.com", cert))
        assertFalse(matches("example.com.evil.example", cert))
        assertFalse(matches("evilexample.com", cert))
    }

    @Test
    fun `a wildcard stands for exactly one label`() {
        val cert = names(dns("*.example.com"))
        assertTrue(matches("www.example.com", cert))
        assertFalse(matches("example.com", cert))
        assertFalse(matches("a.b.example.com", cert))
        assertFalse(matches(".example.com", cert))
    }

    @Test
    fun `unsafe wildcards are never accepted`() {
        assertFalse(matches("example.com", names(dns("*.com"))))
        assertFalse(matches("a.com", names(dns("*.com"))))
        assertFalse(matches("www.example.com", names(dns("w*.example.com"))))
        assertFalse(matches("www.example.com", names(dns("www.*.com"))))
        assertFalse(matches("www.example.com", names(dns("*.*.com"))))
        assertFalse(matches("www.example.com", names(dns("*"))))
    }

    @Test
    fun `a wildcard over a public suffix is refused`() {
        assertFalse(matches("a.co.uk", names(dns("*.co.uk"))))
        assertFalse(matches("a.github.io", names(dns("*.github.io"))))
        assertFalse(matches("a.com.au", names(dns("*.com.au"))))
        assertTrue(matches("a.example.co.uk", names(dns("*.example.co.uk"))))
    }

    @Test
    fun `the common name is not used, only the subject alternative names`() {
        assertFalse(matches("example.com", null))
        assertFalse(matches("example.com", names()))
    }

    @Test
    fun `an IP host needs an IP name and a DNS name never matches it`() {
        assertTrue(matches("93.184.216.34", names(ip("93.184.216.34"))))
        assertFalse(matches("93.184.216.34", names(dns("93.184.216.34"))))
        assertFalse(matches("93.184.216.34", names(ip("93.184.216.35"))))
        assertFalse(matches("93.184.216.34", names(dns("*.184.216.34"))))
        assertTrue(matches("[2606:4700:4700::1111]", names(ip("2606:4700:4700:0:0:0:0:1111"))))
    }

    @Test
    fun `an IP name does not match a DNS host`() {
        assertFalse(matches("example.com", names(ip("93.184.216.34"))))
    }

    @Test
    fun `other name types and malformed entries are ignored`() {
        assertFalse(matches("example.com", listOf(listOf(1, "example.com"), listOf(2), emptyList<Any>(), listOf(2, 5))))
        assertTrue(matches("example.com", listOf(listOf(1, "x"), listOf(2, "example.com"))))
    }

    @Test
    fun `a non-ASCII or blank host never matches`() {
        assertFalse(matches("bücher.example", names(dns("bücher.example"))))
        assertFalse(matches("", names(dns(""))))
    }
}
