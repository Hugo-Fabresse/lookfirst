package com.lookfirst.ui.screens.result

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.lookfirst.R
import com.lookfirst.domain.model.LookalikeKind
import com.lookfirst.domain.model.MixedScripts
import com.lookfirst.domain.model.OtpParam
import com.lookfirst.domain.model.PaymentProblem
import com.lookfirst.domain.model.Signal
import com.lookfirst.domain.model.SignalCode
import com.lookfirst.domain.model.SignalDetail
import com.lookfirst.domain.model.Untrusted
import java.util.Locale

/** Longest piece of scanned text put inside a sentence; the exact content stays available below. */
private const val MAX_EVIDENCE_CHARS = 80
private const val ELLIPSIS = "…"

/** First Strong Isolate / Pop Directional Isolate: a right-to-left name cannot reorder the words around it. */
private val ISOLATE_START = Character.toString(0x2068)
private val ISOLATE_END = Character.toString(0x2069)

/** The sentence of a reason: a fixed text, or a plural driven by [Signal.count]. */
internal sealed interface SentenceRes {
    data class Text(@StringRes val id: Int) : SentenceRes

    data class Counted(@PluralsRes val id: Int) : SentenceRes
}

internal class ReasonRes(@StringRes val shortLabel: Int, val sentence: SentenceRes)

private fun reason(@StringRes short: Int, @StringRes sentence: Int) = ReasonRes(short, SentenceRes.Text(sentence))

private fun counted(@StringRes short: Int, @PluralsRes sentence: Int) = ReasonRes(short, SentenceRes.Counted(sentence))

