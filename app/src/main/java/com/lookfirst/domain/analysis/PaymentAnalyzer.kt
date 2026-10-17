package com.lookfirst.domain.analysis

import com.lookfirst.domain.model.EpcIssue
import com.lookfirst.domain.model.ParseResult
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
 * EPC / SEPA and crypto payment QR codes. One PAYMENT_MISMATCH signal whose evidence lists what is inconsistent
 * (fixed tokens such as `currency,bic`, never scanned text). Valid but attacker-owned destinations are unknowable
 * locally (T-PAY): this only catches payloads that contradict themselves or hide the recipient.
 */
object PaymentAnalyzer : SignalAnalyzer {
    override fun analyze(result: ParseResult.Ok): List<Signal> {
        val problems =
            when (val payload = result.payload) {
                is ScanPayload.Epc -> epcProblems(payload)
                is ScanPayload.Crypto -> cryptoProblems(payload)
                else -> return emptyList()
            }
        return if (problems.isEmpty()) {
            emptyList()
        } else {
            listOf(Signal(SignalCode.PAYMENT_MISMATCH, evidence = Untrusted(problems.joinToString(","))))
        }
    }

    private fun epcProblems(epc: ScanPayload.Epc): List<String> = buildList {
        if (EpcIssue.BAD_IBAN_FORMAT in epc.issues || EpcIssue.BAD_IBAN_CHECKSUM in epc.issues) add("iban")
        if (EpcIssue.BAD_BIC in epc.issues) add("bic")
        if (EpcIssue.BAD_CURRENCY in epc.issues) add("currency")
        if (EpcIssue.BAD_AMOUNT in epc.issues) add("amount")
        if (epc.name == null) add("name")
    }

    private fun cryptoProblems(crypto: ScanPayload.Crypto): List<String> = buildList {
        val function = crypto.function?.value
        if (function == "transfer") {
            add("token-transfer")
            val recipient = crypto.recipient
            if (recipient == null || !recipient.value.equals(crypto.address.value, ignoreCase = true)) add("recipient")
        } else if (function != null) {
            add("contract-call")
        }
        if (isHugeCoinAmount(crypto.amount)) add("amount")
        if (isHugeDigits(crypto.value, MAX_WEI_DIGITS)) add("value")
        if (isHugeDigits(crypto.tokenAmount, MAX_TOKEN_DIGITS)) add("token-amount")
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

    /** Digits with at most one dot; no sign, no exponent, no spaces, bounded length. */
    private fun isPlainDecimal(text: String): Boolean {
        val digits = text.replace(".", "")
        val dots = text.length - digits.length
        return text.length <= MAX_NUMBER_CHARS && dots <= 1 && digits.isNotEmpty() && digits.all { it in '0'..'9' }
    }
}
