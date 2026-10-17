package com.lookfirst.domain.model

/** How much a signal weighs. The verdict level is the maximum, [INFO] excluded (ux.md 4.1). */
enum class Severity { DANGEROUS, SUSPICIOUS, CAUTION, INFO }

/**
 * Stable reason codes (ux.md 7.3; string keys `reason_<code>` and `reason_<code>_short`). [severity] is the default
 * weight of the code. Codes marked "new" are not in the ux.md table yet: the UI ticket must add their texts.
 */
enum class SignalCode(val severity: Severity) {
    REPUTATION_LISTED(Severity.DANGEROUS),
    DANGEROUS_SCHEME(Severity.DANGEROUS),
    BIDI_IN_HOST(Severity.DANGEROUS),
    BRAND_LOOKALIKE(Severity.SUSPICIOUS),
    MIXED_SCRIPT(Severity.SUSPICIOUS),
    USERINFO_AT(Severity.SUSPICIOUS),
    IP_HOST(Severity.SUSPICIOUS),
    HIDDEN_CHARS(Severity.SUSPICIOUS),
    PAYMENT_MISMATCH(Severity.SUSPICIOUS),
    OTPAUTH_ENROL(Severity.SUSPICIOUS),
    SHORTENER(Severity.CAUTION),
    PLAIN_HTTP(Severity.CAUTION),
    LONG_URL(Severity.CAUTION),
    UNUSUAL_PORT(Severity.CAUTION),
    OPEN_WIFI(Severity.CAUTION),
    UNKNOWN_SCHEME(Severity.CAUTION),
    NON_BROWSER_HANDLER(Severity.CAUTION),
    LISTS_STALE(Severity.CAUTION),
    LISTS_MISSING(Severity.CAUTION),
    UNPARSABLE(Severity.CAUTION),
    REDIRECT_HTTP(Severity.SUSPICIOUS),
    REDIRECT_FINAL(Severity.INFO),
    DOMAIN_RECENT(Severity.SUSPICIOUS),

    /** New. Scheme missing, odd slashes or backslashes: the URL is not written the way a browser form expects. */
    URL_ODD_FORMAT(Severity.CAUTION),

    /** New. Percent-encoded host: a host never needs it, it hides the name from a reader. */
    URL_HOST_ENCODED(Severity.SUSPICIOUS),

    /** New. Empty or too long host, forbidden host characters, invalid port, control characters or spaces. */
    URL_MALFORMED(Severity.SUSPICIOUS),

    /** New. WEP network: broken encryption. */
    WIFI_WEP(Severity.CAUTION),

    /** New. otpauth with an unusual algorithm, digit count, period, type or issuer / label mismatch. */
    OTPAUTH_ODD_PARAMS(Severity.CAUTION),

    /** New. `tel:` / `sms:` number with `*` or `#`: a USSD / MMI code that the dialer would run. */
    USSD_CODE(Severity.SUSPICIOUS),

    /** New. International number in a known premium-rate range (best effort). */
    PREMIUM_NUMBER(Severity.SUSPICIOUS),

    /** New. SMS to a 3 to 6 digit short code (often a paid service). */
    SMS_SHORT_CODE(Severity.CAUTION),

    /** New. SMS with a prefilled body. */
    SMS_PREFILLED_BODY(Severity.CAUTION),
}

/**
 * One finding. [evidence] is the fragment of scanned content to highlight (for example the real host), [count] the
 * number for plural texts. Neither is printed by [toString]: scanned content never reaches logs.
 */
data class Signal(
    val code: SignalCode,
    val severity: Severity = code.severity,
    val evidence: Untrusted? = null,
    val count: Int = 0,
) {
    override fun toString(): String =
        "Signal($code, $severity, evidenceLen=${evidence?.value?.length ?: 0}, count=$count)"
}