/** Every [SignalCode] has an entry (checked by a test): the texts of ux.md 7.3 plus the codes added since. */
private val REASON_TABLE: Map<SignalCode, ReasonRes> = mapOf(
    SignalCode.REPUTATION_LISTED to reason(R.string.reason_reputation_listed_short, R.string.reason_reputation_listed),
    SignalCode.DANGEROUS_SCHEME to reason(R.string.reason_dangerous_scheme_short, R.string.reason_dangerous_scheme),
    SignalCode.BIDI_IN_HOST to reason(R.string.reason_bidi_in_host_short, R.string.reason_bidi_in_host),
    SignalCode.BIDI_IN_URL to reason(R.string.reason_bidi_in_url_short, R.string.reason_bidi_in_url),
    SignalCode.BRAND_LOOKALIKE to reason(R.string.reason_brand_lookalike_short, R.string.reason_brand_lookalike),
    SignalCode.MIXED_SCRIPT to reason(R.string.reason_mixed_script_short, R.string.reason_mixed_script),
    SignalCode.WHOLE_SCRIPT_CONFUSABLE to reason(
        R.string.reason_whole_script_confusable_short,
        R.string.reason_whole_script_confusable,
    ),
    SignalCode.USERINFO_AT to reason(R.string.reason_userinfo_at_short, R.string.reason_userinfo_at),
    SignalCode.IP_HOST to reason(R.string.reason_ip_host_short, R.string.reason_ip_host),
    SignalCode.HIDDEN_CHARS to counted(R.string.reason_hidden_chars_short, R.plurals.reason_hidden_chars),
    SignalCode.PAYMENT_MISMATCH to reason(R.string.reason_payment_mismatch_short, R.string.reason_payment_mismatch),
    SignalCode.CRYPTO_BAD_CHECKSUM to reason(
        R.string.reason_crypto_bad_checksum_short,
        R.string.reason_crypto_bad_checksum,
    ),
    SignalCode.OTPAUTH_ENROL to reason(R.string.reason_otpauth_enrol_short, R.string.reason_otpauth_enrol),
    SignalCode.OTPAUTH_ODD_PARAMS to reason(
        R.string.reason_otpauth_odd_params_short,
        R.string.reason_otpauth_odd_params,
    ),
    SignalCode.USSD_CODE to reason(R.string.reason_ussd_code_short, R.string.reason_ussd_code),
    SignalCode.PREMIUM_NUMBER to reason(R.string.reason_premium_number_short, R.string.reason_premium_number),
    SignalCode.POSSIBLE_PREMIUM_NUMBER to reason(
        R.string.reason_possible_premium_number_short,
        R.string.reason_possible_premium_number,
    ),
    SignalCode.SHORT_NUMBER to reason(R.string.reason_short_number_short, R.string.reason_short_number),
    SignalCode.SMS_SHORT_CODE to reason(R.string.reason_sms_short_code_short, R.string.reason_sms_short_code),
    SignalCode.SMS_PREFILLED_BODY to reason(
        R.string.reason_sms_prefilled_body_short,
        R.string.reason_sms_prefilled_body,
    ),
    SignalCode.MAIL_PREFILLED to reason(R.string.reason_mail_prefilled_short, R.string.reason_mail_prefilled),
    SignalCode.NON_ASCII_DIGITS to reason(R.string.reason_non_ascii_digits_short, R.string.reason_non_ascii_digits),
    SignalCode.SHORTENER to reason(R.string.reason_shortener_short, R.string.reason_shortener),
    SignalCode.PLAIN_HTTP to reason(R.string.reason_plain_http_short, R.string.reason_plain_http),
    SignalCode.LONG_URL to reason(R.string.reason_long_url_short, R.string.reason_long_url),
    SignalCode.UNUSUAL_PORT to reason(R.string.reason_unusual_port_short, R.string.reason_unusual_port),
    SignalCode.URL_ODD_FORMAT to reason(R.string.reason_url_odd_format_short, R.string.reason_url_odd_format),
    SignalCode.URL_HOST_ENCODED to reason(R.string.reason_url_host_encoded_short, R.string.reason_url_host_encoded),
    SignalCode.URL_MALFORMED to reason(R.string.reason_url_malformed_short, R.string.reason_url_malformed),
    SignalCode.OPEN_WIFI to reason(R.string.reason_open_wifi_short, R.string.reason_open_wifi),
    SignalCode.WIFI_WEP to reason(R.string.reason_wifi_wep_short, R.string.reason_wifi_wep),
    SignalCode.WIFI_UNKNOWN_SECURITY to reason(
        R.string.reason_wifi_unknown_security_short,
        R.string.reason_wifi_unknown_security,
    ),
    SignalCode.UNKNOWN_SCHEME to reason(R.string.reason_unknown_scheme_short, R.string.reason_unknown_scheme),
    SignalCode.NON_BROWSER_HANDLER to reason(
        R.string.reason_non_browser_handler_short,
        R.string.reason_non_browser_handler,
    ),
    SignalCode.LISTS_STALE to counted(R.string.reason_lists_stale_short, R.plurals.reason_lists_stale),
    SignalCode.LISTS_MISSING to reason(R.string.reason_lists_missing_short, R.string.reason_lists_missing),
    SignalCode.LISTS_UNAVAILABLE to reason(R.string.reason_lists_unavailable_short, R.string.reason_lists_unavailable),
    SignalCode.ANALYSIS_INCOMPLETE to reason(
        R.string.reason_analysis_incomplete_short,
        R.string.reason_analysis_incomplete,
    ),
    SignalCode.UNPARSABLE to reason(R.string.reason_unparsable_short, R.string.reason_unparsable),
    SignalCode.REDIRECT_HTTP to reason(R.string.reason_redirect_http_short, R.string.reason_redirect_http),
    SignalCode.REDIRECT_PRIVATE to reason(R.string.reason_redirect_private_short, R.string.reason_redirect_private),
    SignalCode.REDIRECT_EXCESSIVE to reason(
        R.string.reason_redirect_excessive_short,
        R.string.reason_redirect_excessive,
    ),
    SignalCode.REDIRECT_FINAL to counted(R.string.reason_redirect_final_short, R.plurals.reason_redirect_final),
    SignalCode.DOMAIN_RECENT to counted(R.string.reason_domain_recent_short, R.plurals.reason_domain_recent),
    SignalCode.DOMAIN_UNKNOWN to reason(R.string.reason_domain_unknown_short, R.string.reason_domain_unknown),
)

