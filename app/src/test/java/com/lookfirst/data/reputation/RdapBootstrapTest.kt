package com.lookfirst.data.reputation

import com.lookfirst.domain.rdap.RdapServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RdapBootstrapTest {
    private fun file(services: String) =
        """{"version":"1.0","publication":"2026-10-01T00:00:00Z","services":[$services]}"""

    private fun strict(text: String) = RdapBootstrap.parseStrict(text.toByteArray())

    private fun lenient(text: String) = RdapBootstrap.parseLenient(text.toByteArray())

    private val valid = file(
        """[["com","net"],["https://rdap.verisign.com/com/v1/"]],""" +
            """[["uk"],["https://rdap.nominet.uk/uk/"]],""" +
            """[["xn--p1ai"],["https://rdap.example.ru/","https://backup.example.ru/"]]""",
    )

    @Test
    fun `a conforming file gives the first server of each tld`() {
        val bootstrap = strict(valid)!!
        assertEquals(RdapServer("rdap.verisign.com", "/com/v1/"), bootstrap.serverFor("com"))
        assertEquals(RdapServer("rdap.verisign.com", "/com/v1/"), bootstrap.serverFor("net"))
        assertEquals(RdapServer("rdap.nominet.uk", "/uk/"), bootstrap.serverFor("uk"))
        assertEquals(RdapServer("rdap.example.ru", "/"), bootstrap.serverFor("xn--p1ai"))
        assertNull(bootstrap.serverFor("org"))
        assertEquals(4, bootstrap.size)
    }

    private val hostile = listOf(
        "http scheme" to "http://rdap.example.com/",
        "no scheme" to "rdap.example.com/",
        "upper-case scheme" to "HTTPS://rdap.example.com/",
        "ipv4 literal" to "https://93.184.216.34/",
        "decimal ipv4" to "https://2130706433/",
        "hex ipv4" to "https://0x7f.0x1/",
        "ipv6 literal" to "https://[::1]/",
        "port" to "https://rdap.example.com:8443/",
        "port 443" to "https://rdap.example.com:443/",
        "userinfo" to "https://user@rdap.example.com/",
        "userinfo trick" to "https://rdap.example.com@evil.example/",
        "query" to "https://rdap.example.com/?a=b",
        "fragment" to "https://rdap.example.com/#x",
        "no path" to "https://rdap.example.com",
        "path without final slash" to "https://rdap.example.com/com/v1",
        "dot segment" to "https://rdap.example.com/a/../b/",
        "empty segment" to "https://rdap.example.com/a//b/",
        "percent" to "https://rdap.example.com/%2e%2e/",
        "backslash" to "https://rdap.example.com\\\\evil/",
        "space" to "https://rdap.example.com/a b/",
        "single label host" to "https://localhost/",
        "numeric tld" to "https://rdap.example.1/",
        "uppercase host" to "https://RDAP.example.com/",
        "underscore host" to "https://rdap_x.example.com/",
        "idn host" to "https://rdap.exämple.com/",
        "hyphen start" to "https://-rdap.example.com/",
        "empty label" to "https://rdap..example.com/",
        "trailing dot" to "https://rdap.example.com./",
        "too long" to "https://rdap.example.com/" + "a/".repeat(100),
    )

    @Test
    fun `a bundle with one non-conforming server url is refused`() {
        for ((name, url) in hostile) {
            val text = file("""[["com"],["https://rdap.ok.example/","$url"]]""")
            assertNull("accepted: $name", strict(text))
            val alone = file("""[["com"],["$url"]]""")
            assertNull("accepted alone: $name", strict(alone))
        }
    }

    @Test
    fun `reading an installed file keeps only conforming urls and takes the first one`() {
        for ((name, url) in hostile) {
            val text = file("""[["com"],["$url","https://rdap.ok.example/x/"]]""")
            assertEquals("kept: $name", RdapServer("rdap.ok.example", "/x/"), lenient(text)!!.serverFor("com"))
            assertNull("used: $name", lenient(file("""[["com"],["$url"]]"""))!!.serverFor("com"))
        }
    }

    @Test
    fun `the lenient reader ignores a service with no usable server and keeps the others`() {
        val text = file("""[["org"],["http://rdap.example.org/"]],[["com"],["https://rdap.ok.example/"]]""")
        val bootstrap = lenient(text)!!
        assertNull(bootstrap.serverFor("org"))
        assertNotNull(bootstrap.serverFor("com"))
    }

    @Test
    fun `a service without any url is refused at install and ignored when read`() {
        val text = file("""[["com"],[]]""")
        assertNull(strict(text))
        assertNull(lenient(text)!!.serverFor("com"))
    }

    @Test
    fun `a tld that is not plain lowercase ascii is refused`() {
        for (tld in listOf("COM", "co.m", "", "-com", "com-", "c om", "cöm", "a".repeat(64), "com/", "*")) {
            assertNull("accepted: $tld", strict(file("""[["$tld"],["https://rdap.ok.example/"]]""")))
            assertNull(lenient(file("""[["$tld"],["https://rdap.ok.example/"]]"""))!!.serverFor(tld))
        }
    }

    @Test
    fun `the same tld in two services is ambiguous at install and the first wins when read`() {
        val text = file("""[["com"],["https://one.example/"]],[["com"],["https://two.example/"]]""")
        assertNull(strict(text))
        assertEquals("one.example", lenient(text)!!.serverFor("com")!!.host)
    }

    @Test
    fun `a file that is not the bootstrap shape is refused`() {
        listOf(
            "",
            "[]",
            "{}",
            """{"services":{}}""",
            """{"services":[1]}""",
            """{"services":[[]]}""",
            """{"services":[[["com"]]]}""",
            """{"services":[[["com"],["https://a.example/"],["x"]]]}""",
            """{"services":[[["com"],"https://a.example/"]]}""",
            """{"services":[],"services":[]}""",
        ).forEach {
            assertNull("strict accepted: $it", strict(it))
            assertNull("lenient accepted: $it", lenient(it))
        }
    }

    @Test
    fun `an entry that is not a string is refused at install and skipped when read`() {
        val badTld = """{"services":[[["com",1],["https://a.example/"]]]}"""
        val badUrl = """{"services":[[["com"],["https://a.example/",2]]]}"""
        assertNull(strict(badTld))
        assertNull(strict(badUrl))
        assertEquals("a.example", lenient(badTld)!!.serverFor("com")!!.host)
        assertEquals("a.example", lenient(badUrl)!!.serverFor("com")!!.host)
    }

    @Test
    fun `a huge or too deep file is refused without trouble`() {
        val manyServices = file((1..2_500).joinToString(",") { """[["t$it"],["https://r$it.example/"]]""" })
        assertNull(strict(manyServices))
        val manyTlds = file("""[[${(1..10_500).joinToString(",") { "\"t$it\"" }}],["https://r.example/"]]""")
        assertNull(strict(manyTlds))
        val deep = """{"services":""" + "[".repeat(50) + "]".repeat(50) + "}"
        assertNull(strict(deep))
        assertNull(strict("""{"services":[""" + """[["com"],["https://r.example/"]],""".repeat(200_000) + "]}"))
        assertNull(strict("{".repeat(100_000)))
    }

    @Test
    fun `a url given as an object field or null is not a url`() {
        assertNull(strict("""{"services":[[["com"],[null]]]}"""))
        assertNull(RdapBootstrap.serverOf(""))
        assertNull(RdapBootstrap.serverOf("https://"))
        assertNull(RdapBootstrap.serverOf("https:///x/"))
    }
}
