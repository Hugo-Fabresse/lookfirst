package com.lookfirst.domain.decoder

import com.lookfirst.domain.model.QueryParam
import com.lookfirst.domain.model.ScanPayload
import com.lookfirst.domain.model.Untrusted
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OtpauthCryptoParsingTest {
    private fun unknown(raw: String) = (parseOk(raw).payload as? ScanPayload.Unknown)?.declaredType

    @Test
    fun totpWithLabelIssuerAndParams() {
        val o =
            decode<ScanPayload.Otpauth>(
                "otpauth://totp/Example:alice@example.com?secret=JBSWY3DPEHPK3PXP&issuer=Example&digits=6&period=30",
            )
        assertEquals("totp", o.type)
        assertEquals("Example", o.issuer?.value)
        assertEquals("Example", o.labelIssuer?.value)
        assertEquals("alice@example.com", o.account.value)
        assertEquals("JBSWY3DPEHPK3PXP", o.secret.value)
        assertEquals(
            listOf(
                QueryParam("issuer", Untrusted("Example")),
                QueryParam("digits", Untrusted("6")),
                QueryParam("period", Untrusted("30")),
            ),
            o.params,
        )
    }

    @Test
    fun hotpAndPercentEncodedLabel() {
        val o = decode<ScanPayload.Otpauth>("OTPAUTH://HOTP/My%20Bank%3Abob?secret=ABC&counter=1")
        assertEquals("hotp", o.type)
        assertEquals("My Bank", o.labelIssuer?.value)
        assertEquals("bob", o.account.value)
        assertEquals("My Bank", o.issuer?.value)
    }

    @Test
    fun labelIssuerAndIssuerParameterMayDisagree() {
        val o = decode<ScanPayload.Otpauth>("otpauth://totp/Google:bob?secret=ABC&issuer=Evil")
        assertEquals("Evil", o.issuer?.value)
        assertEquals("Google", o.labelIssuer?.value)
    }

    @Test
    fun otpauthWithoutSecretOrWithBadTypeIsUnknown() {
        assertEquals("OTPAUTH", unknown("otpauth://totp/x?issuer=y"))
        assertEquals("OTPAUTH", unknown("otpauth://totp/x?secret="))
        assertEquals("OTPAUTH", unknown("otpauth://steam/x?secret=A"))
        assertEquals("OTPAUTH", unknown("otpauth://"))
        assertEquals("OTPAUTH", unknown("otpauth://totp/" + "a".repeat(1000) + "?secret=A"))
        assertEquals("OTPAUTH", unknown("otpauth://totp/x?" + "k=v&".repeat(100) + "secret=A"))
    }

    @Test
    fun secretIsNeverInToStringNorInParams() {
        val o = decode<ScanPayload.Otpauth>("otpauth://totp/x?secret=TOPSECRET")
        assertFalse(o.toString().contains("TOPSECRET"))
        assertTrue(o.params.isEmpty())
    }

    @Test
    fun bitcoinWithAmountAndLabel() {
        val c =
            decode<ScanPayload.Crypto>(
                "bitcoin:bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq?amount=0.001&label=Shop&message=Order%2012",
            )
        assertEquals("bitcoin", c.coin)
        assertEquals("bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq", c.address.value)
        assertEquals("0.001", c.amount?.value)
        assertEquals("Shop", c.label?.value)
        assertEquals("Order 12", c.message?.value)
    }

    @Test
    fun ethereumTokenTransferExposesTheRealRecipientAndAmount() {
        val c = decode<ScanPayload.Crypto>("ethereum:0xTokenContract@1/transfer?address=0xAttacker&uint256=1000000")
        assertEquals("0xTokenContract", c.address.value)
        assertEquals("@1/transfer", c.suffix.value)
        assertEquals("transfer", c.function?.value)
        assertEquals("0xAttacker", c.recipient?.value)
        assertEquals("1000000", c.tokenAmount?.value)
        assertNull(c.amount)
        assertNull(c.value)
    }

    @Test
    fun ethereumPayPrefixKeepsWeiSeparateFromAmount() {
        val c = decode<ScanPayload.Crypto>("ETHEREUM:pay-0xabc?value=1e18")
        assertEquals("0xabc", c.address.value)
        assertEquals("1e18", c.value?.value)
        assertNull(c.amount)
        assertNull(c.function)
        assertNull(c.recipient)
    }

    @Test
    fun addressParameterIsNotARecipientOutsideTransfer() {
        assertNull(decode<ScanPayload.Crypto>("ethereum:0xabc@1?address=0xevil").recipient)
    }

    @Test
    fun cryptoWithoutAddressOrTooManyParamsIsUnknown() {
        assertEquals("BITCOIN", unknown("bitcoin:"))
        assertEquals("BITCOIN", unknown("bitcoin:?amount=1"))
        assertEquals("BITCOIN", unknown("bitcoin:abc?" + "k=v&".repeat(100)))
    }

    @Test
    fun ambiguousDuplicatesAreRefused() {
        listOf(
            "bitcoin:abc?amount=1&amount=1000",
            "ethereum:0xabc?value=1&value=2",
            "ethereum:0xabc@1/transfer?address=0xa&address=0xb&uint256=1",
            "ethereum:0xabc@1/transfer?address=0xa&uint256=1&uint256=2",
        ).forEach { assertEquals(it, true, parseOk(it).payload is ScanPayload.Unknown) }
    }

    @Test
    fun unknownRequiredBip21ParameterIsRefused() {
        assertEquals("BITCOIN", unknown("bitcoin:abc?amount=1&req-somethingyoudontunderstand=1"))
        assertNull(decode<ScanPayload.Crypto>("bitcoin:abc").amount)
    }

    @Test
    fun otpauthDuplicatesAreRefusedAndToStringHidesEverything() {
        listOf(
            "secret=A&secret=B",
            "secret=A&issuer=x&issuer=y",
            "secret=A&digits=6&digits=8",
            "secret=A&period=30&period=1",
        )
            .forEach { assertEquals(it, "OTPAUTH", unknown("otpauth://totp/x?$it")) }
        val tooLong = parseOk("otpauth://totp/" + "a".repeat(1000) + "?secret=TOPSEED").payload
        assertFalse(tooLong.toString().contains("TOPSEED"))
        assertFalse(tooLong.toString().contains("aaaa"))
    }

    @Test
    fun hiddenCharactersInDecodedFieldsAreReportedByFieldName() {
        val ok = parseOk("otpauth://totp/x?secret=A&issuer=Goo%E2%80%8Bgle%E2%80%AE")
        assertTrue(ok.hiddenChars.isEmpty())
        val issuer = ok.fieldHiddenChars.first { it.field == "issuer" }
        assertEquals(listOf(0x200B, 0x202E), issuer.chars.map { it.codePoint })
    }

    @Test
    fun decodedFieldsAreNormalizedToNfc() {
        val c = decode<ScanPayload.Crypto>("bitcoin:abc?label=cafe%CC%81")
        assertEquals("caf\u00E9", c.label?.value)
    }
}
