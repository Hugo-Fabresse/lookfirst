package com.lookfirst.domain.analysis

import com.lookfirst.domain.model.EpcIssue
import com.lookfirst.domain.model.ParseResult
import com.lookfirst.domain.model.PaymentProblem
import com.lookfirst.domain.model.ScanPayload
import com.lookfirst.domain.model.Signal
import com.lookfirst.domain.model.SignalCode
import com.lookfirst.domain.model.Untrusted
import java.util.Locale

private const val MAX_NUMBER_CHARS = 40
private const val MAX_EXPONENT_CHARS = 2

/** Coin units (BIP-21 `amount`) above this are implausible for a QR payment request. */
private const val MAX_COIN_AMOUNT = 1_000_000L

/** `value` in wei with more digits than this is at least 1000 ETH (10^21 wei). */
private const val MAX_WEI_DIGITS = 21

/** `uint256` token amount with more digits than this is at least 10^27 base units. */
private const val MAX_TOKEN_DIGITS = 27

/**
 * EPC / SEPA and crypto payment QR codes. One PAYMENT_MISMATCH signal whose [Signal.details] list what is
 * inconsistent, and CRYPTO_BAD_CHECKSUM when the displayed address fails its checksum. Valid but attacker-owned
 * destinations are unknowable locally (T-PAY): this only catches payloads that contradict themselves.
 */
object PaymentAnalyzer : SignalAnalyzer {
    override fun analyze(result: ParseResult.Ok, context: AnalysisContext): List<Signal> =
        when (val payload = result.payload) {
            is ScanPayload.Epc -> mismatch(epcProblems(payload), null)
            is ScanPayload.Crypto -> cryptoSignals(payload)
            else -> emptyList()
        }

    private fun mismatch(problems: List<PaymentProblem>, evidence: Untrusted?): List<Signal> {
        if (problems.isEmpty()) return emptyList()
        return listOf(Signal(SignalCode.PAYMENT_MISMATCH, evidence = evidence, details = problems))
    }

    private fun epcProblems(epc: ScanPayload.Epc): List<PaymentProblem> = buildList {
        if (EpcIssue.BAD_IBAN_FORMAT in epc.issues || EpcIssue.BAD_IBAN_CHECKSUM in epc.issues) {
            add(PaymentProblem.IBAN)
        }
        if (EpcIssue.BAD_BIC in epc.issues) add(PaymentProblem.BIC)
        if (EpcIssue.BAD_CURRENCY in epc.issues) add(PaymentProblem.CURRENCY)
        if (EpcIssue.BAD_AMOUNT in epc.issues) add(PaymentProblem.AMOUNT)
        if (epc.name == null) add(PaymentProblem.NAME)
    }

    private fun cryptoSignals(crypto: ScanPayload.Crypto): List<Signal> = buildList {
        if (CryptoAddresses.verify(crypto.coin, crypto.address.value) == false) {
            add(Signal(SignalCode.CRYPTO_BAD_CHECKSUM, evidence = crypto.address))
        }
        addAll(mismatch(cryptoProblems(crypto), recipientEvidence(crypto)))
    }

    /** The real destination of a token transfer, when the URI names one, so the user can compare it. */
    private fun recipientEvidence(crypto: ScanPayload.Crypto): Untrusted? =
        if (crypto.function?.value == "transfer") crypto.recipient else null

    private fun cryptoProblems(crypto: ScanPayload.Crypto): List<PaymentProblem> = buildList {
        val function = crypto.function?.value
        if (function == "transfer") {
            if (!hasDistinctValidRecipient(crypto)) add(PaymentProblem.RECIPIENT)
        } else if (function != null) {
            add(PaymentProblem.CONTRACT_CALL)
        }
        if (isHugeCoinAmount(crypto.amount)) add(PaymentProblem.AMOUNT)
        if (isHugeDigits(crypto.value, MAX_WEI_DIGITS)) add(PaymentProblem.VALUE)
        if (isHugeDigits(crypto.tokenAmount, MAX_TOKEN_DIGITS)) add(PaymentProblem.TOKEN_AMOUNT)
    }

    /**
     * In an ERC-20 `transfer` the path address is the contract and `address` the real recipient: the two are
     * different by design. A missing, malformed, badly checksummed or identical recipient is the anomaly.
     */
    private fun hasDistinctValidRecipient(crypto: ScanPayload.Crypto): Boolean {
        val recipient = crypto.recipient?.value ?: return false
        return CryptoAddresses.isEthereumAddress(recipient) &&
            CryptoAddresses.verify("ethereum", recipient) != false &&
            !recipient.equals(crypto.address.value, ignoreCase = true)
    }

    /** Anything that is not a short plain decimal number counts as huge: `1e999`, `-1`, `0x...` are not amounts. */
    private fun isHugeCoinAmount(amount: Untrusted?): Boolean {
        val text = amount?.value?.trim() ?: return false
        if (!isPlainDecimal(text)) return true
        val whole = text.substringBefore('.').trimStart('0')
        return whole.isNotEmpty() && (whole.toLongOrNull() ?: Long.MAX_VALUE) > MAX_COIN_AMOUNT
    }

    /** EIP-681 allows `2.5e18`; the integer digit count is taken after applying a short exponent. */
    private fun isHugeDigits(number: Untrusted?, maxDigits: Int): Boolean {
        val text = number?.value?.trim()?.lowercase(Locale.ROOT) ?: return false
        val mantissa = text.substringBefore('e')
        val exponent = if ('e' in text) text.substringAfter('e') else "0"
        val exponentOk = exponent.length in 1..MAX_EXPONENT_CHARS && exponent.all { it in '0'..'9' }
        if (text.length > MAX_NUMBER_CHARS || !exponentOk || !isPlainDecimal(mantissa)) return true
        val whole = mantissa.substringBefore('.').trimStart('0')
        return whole.isNotEmpty() && whole.length + exponent.toInt() > maxDigits
    }

    /** ASCII digits with at most one dot; no sign, no exponent, no spaces, bounded length. */
    private fun isPlainDecimal(text: String): Boolean {
        val digits = text.replace(".", "")
        val dots = text.length - digits.length
        return text.length <= MAX_NUMBER_CHARS && dots <= 1 && digits.isNotEmpty() && digits.all { it in '0'..'9' }
    }
}
