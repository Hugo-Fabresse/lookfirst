package com.lookfirst.domain.analysis

import com.lookfirst.domain.model.Severity
import com.lookfirst.domain.model.SignalCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OtpauthPaymentAnalyzerTest {
    private val secret = "JBSWY3DPEHPK3PXP"

    @Test
    fun anyOtpauthIsAnEnrolment() {
        val signals = OtpauthAnalyzer.on("otpauth://totp/Bank:alice@example.com?secret=$secret&issuer=Bank")
        assertEquals(setOf(SignalCode.OTPAUTH_ENROL), signals.codes())
        assertEquals(Severity.SUSPICIOUS, signals.single().severity)
        assertEquals("Bank", signals.single().evidence?.value)
    }

    @Test
    fun enrolEvidenceFallsBackToTheAccount() {
        val signal = OtpauthAnalyzer.on("otpauth://totp/alice?secret=$secret").single()
        assertEquals("alice", signal.evidence?.value)
    }

    @Test
    fun defaultAndCommonParametersAreNotOdd() {
        val raw = "otpauth://totp/Bank:alice?secret=$secret&issuer=Bank&algorithm=SHA256&digits=8&period=60"
        assertEquals(setOf(SignalCode.OTPAUTH_ENROL), OtpauthAnalyzer.on(raw).codes())
        val sha1 = OtpauthAnalyzer.on("otpauth://totp/a?secret=$secret&algorithm=sha1")
        assertEquals(setOf(SignalCode.OTPAUTH_ENROL), sha1.codes())
    }

    @Test
    fun trappedEnrolmentListsTheOddParameters() {
        val raw = "otpauth://hotp/Google:bob?secret=$secret&issuer=Evil&algorithm=MD5&digits=10&period=5&counter=0"
        val odd = OtpauthAnalyzer.on(raw).of(SignalCode.OTPAUTH_ODD_PARAMS)
        assertEquals(Severity.CAUTION, odd.severity)
        assertEquals("type,algorithm,digits,period,issuer", odd.evidence?.value)
    }

    @Test
    fun issuerLabelMismatchIsOdd() {
        val odd = OtpauthAnalyzer.on("otpauth://totp/Google:bob?secret=$secret&issuer=Goggle")
            .of(SignalCode.OTPAUTH_ODD_PARAMS)
        assertEquals("issuer", odd.evidence?.value)
        val sameIssuer = OtpauthAnalyzer.on("otpauth://totp/Google:bob?secret=$secret&issuer=google")
        assertFalse(SignalCode.OTPAUTH_ODD_PARAMS in sameIssuer.codes())
    }

    @Test
    fun secretNeverAppearsInSignals() {
        val signals = OtpauthAnalyzer.on("otpauth://totp/Bank:a?secret=$secret&algorithm=MD5")
        assertFalse(signals.joinToString { "${it.evidence?.value} $it" }.contains(secret))
    }

    @Test
    fun malformedOtpauthIsNotAnEnrolment() {
        assertTrue(OtpauthAnalyzer.on("otpauth://totp/a").isEmpty())
    }

    private fun epc(
        iban: String = "DE89370400440532013000",
        bic: String = "COBADEFFXXX",
        name: String = "Max Mustermann",
        amount: String = "EUR120.50",
    ) = listOf("BCD", "002", "1", "SCT", bic, name, iban, amount, "", "", "Facture 42").joinToString("\n")

    @Test
    fun consistentEpcHasNoSignal() {
        assertTrue(PaymentAnalyzer.on(epc()).isEmpty())
        assertTrue(PaymentAnalyzer.on(epc(amount = "")).isEmpty())
    }

    @Test
    fun fakeInvoiceWithBadFieldsIsMismatch() {
        val signal = PaymentAnalyzer.on(epc(iban = "DE89370400440532013001", bic = "BAD", amount = "USD99999")).single()
        assertEquals(SignalCode.PAYMENT_MISMATCH, signal.code)
        assertEquals(Severity.SUSPICIOUS, signal.severity)
        assertEquals("iban,bic,currency", signal.evidence?.value)
    }

    @Test
    fun epcWithBadAmountOrMissingNameIsMismatch() {
        assertEquals("amount", PaymentAnalyzer.on(epc(amount = "EUR0.001")).single().evidence?.value)
        assertEquals("name", PaymentAnalyzer.on(epc(name = "")).single().evidence?.value)
    }

    @Test
    fun cleanBitcoinRequestHasNoSignal() {
        val raw = "bitcoin:bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh?amount=0.015&label=Cafe"
        assertTrue(PaymentAnalyzer.on(raw).isEmpty())
        assertTrue(PaymentAnalyzer.on("ethereum:0xAbCdEf0123456789aBcDeF0123456789AbCdEf01?value=1e18").isEmpty())
    }

    @Test
    fun tokenTransferHidesTheRecipient() {
        val raw = "ethereum:0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48/transfer" +
            "?address=0x1111111111111111111111111111111111111111&uint256=500000000"
        assertEquals("token-transfer,recipient", PaymentAnalyzer.on(raw).single().evidence?.value)
    }

    @Test
    fun otherContractCallIsMismatch() {
        val raw = "ethereum:0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48/approve?address=0x11&uint256=1"
        assertEquals("contract-call", PaymentAnalyzer.on(raw).single().evidence?.value)
    }

    @Test
    fun hugeAmountsAreMismatch() {
        val btc = "bitcoin:bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh?amount=50000000"
        assertEquals("amount", PaymentAnalyzer.on(btc).single().evidence?.value)
        val wei = "ethereum:0xAbCdEf0123456789aBcDeF0123456789AbCdEf01?value=5000000000000000000000"
        assertEquals("value", PaymentAnalyzer.on(wei).single().evidence?.value)
        val sci = "ethereum:0xAbCdEf0123456789aBcDeF0123456789AbCdEf01?value=1e30"
        assertEquals("value", PaymentAnalyzer.on(sci).single().evidence?.value)
        val junk = "bitcoin:bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh?amount=-1"
        assertEquals("amount", PaymentAnalyzer.on(junk).single().evidence?.value)
    }

    @Test
    fun absurdNumbersNeverThrow() {
        val digits = "9".repeat(3000)
        assertEquals("amount", PaymentAnalyzer.on("bitcoin:bc1q?amount=$digits").single().evidence?.value)
        assertEquals("value", PaymentAnalyzer.on("ethereum:0xAb?value=1e999999999").single().evidence?.value)
    }
}