/** The three sentences of a brand look-alike, by how the host imitates the brand. */
private val BRAND_SENTENCES: Map<LookalikeKind, Int> = mapOf(
    LookalikeKind.HOMOGLYPH to R.string.reason_brand_lookalike_homoglyph,
    LookalikeKind.TYPO to R.string.reason_brand_lookalike_typo,
    LookalikeKind.COMBINED to R.string.reason_brand_lookalike_combined,
)

private val PAYMENT_WORDS: Map<SignalDetail, Int> = mapOf(
    PaymentProblem.IBAN to R.string.payment_problem_iban,
    PaymentProblem.BIC to R.string.payment_problem_bic,
    PaymentProblem.CURRENCY to R.string.payment_problem_currency,
    PaymentProblem.AMOUNT to R.string.payment_problem_amount,
    PaymentProblem.NAME to R.string.payment_problem_name,
    PaymentProblem.RECIPIENT to R.string.payment_problem_recipient,
    PaymentProblem.CONTRACT_CALL to R.string.payment_problem_contract_call,
    PaymentProblem.VALUE to R.string.payment_problem_value,
    PaymentProblem.TOKEN_AMOUNT to R.string.payment_problem_token_amount,
)

private val OTP_WORDS: Map<SignalDetail, Int> = mapOf(
    OtpParam.TYPE to R.string.otp_param_type,
    OtpParam.ALGORITHM to R.string.otp_param_algorithm,
    OtpParam.DIGITS to R.string.otp_param_digits,
    OtpParam.PERIOD to R.string.otp_param_period,
    OtpParam.ISSUER to R.string.otp_param_issuer,
)

/** Unicode script names as the embedded data gives them; a script not listed is shown under its own name. */
private val SCRIPT_WORDS: Map<String, Int> = mapOf(
    "latin" to R.string.script_latin,
    "cyrillic" to R.string.script_cyrillic,
    "greek" to R.string.script_greek,
    "armenian" to R.string.script_armenian,
    "hebrew" to R.string.script_hebrew,
    "arabic" to R.string.script_arabic,
    "han" to R.string.script_han,
    "hiragana" to R.string.script_hiragana,
    "katakana" to R.string.script_katakana,
    "hangul" to R.string.script_hangul,
    "thai" to R.string.script_thai,
    "devanagari" to R.string.script_devanagari,
    "bopomofo" to R.string.script_bopomofo,
)

/** Style of the evidence inside a sentence: bold and underlined, never colour alone (ux.md 8). */
private val EVIDENCE_STYLE = SpanStyle(fontWeight = FontWeight.Bold, textDecoration = TextDecoration.Underline)

/** The resource entry of [code]; null only if a new code was added without its texts (a test fails then). */
internal fun SignalCode.reasonRes(): ReasonRes? = REASON_TABLE[this]

/** The codes that have no texts yet. Must stay empty. */
internal fun codesWithoutTexts(): List<SignalCode> = SignalCode.entries.filter { it !in REASON_TABLE }

/**
 * Turns a [Signal] into words with the typed details translated. The scanned evidence is the only free text
 * that enters a sentence: it is cut, its invisible characters are drawn as tokens, and it is isolated for bidi.
 */
internal class ReasonTexts(private val resources: Resources) {
    fun shortLabel(signal: Signal): String = signal.code.reasonRes()?.let { resources.getString(it.shortLabel) }
        ?: resources.getString(R.string.reason_unparsable_short)

    fun sentence(signal: Signal): AnnotatedString {
        val evidence = evidenceText(signal.evidence)
        val full = format(signal, evidence ?: resources.getString(R.string.reason_unknown_value))
        val start = evidence?.let { full.indexOf(it) } ?: -1
        return buildAnnotatedString {
            append(full)
            if (start >= 0 && evidence != null) addStyle(EVIDENCE_STYLE, start, start + evidence.length)
        }
    }

