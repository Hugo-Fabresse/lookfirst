package com.lookfirst.data.reputation

import com.lookfirst.domain.rdap.RdapServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RdapBootstrapTest {
    private fun file(services: String) =
        """{"version":"1.0","publication":"2026-10-01T00:00:00Z","services":[$services]}"""

    private fun parse(text: String) = RdapBootstrap.parse(text.toByteArray())

    private val valid = file(
        """[["com","net"],["https://rdap.verisign.com/com/v1/"]],""" +
            """[["uk"],["https://rdap.nominet.uk/uk/"]],""" +
            """[["xn--p1ai"],["https://rdap.example.ru/","https://backup.example.ru/"]]""",
    )

    @Test
    fun `a conforming file gives the first server of each tld`() {
        val bootstrap = parse(valid)!!
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
    fun `only conforming urls are kept and the first one wins`() {
        for ((name, url) in hostile) {
            val text = file("""[["com"],["$url","https://rdap.ok.example/x/"]]""")
            assertEquals("kept: $name", RdapServer("rdap.ok.example", "/x/"), parse(text)!!.serverFor("com"))
            assertNull("used: $name", parse(file("""[["com"],["$url"]]"""))!!.serverFor("com"))
        }
    }

    @Test
    fun `a service with no usable server is ignored and the others are kept`() {
        val text = file("""[["org"],["http://rdap.example.org/"]],[["com"],["https://rdap.ok.example/"]]""")
        val bootstrap = parse(text)!!
        assertNull(bootstrap.serverFor("org"))
        assertNotNull(bootstrap.serverFor("com"))
    }

    @Test
    fun `a service without any url is ignored`() {
        val text = file("""[["com"],[]]""")
        assertNull(parse(text)!!.serverFor("com"))
    }

    @Test
    fun `a tld that is not plain lowercase ascii is refused`() {
        for (tld in listOf("COM", "co.m", "", "-com", "com-", "c om", "cöm", "a".repeat(64), "com/", "*")) {
            assertNull(parse(file("""[["$tld"],["https://rdap.ok.example/"]]"""))!!.serverFor(tld))
        }
    }

    @Test
    fun `the same tld in two services gives the first one`() {
        val text = file("""[["com"],["https://one.example/"]],[["com"],["https://two.example/"]]""")
        assertEquals("one.example", parse(text)!!.serverFor("com")!!.host)
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
            assertNull("accepted: $it", parse(it))
        }
    }

    @Test
    fun `an entry that is not a string is skipped`() {
        val badTld = """{"services":[[["com",1],["https://a.example/"]]]}"""
        val badUrl = """{"services":[[["com"],["https://a.example/",2]]]}"""
        assertEquals("a.example", parse(badTld)!!.serverFor("com")!!.host)
        assertEquals("a.example", parse(badUrl)!!.serverFor("com")!!.host)
    }

    @Test
    fun `a huge or too deep file is refused without trouble`() {
        val manyServices = file((1..2_500).joinToString(",") { """[["t$it"],["https://r$it.example/"]]""" })
        assertNull(parse(manyServices))
        val manyTlds = file("""[[${(1..10_500).joinToString(",") { "\"t$it\"" }}],["https://r.example/"]]""")
        assertNull(parse(manyTlds))
        val deep = """{"services":""" + "[".repeat(50) + "]".repeat(50) + "}"
        assertNull(parse(deep))
        assertNull(parse("""{"services":[""" + """[["com"],["https://r.example/"]],""".repeat(200_000) + "]}"))
        assertNull(parse("{".repeat(100_000)))
    }

    @Test
    fun `a url given as an object field or null is not a url`() {
        assertNull(parse("""{"services":[[["com"],[null]]]}""")!!.serverFor("com"))
        assertNull(RdapBootstrap.serverOf(""))
        assertNull(RdapBootstrap.serverOf("https://"))
        assertNull(RdapBootstrap.serverOf("https:///x/"))
    }
}
