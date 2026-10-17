package com.lookfirst.domain.analysis

import com.lookfirst.domain.model.OtpParam
import com.lookfirst.domain.model.PaymentProblem
import com.lookfirst.domain.model.Severity
import com.lookfirst.domain.model.SignalCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        assertEquals(
            listOf(OtpParam.TYPE, OtpParam.ALGORITHM, OtpParam.DIGITS, OtpParam.PERIOD, OtpParam.ISSUER),
            odd.details,
        )
        assertNull(odd.evidence)
    }

    @Test
    fun issuerLabelMismatchIsOdd() {
        val odd = OtpauthAnalyzer.on("otpauth://totp/Google:bob?secret=$secret&issuer=Goggle")
            .of(SignalCode.OTPAUTH_ODD_PARAMS)
        assertEquals(listOf(OtpParam.ISSUER), odd.details)
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
        assertEquals(listOf(PaymentProblem.IBAN, PaymentProblem.BIC, PaymentProblem.CURRENCY), signal.details)
    }

    @Test
    fun epcWithBadAmountOrMissingNameIsMismatch() {
        assertEquals(listOf(PaymentProblem.AMOUNT), PaymentAnalyzer.on(epc(amount = "EUR0.001")).single().details)
        assertEquals(listOf(PaymentProblem.NAME), PaymentAnalyzer.on(epc(name = "")).single().details)
    }

    private val btcBech32 = "bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh"
    private val contract = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"
    private val other = "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359"
    private val lowerEth = "0xd1220a0cf47c7b9be7a2e6ba89f429762e7b9adb"

    @Test
    fun cleanRequestsHaveNoSignal() {
        assertTrue(PaymentAnalyzer.on("bitcoin:$btcBech32?amount=0.015&label=Cafe").isEmpty())
        assertTrue(PaymentAnalyzer.on("ethereum:$contract?value=1e18").isEmpty())
        assertTrue(PaymentAnalyzer.on("ethereum:$lowerEth?value=1000000000000000").isEmpty())
    }

    @Test
    fun tokenTransferToADifferentValidRecipientIsNormal() {
        assertTrue(PaymentAnalyzer.on("ethereum:$contract/transfer?address=$other&uint256=500000000").isEmpty())
    }

    @Test
    fun tokenTransferWithBadRecipientShowsTheRealRecipient() {
        val same = PaymentAnalyzer.on("ethereum:$contract/transfer?address=${contract.lowercase()}&uint256=1")
        assertEquals(listOf(PaymentProblem.RECIPIENT), same.single().details)
        assertEquals(contract.lowercase(), same.single().evidence?.value)
        val missing = PaymentAnalyzer.on("ethereum:$contract/transfer?uint256=1")
        assertEquals(listOf(PaymentProblem.RECIPIENT), missing.single().details)
        assertNull(missing.single().evidence)
        val short = PaymentAnalyzer.on("ethereum:$contract/transfer?address=0x1111&uint256=1")
        assertEquals(listOf(PaymentProblem.RECIPIENT), short.single().details)
        val tampered = "0xFB6916095ca1df60bB79Ce92cE3Ea74c37c5d359"
        val badCase = PaymentAnalyzer.on("ethereum:$contract/transfer?address=$tampered&uint256=1")
        assertEquals(listOf(PaymentProblem.RECIPIENT), badCase.single().details)
    }

    @Test
    fun otherContractCallIsMismatch() {
        val raw = "ethereum:$contract/approve?address=$other&uint256=1"
        assertEquals(listOf(PaymentProblem.CONTRACT_CALL), PaymentAnalyzer.on(raw).single().details)
    }

    @Test
    fun hugeAmountsAreMismatch() {
        val btc = PaymentAnalyzer.on("bitcoin:$btcBech32?amount=50000000")
        assertEquals(listOf(PaymentProblem.AMOUNT), btc.single().details)
        val wei = PaymentAnalyzer.on("ethereum:$lowerEth?value=5000000000000000000000")
        assertEquals(listOf(PaymentProblem.VALUE), wei.single().details)
        val sci = PaymentAnalyzer.on("ethereum:$lowerEth?value=1e30")
        assertEquals(listOf(PaymentProblem.VALUE), sci.single().details)
        val junk = PaymentAnalyzer.on("bitcoin:$btcBech32?amount=-1")
        assertEquals(listOf(PaymentProblem.AMOUNT), junk.single().details)
        val token = PaymentAnalyzer.on("ethereum:$contract/transfer?address=$other&uint256=${"9".repeat(40)}")
        assertEquals(listOf(PaymentProblem.TOKEN_AMOUNT), token.single().details)
    }

    @Test
    fun absurdNumbersNeverThrow() {
        val digits = "9".repeat(3000)
        val amount = PaymentAnalyzer.on("bitcoin:$btcBech32?amount=$digits")
        assertEquals(listOf(PaymentProblem.AMOUNT), amount.single().details)
        val exponent = PaymentAnalyzer.on("ethereum:$lowerEth?value=1e999999999")
        assertEquals(listOf(PaymentProblem.VALUE), exponent.single().details)
    }

    @Test
    fun replacedAddressWithBadChecksumIsSuspicious() {
        val tampered = PaymentAnalyzer.on("bitcoin:bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlk?amount=0.01")
        assertEquals(Severity.SUSPICIOUS, tampered.of(SignalCode.CRYPTO_BAD_CHECKSUM).severity)
        val eth = PaymentAnalyzer.on("ethereum:0x5AAeb6053F3E94C9b9A09f33669435E7Ef1BeAed")
        assertEquals(setOf(SignalCode.CRYPTO_BAD_CHECKSUM), eth.codes())
        val base58 = PaymentAnalyzer.on("bitcoin:1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNb")
        assertEquals(setOf(SignalCode.CRYPTO_BAD_CHECKSUM), base58.codes())
        assertEquals(setOf(SignalCode.CRYPTO_BAD_CHECKSUM), PaymentAnalyzer.on("ethereum:0xAb").codes())
    }

    @Test
    fun validAddressesAndUncheckedFamiliesHaveNoSignal() {
        assertTrue(PaymentAnalyzer.on("bitcoin:1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa").isEmpty())
        assertTrue(PaymentAnalyzer.on("bitcoin:3J98t1WpEZ73CNmQviecrnyiWrnqRhWNLy").isEmpty())
        val taproot = "bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqzk5jj0"
        assertTrue(PaymentAnalyzer.on("bitcoin:$taproot").isEmpty())
        assertTrue(PaymentAnalyzer.on("ethereum:vitalik.eth").isEmpty())
        assertTrue(PaymentAnalyzer.on("monero:somethingnotchecked").isEmpty())
    }
}
