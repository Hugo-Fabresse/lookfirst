package com.lookfirst.domain.decoder

import com.lookfirst.domain.model.EpcIssue
import com.lookfirst.domain.model.ScanPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class EpcParsingTest {
    private fun epcText(
        iban: String = "DE89370400440532013000",
        amount: String = "EUR12.34",
        bic: String = "COBADEFFXXX",
        version: String = "002",
        eol: String = "\n",
    ) = listOf("BCD", version, "1", "SCT", bic, "Max Mustermann", iban, amount, "", "", "Invoice 42").joinToString(eol)

    private fun epc(raw: String) = decode<ScanPayload.Epc>(raw)

    @Test
    fun validEpcIsDecoded() {
        val e = epc(epcText())
        assertEquals("002", e.version)
        assertEquals("COBADEFFXXX", e.bic)
        assertEquals("Max Mustermann", e.name)
        assertEquals("DE89370400440532013000", e.iban)
        assertEquals(BigDecimal("12.34"), e.amount)
        assertEquals("EUR", e.currency)
        assertEquals("Invoice 42", e.remittance)
        assertTrue(e.issues.isEmpty())
    }

    @Test
    fun crlfLineEndingsAndSpacedLowercaseIbanAreAccepted() {
        val e = epc(epcText(iban = "de89 3704 0044 0532 0130 00", eol = "\r\n"))
        assertEquals("DE89370400440532013000", e.iban)
        assertTrue(e.issues.isEmpty())
    }

    @Test
    fun wrongIbanCheckDigitsAreFlagged() {
        assertEquals(setOf(EpcIssue.BAD_IBAN_CHECKSUM), epc(epcText(iban = "DE89370400440532013001")).issues)
        assertEquals(setOf(EpcIssue.BAD_IBAN_CHECKSUM), epc(epcText(iban = "DE00370400440532013000")).issues)
    }

    @Test
    fun malformedIbanIsFlaggedAsFormat() {
        listOf("DE89", "89DE370400440532013000", "DE89-3704-0044", "DE8937040044053201300!").forEach {
            assertTrue(it, EpcIssue.BAD_IBAN_FORMAT in epc(epcText(iban = it)).issues)
        }
    }

    @Test
    fun knownGoodIbansPassTheChecksum() {
        listOf("GB82WEST12345698765432", "FR1420041010050500013M02606", "NL91ABNA0417164300").forEach {
            assertTrue(it, EpcParser.hasValidIbanChecksum(it))
        }
    }

    @Test
    fun badAmountsAreFlaggedAndNotParsed() {
        listOf("EUR12.345", "EUR-5", "EUR", "12.34", "eur12", "EUR1e5", "EUR12,34", "EUR1234567890123456").forEach {
            val e = epc(epcText(amount = it))
            assertTrue(it, EpcIssue.BAD_AMOUNT in e.issues)
            assertNull(it, e.amount)
        }
    }

    @Test
    fun emptyAmountMeansUnspecified() {
        val e = epc(epcText(amount = ""))
        assertNull(e.amount)
        assertNull(e.currency)
        assertTrue(e.issues.isEmpty())
    }

    @Test
    fun badBicIsFlagged() {
        assertTrue(EpcIssue.BAD_BIC in epc(epcText(bic = "123")).issues)
        assertTrue(epc(epcText(bic = "")).bic == null)
    }

    @Test
    fun wrongHeaderIsUnknown() {
        listOf(
            epcText(version = "003"),
            "BCD\n001",
            "BCD\n002\n1\nXXX\n\nName\nDE89370400440532013000",
            "BCD\n",
        ).forEach {
            assertEquals(it, "EPC", (parseOk(it).payload as? ScanPayload.Unknown)?.declaredType)
        }
    }

    @Test
    fun tooManyLinesIsUnknown() {
        assertTrue(parseOk(epcText() + "\n".repeat(50) + "x").payload is ScanPayload.Unknown)
    }

    @Test
    fun bcdWithoutNewlineIsNotEpc() {
        assertFalse(parseOk("BCD").payload is ScanPayload.Epc)
        assertTrue(parseOk("BCDEF").payload is ScanPayload.Text)
    }
}
