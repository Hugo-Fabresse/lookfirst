package com.lookfirst.data.reputation

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private const val IMF_LENGTH = 29
private const val MIN_YEAR = 2000
private const val MAX_YEAR = 2999
private const val MAX_DAY = 31
private const val HOURS_PER_DAY = 24
private const val MINUTES_PER_HOUR = 60
private const val SECOND = 1000L
private const val IMF_PATTERN = "EEE, dd MMM yyyy HH:mm:ss 'GMT'"
private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/**
 * The only HTTP date form the app accepts or sends: IMF-fixdate (RFC 9110), `Sat, 17 Oct 2026 09:20:06 GMT`. The
 * publisher's `Last-Modified` is parsed strictly into an instant and never kept as text; `If-Modified-Since` is
 * formatted by the app from that instant, so no byte chosen by the server is ever sent back.
 */
@Suppress("MagicNumber") // fixed string positions of the IMF-fixdate layout
internal object HttpDate {
    /** Milliseconds since the epoch, or null unless [text] is exactly an IMF-fixdate of a real date and weekday. */
    fun parse(text: String): Long? {
        if (text.length != IMF_LENGTH || !text.endsWith(" GMT") || !hasSeparators(text)) return null
        val month = MONTHS.indexOf(text.substring(8, 11)) + 1
        val fields = listOf(5 to 2, 12 to 4, 17 to 2, 20 to 2, 23 to 2).map { (at, length) -> number(text, at, length) }
        val v = fields.map { it ?: return null }
        if (!inRange(month, v[0], v[1], v[2], v[3], v[4])) return null
        val millis = UtcTime.midnight(v[1], month, v[0]) + v[2] * UtcTime.HOUR + v[3] * UtcTime.MINUTE + v[4] * SECOND
        // The round trip rejects 30 February, a wrong weekday and any other spelling.
        return millis.takeIf { format(it) == text }
    }

    private fun inRange(month: Int, day: Int, year: Int, hour: Int, minute: Int, second: Int): Boolean = month > 0 &&
        day in 1..MAX_DAY &&
        year in MIN_YEAR..MAX_YEAR &&
        hour < HOURS_PER_DAY &&
        minute < MINUTES_PER_HOUR &&
        second < MINUTES_PER_HOUR

    fun format(millis: Long): String {
        val formatter = SimpleDateFormat(IMF_PATTERN, Locale.US)
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        return formatter.format(Date(millis))
    }

    private fun hasSeparators(text: String): Boolean = text[3] == ',' &&
        text[4] == ' ' &&
        text[7] == ' ' &&
        text[11] == ' ' &&
        text[16] == ' ' &&
        text[19] == ':' &&
        text[22] == ':'

    private fun number(text: String, at: Int, length: Int): Int? {
        val digits = text.substring(at, at + length)
        return if (digits.all { it in '0'..'9' }) digits.toInt() else null
    }
}
