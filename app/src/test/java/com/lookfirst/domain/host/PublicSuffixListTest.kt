package com.lookfirst.domain.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicSuffixListTest {
    private val list = PublicSuffixList.instance!!

    private fun ascii(host: String): String? = (HostNormalizer.normalize(host) as? HostResult.Normalized)?.host?.ascii

    @Test
    fun officialTestVectors() {
        val lines = javaClass.getResourceAsStream("/lookfirst/host/test_psl.txt")!!.bufferedReader().readLines()
        val pattern = Regex("""checkPublicSuffix\((null|'[^']*'), (null|'[^']*')\);""")
        val cases = lines.mapNotNull { pattern.matchEntire(it.trim()) }.filter { it.groupValues[1] != "null" }
        val failures = cases.mapNotNull { match ->
            val expected = match.groupValues[2].trim('\'').takeIf { match.groupValues[2] != "null" }
            val actual = ascii(match.groupValues[1].trim('\''))?.let { list.registrableDomain(it) }
            if (actual == expected?.let { ascii(it) }) null else "${match.value} -> $actual"
        }
        assertTrue("${cases.size} cases: $failures", failures.isEmpty())
        assertTrue(cases.size > 50)
    }

    @Test
    fun registrableDomainOfCommonHosts() {
        assertEquals("paypal.com", list.registrableDomain("www.paypal.com"))
        assertEquals("bbc.co.uk", list.registrableDomain("news.bbc.co.uk"))
        assertEquals("impots.gouv.fr", list.registrableDomain("www.impots.gouv.fr"))
        assertEquals("fr.example", list.registrableDomain("impots-gouv.fr.example"))
        assertEquals("evil.example", list.registrableDomain("a.b.evil.example"))
        assertNull(list.registrableDomain("co.uk"))
        assertNull(list.registrableDomain("com"))
    }

    @Test
    fun wildcardAndExceptionRules() {
        val custom = PublicSuffixList.parse(listOf("ck", "*.ck", "!www.ck", "#PRIVATE", "blogspot.com", "com"))
        assertNull(custom.registrableDomain("foo.ck"))
        assertEquals("a.foo.ck", custom.registrableDomain("b.a.foo.ck"))
        assertEquals("www.ck", custom.registrableDomain("www.ck"))
        assertEquals("www.ck", custom.registrableDomain("sub.www.ck"))
    }

    @Test
    fun privateSectionIsKnown() {
        val labels = listOf("paypal", "blogspot", "com")
        val suffix = list.suffixLabelCount(labels)
        assertEquals(2, suffix)
        assertTrue(list.isPrivateSuffix(labels, suffix))
        assertFalse(list.isPrivateSuffix(listOf("paypal", "com"), list.suffixLabelCount(listOf("paypal", "com"))))
        assertEquals("paypal.github.io", list.registrableDomain("www.paypal.github.io"))
    }

    @Test
    fun malformedHostsGiveNothing() {
        assertNull(list.registrableDomain(""))
        assertNull(list.registrableDomain("a..com"))
        assertNotNull(list.registrableDomain("a.example"))
    }
}
