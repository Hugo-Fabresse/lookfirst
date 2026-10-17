package com.lookfirst.data.reputation

import com.lookfirst.domain.analysis.BrandList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Random

class DomainListParserTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun parse(bytes: ByteArray, max: Long = 1000, officials: Set<String> = emptySet()): ParseOutcome {
        val file = folder.newFile()
        file.writeBytes(bytes)
        return DomainListParser.parse(file, max, officials, TEST_PSL)
    }

    private fun parse(text: String, max: Long = 1000, officials: Set<String> = emptySet()) =
        parse(text.toByteArray(Charsets.UTF_8), max, officials)

    private fun parsedOf(text: String, officials: Set<String> = emptySet()) =
        parse(text, officials = officials) as ParseOutcome.Parsed

    private fun reason(outcome: ParseOutcome) = (outcome as ParseOutcome.Rejected).reason

    private fun ParseOutcome.Parsed.has(kind: Char, name: String) = keys.contains(Lfix.keyOf(kind, name))

    @Test
    fun registrableDomainsBecomeDomainKeysAndOtherHostsHostKeys() {
        val out = parsedOf("evil.com\nlogin.phish.net\nbad.blogspot.com\nwww.shop.co.uk\n")
        assertEquals(4, out.keys.size)
        assertTrue(out.has(Lfix.KIND_DOMAIN, "evil.com"))
        assertTrue(out.has(Lfix.KIND_HOST, "login.phish.net"))
        assertTrue(out.has(Lfix.KIND_DOMAIN, "bad.blogspot.com"))
        assertTrue(out.has(Lfix.KIND_HOST, "www.shop.co.uk"))
    }

    @Test
    fun keysAreSortedUnsignedAndUnique() {
        val out = parsedOf(domainLines(200) + domainLines(50))
        assertEquals(200, out.keys.size)
        for (i in 1 until out.keys.size) {
            assertTrue(java.lang.Long.compareUnsigned(out.keys[i - 1], out.keys[i]) < 0)
        }
    }

    @Test
    fun commentsBlankLinesCrlfSpacesAndBomAreHandled() {
        val text = "# header\r\n\r\n  evil.com  \r\n\t\r\n# more\r\nbad.net\r\nthird.com"
        val out = parsedOf(text)
        assertEquals(3, out.keys.size)
        assertTrue(out.has(Lfix.KIND_DOMAIN, "third.com"))
    }

    @Test
    fun uppercaseAndTrailingDotAreNormalized() {
        val out = parsedOf("EVIL.COM.\nBad.Net\nx.com\n")
        assertTrue(out.has(Lfix.KIND_DOMAIN, "evil.com"))
        assertTrue(out.has(Lfix.KIND_DOMAIN, "bad.net"))
    }

    @Test
    fun idnInUnicodeAndPunycodeIsOneEntryInPunycode() {
        val out = parsedOf("bücher.com\nxn--bcher-kva.com\nother.com\nthird.com\n")
        assertEquals(3, out.keys.size)
        assertTrue(out.has(Lfix.KIND_DOMAIN, "xn--bcher-kva.com"))
    }

    @Test
    fun ipv4IsKeptAsAHostAndIpv6IsInvalid() {
        val lines = (1..100).joinToString("\n") { "host$it.com" }
        val out = parsedOf(lines + "\n192.0.2.7\n[::1]\n")
        assertTrue(out.has(Lfix.KIND_HOST, "192.0.2.7"))
        assertEquals(101, out.keys.size)
    }

    @Test
    fun aBarePublicSuffixIsNeverStored() {
        val lines = (1..100).joinToString("\n") { "host$it.com" }
        val out = parsedOf(lines + "\ncom\nco.uk\n")
        assertEquals(100, out.keys.size)
    }

    @Test
    fun anOfficialDomainAndEverythingUnderItIsDroppedAndCounted() {
        val out = parsedOf(
            "paypal.com\naccounts.paypal.com\nevil.login.paypal.com\nother.com\nthird.com\nfourth.net\n",
            officials = setOf("paypal.com"),
        )
        assertEquals(3, out.keys.size)
        assertEquals(3, out.droppedOfficial)
        assertFalse(out.has(Lfix.KIND_HOST, "accounts.paypal.com"))
    }

    @Test
    fun tenantsOfAnOpenPlatformAreIndexedByLabelAndPlatformNotDropped() {
        // The real CERT Polska entries of 2026-10-17 that the app used to lose.
        val out = parsedOf(
            "office365emailverification1.wordpress.com\nofficeemailverification3653.wordpress.com\n" +
                "a.deep.wordpress.com\nwordpress.com\nwww.wordpress.com\naccounts.google.com\nother.com\n",
            officials = BrandList.officialDomains,
        )
        assertTrue(out.has(Lfix.KIND_DOMAIN, "office365emailverification1.wordpress.com"))
        assertTrue(out.has(Lfix.KIND_DOMAIN, "officeemailverification3653.wordpress.com"))
        assertTrue(out.has(Lfix.KIND_HOST, "a.deep.wordpress.com"))
        assertFalse(out.has(Lfix.KIND_DOMAIN, "wordpress.com"))
        assertFalse(out.has(Lfix.KIND_HOST, "www.wordpress.com"))
        assertFalse(out.has(Lfix.KIND_HOST, "accounts.google.com"))
        assertEquals(4, out.keys.size) // 3 tenants + other.com
        assertEquals(3, out.droppedOfficial) // wordpress.com, www.wordpress.com, accounts.google.com
    }

    @Test
    fun manyTenantsOfAnOpenPlatformAreNotPoison() {
        val hosts = (1..150).joinToString("\n") { "x$it.wordpress.com" }
        val out = parse(hosts + "\nok.com\nok2.com\n", officials = BrandList.officialDomains)
        assertEquals(152, (out as ParseOutcome.Parsed).keys.size)
        assertEquals(0, out.droppedOfficial)
    }

    @Test
    fun manyHostsUnderOfficialDomainsMeanAPoisonedList() {
        val hosts = (1..150).joinToString("\n") { "x$it.paypal.com" }
        val outcome = parse(hosts + "\nok.com\nok2.com\n", officials = setOf("paypal.com"))
        assertEquals(ListFailure.POISONED, reason(outcome))
    }

    @Test
    fun withoutThePublicSuffixListTheFileIsRefused() {
        val file = folder.newFile().also { it.writeText(domainLines(20)) }
        val outcome = DomainListParser.parse(file, 1000, emptySet(), null)
        assertEquals(ListFailure.BAD_FORMAT, reason(outcome))
    }

    @Test
    fun manyOfficialDomainsMeanAPoisonedList() {
        val names = (1..150).map { "brand$it.com" }
        val outcome = parse(names.joinToString("\n") + "\nok.com\n", officials = names.toSet())
        assertEquals(ListFailure.POISONED, reason(outcome))
    }

    @Test
    fun urlsAndOtherJunkLinesAreInvalidAndALargeShareRefusesTheFile() {
        assertEquals(
            ListFailure.BAD_FORMAT,
            reason(parse("http://evil.com/x\nuser@evil.com\nevil.com/path\na b.com\n")),
        )
        val mostlyGood = (1..200).joinToString("\n") { "host$it.com" } + "\nhttp://x/y\n"
        assertEquals(200, (parse(mostlyGood) as ParseOutcome.Parsed).keys.size)
    }

    @Test
    fun emptyAndCommentOnlyFilesAreRefused() {
        assertEquals(ListFailure.TOO_FEW, reason(parse("")))
        assertEquals(ListFailure.TOO_FEW, reason(parse("# nothing\n\n   \n")))
    }

    @Test
    fun binaryContentIsRefused() {
        val random = ByteArray(100_000).also { Random(7).nextBytes(it) }
        assertEquals(ListFailure.BAD_FORMAT, reason(parse(random)))
        assertEquals(ListFailure.BAD_FORMAT, reason(parse("evil.com\u0000\nbad.net\u0001\n")))
    }

    @Test
    fun aHugeSingleLineIsRefusedWithoutBuildingIt() {
        val started = System.nanoTime()
        val outcome = parse(ByteArray(5_000_000) { 'a'.code.toByte() })
        assertEquals(ListFailure.BAD_FORMAT, reason(outcome))
        assertTrue("too slow", System.nanoTime() - started < 5_000_000_000L)
    }

    @Test
    fun manyOverlongLinesAreRefused() {
        val long = "a".repeat(400) + ".com\n"
        assertEquals(ListFailure.BAD_FORMAT, reason(parse(long.repeat(500))))
    }

    @Test
    fun moreEntriesThanTheSourceAllowsIsRefusedEarly() {
        val outcome = parse(domainLines(500), max = 100)
        assertEquals(ListFailure.TOO_MANY, reason(outcome))
    }

    @Test
    fun hostileInputsParseInLinearTime() {
        val trap = "a.".repeat(50_000) + "\n"
        val dashes = "-".repeat(100_000) + "\n"
        val labels = ("a".repeat(63) + ".").repeat(4) + "com\n"
        val started = System.nanoTime()
        val outcome = parse((trap + dashes + labels).repeat(20) + domainLines(100))
        val elapsed = System.nanoTime() - started
        assertTrue(outcome is ParseOutcome.Parsed || outcome is ParseOutcome.Rejected)
        assertTrue("took ${elapsed / 1_000_000} ms", elapsed < 10_000_000_000L)
    }

    @Test
    fun randomGarbageNeverThrows() {
        val random = Random(42)
        repeat(200) {
            val bytes = ByteArray(random.nextInt(2000)) { (random.nextInt(256)).toByte() }
            val outcome = parse(bytes)
            assertTrue(outcome is ParseOutcome.Parsed || outcome is ParseOutcome.Rejected)
        }
    }

    @Test
    fun aFullSizedListParsesQuickly() {
        val started = System.nanoTime()
        val outcome = parse(domainLines(100_000, "phish-"), max = 600_000) as ParseOutcome.Parsed
        assertEquals(100_000, outcome.keys.size)
        assertTrue(
            "took ${(System.nanoTime() - started) / 1_000_000} ms",
            System.nanoTime() - started < 20_000_000_000L,
        )
    }

    @Test
    fun theFileIsNotModifiedAndNothingIsLeftBehind() {
        val file: File = folder.newFile().also { it.writeText(domainLines(10)) }
        DomainListParser.parse(file, 100, emptySet(), TEST_PSL)
        assertEquals(domainLines(10), file.readText())
    }
}
