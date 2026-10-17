package com.lookfirst.domain.decoder

import com.lookfirst.domain.model.EpcIssue
import com.lookfirst.domain.model.ScanPayload
import java.math.BigDecimal

private const val MAX_EPC_LINES = 12
private const val LINE_VERSION = 1
private const val LINE_SERVICE = 3
private const val LINE_BIC = 4
private const val LINE_NAME = 5
private const val LINE_IBAN = 6
private const val LINE_AMOUNT = 7
private const val LINE_REFERENCE = 9
private const val LINE_TEXT = 10
private const val BCD_LENGTH = 3
private const val MAX_DECIMALS = 2
private const val BASE_100 = 100
private const val IBAN_MIN = 15
private const val IBAN_MAX = 34
private const val IBAN_MODULUS = 97
private const val ALPHA_OFFSET = 10
private const val MAX_AMOUNT_LENGTH = 15
private const val CURRENCY_LENGTH = 3
private const val BIC_SHORT = 8
private const val BIC_LONG = 11
private const val BIC_INSTITUTION = 6
private const val IBAN_PREFIX = 4
private const val DECIMAL_RADIX = 10

/** EPC069-12 "BCD" QR: one field per line (version, charset, service, BIC, name, IBAN, amount, purpose, ref, text). */
internal object EpcParser {
    private val VERSIONS = setOf("001", "002")

    fun looksLikeEpc(text: String): Boolean =
        text.startsWith("BCD") && text.length > BCD_LENGTH && (text[BCD_LENGTH] == '\n' || text[BCD_LENGTH] == '\r')

    fun parse(text: String): ScanPayload {
        val lines = text.split('\n').map { it.removeSuffix("\r") }
        val version = lines.getOrNull(LINE_VERSION).orEmpty()
        val validHeader =
            lines.size <= MAX_EPC_LINES && version in VERSIONS && lines.getOrNull(LINE_SERVICE) == "SCT"
        val iban = lines.getOrNull(LINE_IBAN)?.filter { it != ' ' }?.uppercase().orEmpty()
        if (!validHeader || iban.isEmpty()) return ScanPayload.Unknown(text, "EPC")

        val issues = LinkedHashSet<EpcIssue>()
        val bic = lines.getOrNull(LINE_BIC)?.trim()?.ifEmpty { null }
        if (bic != null && !isBic(bic)) issues += EpcIssue.BAD_BIC
        if (!isIbanFormat(iban)) {
            issues += EpcIssue.BAD_IBAN_FORMAT
        } else if (!hasValidIbanChecksum(iban)) {
            issues += EpcIssue.BAD_IBAN_CHECKSUM
        }
        val (currency, amount) = parseAmount(lines.getOrNull(LINE_AMOUNT).orEmpty().trim(), issues)
        return ScanPayload.Epc(
            raw = text,
            version = version,
            bic = bic,
            name = lines.getOrNull(LINE_NAME)?.ifEmpty { null },
            iban = iban,
            amount = amount,
            currency = currency,
            remittance =
            lines.getOrNull(LINE_TEXT)?.ifEmpty { null } ?: lines.getOrNull(LINE_REFERENCE)?.ifEmpty { null },
            issues = issues,
        )
    }

    /** `EUR12.34`: three capital letters then digits with at most two decimals. Empty means "to be filled in". */
    private fun parseAmount(field: String, issues: MutableSet<EpcIssue>): Pair<String?, BigDecimal?> {
        if (field.isEmpty()) return null to null
        val currency = field.take(CURRENCY_LENGTH)
        val number = field.drop(CURRENCY_LENGTH)
        val dot = number.indexOf('.')
        val intPart = if (dot < 0) number else number.substring(0, dot)
        val decimals = if (dot < 0) "" else number.substring(dot + 1)
        val valid =
            field.length <= MAX_AMOUNT_LENGTH &&
                currency.all { it in 'A'..'Z' } &&
                intPart.isNotEmpty() &&
                intPart.all { it in '0'..'9' } &&
                decimals.length <= MAX_DECIMALS &&
                decimals.all { it in '0'..'9' }
        if (!valid) {
            issues += EpcIssue.BAD_AMOUNT
            return currency.takeIf { it.length == CURRENCY_LENGTH && it.all { c -> c in 'A'..'Z' } } to null
        }
        return currency to BigDecimal(number)
    }

    private fun isBic(s: String): Boolean = (s.length == BIC_SHORT || s.length == BIC_LONG) &&
        s.all { it in 'A'..'Z' || it in '0'..'9' } &&
        s.take(BIC_INSTITUTION).all { it in 'A'..'Z' }

    private fun isIbanFormat(iban: String): Boolean = iban.length in IBAN_MIN..IBAN_MAX &&
        iban.take(2).all { it in 'A'..'Z' } &&
        iban.substring(2, IBAN_PREFIX).all { it in '0'..'9' } &&
        iban.all { it in 'A'..'Z' || it in '0'..'9' }

    /** ISO 7064 mod 97-10, computed digit by digit (no BigInteger). */
    internal fun hasValidIbanChecksum(iban: String): Boolean {
        val rearranged = iban.substring(IBAN_PREFIX) + iban.substring(0, IBAN_PREFIX)
        var remainder = 0
        for (c in rearranged) {
            val value = if (c in '0'..'9') c - '0' else c - 'A' + ALPHA_OFFSET
            remainder = if (value < DECIMAL_RADIX) remainder * DECIMAL_RADIX + value else remainder * BASE_100 + value
            remainder %= IBAN_MODULUS
        }
        return remainder == 1
    }
}
