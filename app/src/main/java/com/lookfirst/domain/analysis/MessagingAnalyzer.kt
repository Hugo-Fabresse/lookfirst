package com.lookfirst.domain.analysis

import com.lookfirst.domain.model.ParseResult
import com.lookfirst.domain.model.ScanPayload
import com.lookfirst.domain.model.Signal
import com.lookfirst.domain.model.SignalCode
import com.lookfirst.domain.model.Untrusted

private const val SHORT_CODE_MIN = 3
private const val SHORT_CODE_MAX = 6

/**
 * International prefixes (with `+`) of premium-rate ranges, best effort. National numbers (`0899...`, `09...`) are
 * not matched: without the country, `089` is Munich and `09` a Freebox line. Sources: national numbering plans.
 */
private val PREMIUM_PREFIXES =
    listOf(
        "+3389", "+449", "+49900", "+49137", "+1900", "+1976", "+3290", "+34803", "+34806", "+34807", "+34905",
        "+39899", "+41900", "+41901", "+41906", "+882", "+883", "+870", "+979",
    )

/** `tel:` and `sms:`: USSD / MMI codes, premium-rate numbers, SMS short codes and prefilled message bodies. */
object MessagingAnalyzer : SignalAnalyzer {
    override fun analyze(result: ParseResult.Ok): List<Signal> = when (val payload = result.payload) {
        is ScanPayload.Tel -> numberSignals(payload.number)
        is ScanPayload.Sms -> numberSignals(payload.number) + smsSignals(payload)
        else -> emptyList()
    }

    private fun numberSignals(number: Untrusted): List<Signal> {
        val compact = compact(number.value)
        return when {
            compact.any { it == '*' || it == '#' } -> listOf(Signal(SignalCode.USSD_CODE, evidence = number))
            PREMIUM_PREFIXES.any { compact.startsWith(it) } ->
                listOf(Signal(SignalCode.PREMIUM_NUMBER, evidence = number))
            else -> emptyList()
        }
    }

    private fun smsSignals(sms: ScanPayload.Sms): List<Signal> = buildList {
        val compact = compact(sms.number.value)
        if (compact.length in SHORT_CODE_MIN..SHORT_CODE_MAX && compact.all { it in '0'..'9' }) {
            add(Signal(SignalCode.SMS_SHORT_CODE, evidence = sms.number))
        }
        val body = sms.body
        if (body != null && body.value.isNotBlank()) add(Signal(SignalCode.SMS_PREFILLED_BODY, evidence = body))
    }

    /** Keeps digits, `+`, `*`, `#`; drops separators and RFC 3966 parameters (`;ext=`); `00` becomes `+`. */
    private fun compact(number: String): String {
        val kept = number.substringBefore(';').filter { it in '0'..'9' || it == '+' || it == '*' || it == '#' }
        return if (kept.startsWith("00")) "+" + kept.substring(2) else kept
    }
}