    private fun format(signal: Signal, evidence: String): String {
        val entry = signal.code.reasonRes() ?: return resources.getString(R.string.reason_unparsable)
        return when (val sentence = entry.sentence) {
            is SentenceRes.Counted ->
                resources.formatQuantity(sentence.id, signal.count, countedArgs(signal, evidence))
            is SentenceRes.Text ->
                resources.formatString(
                    brandSentence(signal) ?: directPrivateSentence(signal) ?: sentence.id,
                    textArgs(signal, evidence),
                )
        }
    }

    /** A redirect to a private address says "this link" when the scanned address itself is the private one. */
    private fun directPrivateSentence(signal: Signal): Int? = if (signal.code == SignalCode.REDIRECT_PRIVATE &&
        signal.count == 0
    ) {
        R.string.reason_redirect_private_direct
    } else {
        null
    }

    private fun brandSentence(signal: Signal): Int? = if (signal.code == SignalCode.BRAND_LOOKALIKE) {
        signal.details.filterIsInstance<LookalikeKind>().firstOrNull()?.let { BRAND_SENTENCES[it] }
    } else {
        null
    }

    private fun countedArgs(signal: Signal, evidence: String): List<Any> = when (signal.code) {
        SignalCode.REDIRECT_FINAL -> listOf(signal.count, evidence)
        else -> listOf(signal.count)
    }

    private fun textArgs(signal: Signal, evidence: String): List<Any> = when (signal.code) {
        SignalCode.MIXED_SCRIPT, SignalCode.WHOLE_SCRIPT_CONFUSABLE -> listOf(scripts(signal), evidence)
        SignalCode.PAYMENT_MISMATCH -> listOf(joinWords(signal.details.mapNotNull { PAYMENT_WORDS[it] }))
        SignalCode.OTPAUTH_ODD_PARAMS -> listOf(joinWords(signal.details.mapNotNull { OTP_WORDS[it] }))
        else -> listOf(evidence)
    }

    private fun scripts(signal: Signal): String = signal.details
        .filterIsInstance<MixedScripts>()
        .flatMap { it.scripts }
        .joinToString(resources.getString(R.string.reason_list_separator)) { scriptName(it) }

    private fun scriptName(script: String): String =
        SCRIPT_WORDS[script.lowercase(Locale.ROOT)]?.let { resources.getString(it) } ?: script

    private fun joinWords(ids: List<Int>): String =
        ids.joinToString(resources.getString(R.string.reason_list_separator)) {
            resources.getString(it)
        }

    /** Evidence ready to print: null when there is none. Invisible characters become tokens, length is capped. */
    private fun evidenceText(evidence: Untrusted?): String? {
        val value = evidence?.value?.takeIf { it.isNotEmpty() } ?: return null
        var end = minOf(value.length, MAX_EVIDENCE_CHARS)
        if (end < value.length && Character.isHighSurrogate(value[end - 1])) end--
        val shown = segmentExact(value.substring(0, end)).joinToString("") {
            when (it) {
                is ExactSegment.Plain -> it.text
                is ExactSegment.Hidden -> it.label
            }
        }
        val suffix = if (end < value.length) ELLIPSIS else ""
        return ISOLATE_START + shown + suffix + ISOLATE_END
    }
}

/** At most two placeholders are ever used: a `when` instead of a spread (detekt SpreadOperator). */
private fun Resources.formatString(@StringRes id: Int, args: List<Any>): String = when (args.size) {
    0 -> getString(id)
    1 -> getString(id, args[0])
    else -> getString(id, args[0], args[1])
}

private fun Resources.formatQuantity(@PluralsRes id: Int, quantity: Int, args: List<Any>): String = when (args.size) {
    1 -> getQuantityString(id, quantity, args[0])
    else -> getQuantityString(id, quantity, args[0], args[1])
}
