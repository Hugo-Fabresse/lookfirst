package com.lookfirst.domain.analysis

import com.lookfirst.domain.model.ParseResult
import com.lookfirst.domain.model.ScanPayload
import com.lookfirst.domain.model.Signal
import com.lookfirst.domain.model.SignalCode
import com.lookfirst.domain.model.Untrusted
import java.util.Locale

private const val SHORT_MIN = 3
private const val SHORT_MAX = 6
private const val ASCII_LIMIT = 0x80
private const val DECIMAL_RADIX = 10
private const val FR_REGION = "FR"
private const val FR_DIRECTORY_LENGTH = 6
private const val NATIONAL_PREMIUM_LENGTH = 10
private const val EU_HARMONISED_PREFIX = "116"

/**
 * International prefixes (with `+`) of premium-rate ranges, best effort: France `+33 8[1-9]`, UK `+44 9`,
 * Germany `+49 900` / `137`, North America `900` / `976`, Belgium, Spain, Italy, Switzerland, and the
 * international premium country codes.
 */
private val PREMIUM_PREFIXES =
    ('1'..'9').map { "+338$it" } +
        listOf(
            "+449", "+49900", "+49137", "+1900", "+1976", "+3290", "+34803", "+34806", "+34807", "+34905",
            "+39899", "+41900", "+41901", "+41906", "+882", "+883", "+870", "+979",
        )

/** Short numbers that are not premium: European emergency and helpline numbers. */
private val EMERGENCY_NUMBERS = setOf("112", "114", "115", "119")

/**
 * `tel:`, `sms:` and `mailto:`: USSD / MMI codes, premium-rate and short numbers, non-ASCII digits, SMS short
 * codes, prefilled message bodies and hidden recipients. National numbers need [AnalysisContext.regionHint]:
 * without it only international prefixes (`+33 89`) are recognised, and `089` + 7 digits is a CAUTION guess.
 */
object MessagingAnalyzer : SignalAnalyzer {
    override fun analyze(result: ParseResult.Ok, context: AnalysisContext): List<Signal> =
        when (val payload = result.payload) {
            is ScanPayload.Tel -> numberSignals(payload.number, context, isTel = true)
            is ScanPayload.Sms -> numberSignals(payload.number, context, isTel = false) + smsSignals(payload)
            is ScanPayload.Mail -> mailSignals(payload)
            else -> emptyList()
        }

    private fun mailSignals(mail: ScanPayload.Mail): List<Signal> =
        if (mail.bcc.isNotEmpty() || mail.body?.value?.isNotBlank() == true) {
            listOf(Signal(SignalCode.MAIL_PREFILLED))
        } else {
            emptyList()
        }

    private fun numberSignals(number: Untrusted, context: AnalysisContext, isTel: Boolean): List<Signal> {
        val value = number.value
        return buildList {
            // The whole decoded number: `tel:;*#06#` hides the code after the `;`.
            val ussd = value.any { it == '*' || it == '#' }
            if (ussd) add(Signal(SignalCode.USSD_CODE, evidence = number))
            if (value.any { it.code >= ASCII_LIMIT && Character.digit(it, DECIMAL_RADIX) >= 0 }) {
                add(Signal(SignalCode.NON_ASCII_DIGITS, evidence = number))
            }
            if (!ussd) classify(compact(value), number, context, isTel)?.let { add(it) }
        }
    }

    private fun classify(compact: String, number: Untrusted, context: AnalysisContext, isTel: Boolean): Signal? {
        val region = context.regionHint?.uppercase(Locale.ROOT)
        val code =
            when {
                PREMIUM_PREFIXES.any { compact.startsWith(it) } -> SignalCode.PREMIUM_NUMBER
                region == FR_REGION && isFrenchPremium(compact) -> SignalCode.PREMIUM_NUMBER
                region == null && isNationalPremiumGuess(compact) -> SignalCode.POSSIBLE_PREMIUM_NUMBER
                isTel && isShortNumber(compact) -> SignalCode.SHORT_NUMBER
                else -> return null
            }
        return Signal(code, evidence = number)
    }

    /** France: `08[1-9]` (non-free service numbers) and the `118 xxx` directory numbers. */
    private fun isFrenchPremium(compact: String): Boolean {
        val digits = compact.all { it in '0'..'9' }
        val service = compact.length > 2 && compact.startsWith("08") && compact[2] in '1'..'9'
        val directory = compact.length == FR_DIRECTORY_LENGTH && compact.startsWith("118")
        return digits && (service || directory)
    }

    /** `089` + 7 digits: premium in France, Munich's area code in Germany. Only a CAUTION guess without region. */
    private fun isNationalPremiumGuess(compact: String): Boolean =
        compact.length == NATIONAL_PREMIUM_LENGTH && compact.startsWith("089") && compact.all { it in '0'..'9' }

    private fun isShortNumber(compact: String): Boolean {
        val short = compact.length in SHORT_MIN..SHORT_MAX && compact.all { it in '0'..'9' }
        val emergency = compact in EMERGENCY_NUMBERS ||
            (compact.length == SHORT_MAX && compact.startsWith(EU_HARMONISED_PREFIX))
        return short && !emergency
    }

    private fun smsSignals(sms: ScanPayload.Sms): List<Signal> = buildList {
        val compact = compact(sms.number.value)
        if (compact.length in SHORT_MIN..SHORT_MAX && compact.all { it in '0'..'9' }) {
            add(Signal(SignalCode.SMS_SHORT_CODE, evidence = sms.number))
        }
        val body = sms.body
        if (body != null && body.value.isNotBlank()) add(Signal(SignalCode.SMS_PREFILLED_BODY, evidence = body))
    }

    /**
     * Keeps digits (any script, mapped to ASCII with [Character.digit]), `+`, `*`, `#`; drops separators and
     * RFC 3966 parameters (`;ext=`); a leading `00` becomes `+`.
     */
    private fun compact(number: String): String {
        val kept = buildString {
            for (c in number.substringBefore(';')) {
                val digit = Character.digit(c, DECIMAL_RADIX)
                when {
                    digit >= 0 -> append(('0' + digit))
                    c == '+' -> append(c)
                }
            }
        }
        return if (kept.startsWith("00")) "+" + kept.substring(2) else kept
    }
}
