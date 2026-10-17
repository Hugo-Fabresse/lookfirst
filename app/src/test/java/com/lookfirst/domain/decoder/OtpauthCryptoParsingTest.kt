package com.lookfirst.domain.decoder

import com.lookfirst.domain.model.QueryParam
import com.lookfirst.domain.model.ScanPayload
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
        assertEquals("Example", o.issuer)
        assertEquals("Example", o.labelIssuer)
        assertEquals("alice@example.com", o.account)
        assertEquals("JBSWY3DPEHPK3PXP", o.secret)
        assertEquals(
            listOf(QueryParam("issuer", "Example"), QueryParam("digits", "6"), QueryParam("period", "30")),
            o.params,
        )
    }

    @Test
    fun hotpAndPercentEncodedLabel() {
        val o = decode<ScanPayload.Otpauth>("OTPAUTH://HOTP/My%20Bank%3Abob?secret=ABC&counter=1")
        assertEquals("hotp", o.type)
        assertEquals("My Bank", o.labelIssuer)
        assertEquals("bob", o.account)
        assertEquals("My Bank", o.issuer)
    }

    @Test
    fun labelIssuerAndIssuerParameterMayDisagree() {
        val o = decode<ScanPayload.Otpauth>("otpauth://totp/Google:bob?secret=ABC&issuer=Evil")
        assertEquals("Evil", o.issuer)
        assertEquals("Google", o.labelIssuer)
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
        assertEquals("bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq", c.address)
        assertEquals("0.001", c.amount)
        assertEquals("Shop", c.label)
        assertEquals("Order 12", c.message)
    }

    @Test
    fun ethereumTokenTransferKeepsEveryParameter() {
        val c = decode<ScanPayload.Crypto>("ethereum:0xTokenContract@1/transfer?address=0xAttacker&uint256=1e18")
        assertEquals("0xTokenContract", c.address)
        assertEquals("@1/transfer", c.suffix)
        assertEquals("0xAttacker", c.params.first { it.name == "address" }.value)
    }

    @Test
    fun ethereumPayPrefixIsStrippedAndSchemeIsCaseInsensitive() {
        val c = decode<ScanPayload.Crypto>("ETHEREUM:pay-0xabc?value=1")
        assertEquals("0xabc", c.address)
        assertEquals("1", c.amount)
    }

    @Test
    fun cryptoWithoutAddressOrTooManyParamsIsUnknown() {
        assertEquals("BITCOIN", unknown("bitcoin:"))
        assertEquals("BITCOIN", unknown("bitcoin:?amount=1"))
        assertEquals("BITCOIN", unknown("bitcoin:abc?" + "k=v&".repeat(100)))
    }

    @Test
    fun duplicatedAmountKeepsFirstAndBothParams() {
        val c = decode<ScanPayload.Crypto>("bitcoin:abc?amount=1&amount=1000")
        assertEquals("1", c.amount)
        assertEquals(2, c.params.size)
        assertNull(decode<ScanPayload.Crypto>("bitcoin:abc").amount)
    }
}
