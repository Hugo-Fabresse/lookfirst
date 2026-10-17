package com.lookfirst.domain.analysis

import com.lookfirst.domain.model.ParseResult
import com.lookfirst.domain.model.ScanPayload
import com.lookfirst.domain.model.Signal
import com.lookfirst.domain.model.SignalCode
import com.lookfirst.domain.model.Untrusted
import java.util.Locale

private val USUAL_ALGORITHMS = setOf("SHA1", "SHA256", "SHA512")
private val USUAL_DIGITS = setOf("6", "8")
private val USUAL_PERIODS = setOf("30", "60")

/**
 * `otpauth://` always adds a second-factor account (OTPAUTH_ENROL): a service only shows such a code while the
 * user sets it up. Unusual parameters are listed by name in the evidence of OTPAUTH_ODD_PARAMS.
 */
object OtpauthAnalyzer : SignalAnalyzer {
    override fun analyze(result: ParseResult.Ok): List<Signal> {
        val otp = result.payload as? ScanPayload.Otpauth ?: return emptyList()
        val odd = oddParams(otp)
        return buildList {
            add(Signal(SignalCode.OTPAUTH_ENROL, evidence = otp.issuer ?: otp.account))
            if (odd.isNotEmpty()) {
                add(Signal(SignalCode.OTPAUTH_ODD_PARAMS, evidence = Untrusted(odd.joinToString(","))))
            }
        }
    }

    private fun oddParams(otp: ScanPayload.Otpauth): List<String> = buildList {
        if (otp.type == "hotp") add("type")
        if (isOdd(otp, "algorithm", USUAL_ALGORITHMS)) add("algorithm")
        if (isOdd(otp, "digits", USUAL_DIGITS)) add("digits")
        if (isOdd(otp, "period", USUAL_PERIODS)) add("period")
        val issuer = otp.issuer
        val label = otp.labelIssuer
        if (issuer != null && label != null && !issuer.value.equals(label.value, ignoreCase = true)) add("issuer")
    }

    private fun isOdd(otp: ScanPayload.Otpauth, name: String, usual: Set<String>): Boolean {
        val param = otp.params.firstOrNull { it.name.lowercase(Locale.ROOT) == name } ?: return false
        return param.value.value.trim().uppercase(Locale.ROOT) !in usual
    }
}
