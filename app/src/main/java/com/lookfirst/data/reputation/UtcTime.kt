package com.lookfirst.data.reputation

private const val STAMP_TEMPLATE = "dddd-dd-ddTdd:dd:00Z"
private const val MIN_YEAR = 2000
private const val MAX_YEAR = 2999
private const val DAYS_PER_ERA = 146_097L
private const val DAYS_TO_UNIX_EPOCH = 719_468L
private const val DAYS_PER_YEAR = 365
private const val YEARS_PER_ERA = 400L
private const val MONTHS_PER_YEAR = 12
private const val MARCH_SHIFT = 9
private const val MARCH_GROUP_DAYS = 153
private const val MARCH_GROUP_OFFSET = 2
private const val MARCH_GROUP_DIVISOR = 5
private const val LEAP_STEP = 4
private const val LEAP_CYCLE = 400
private val MONTH_LENGTHS = intArrayOf(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
private const val INSTANT_MIN_LENGTH = 20
private const val INSTANT_MAX_LENGTH = 40
private const val MAX_SECOND = 60
private const val SECOND = 1000L
private const val HOURS_PER_DAY = 24
private const val MINUTES_PER_HOUR = 60

/** Dates of the bundle manifest: only `YYYY-MM-DDThh:mm:00Z`, whole minutes, UTC. No `java.time` (API 26). */
@Suppress("MagicNumber", "TooManyFunctions") // calendar arithmetic: string positions and Gregorian constants
internal object UtcTime {
    const val MINUTE: Long = 60_000L
    const val HOUR: Long = MINUTE * MINUTES_PER_HOUR
    const val DAY: Long = HOUR * HOURS_PER_DAY

    /** Milliseconds since 1970-01-01T00:00:00Z of [text], or null if it is not exactly that shape and a real date. */
    fun parseMinuteStamp(text: String): Long? {
        if (!shapeIsValid(text)) return null
        val year = text.substring(0, 4).toInt()
        val month = text.substring(5, 7).toInt()
        val day = text.substring(8, 10).toInt()
        val hour = text.substring(11, 13).toInt()
        val minute = text.substring(14, 16).toInt()
        val inRange = year in MIN_YEAR..MAX_YEAR &&
            month in 1..MONTHS_PER_YEAR &&
            hour in 0 until HOURS_PER_DAY &&
            minute in 0 until MINUTES_PER_HOUR
        if (!inRange || day < 1 || day > daysInMonth(year, month)) return null
        return daysFromCivil(year, month, day) * DAY + hour * HOUR + minute * MINUTE
    }

    /**
     * Milliseconds since 1970-01-01T00:00:00Z of an RFC 3339 date-time (`2024-05-01T12:30:45.123+02:00`, `Z` or a
     * numeric offset, an optional fraction of one to nine digits that is ignored), or null for anything else or an
     * impossible date. Years 1970 to 2999. Linear, no regular expression, no `java.time` (API 26).
     */
    @Suppress("ReturnCount")
    fun parseInstant(text: String): Long? {
        if (text.length < INSTANT_MIN_LENGTH || text.length > INSTANT_MAX_LENGTH) return null
        val fields = (if (hasInstantSeparators(text)) instantFields(text) else null) ?: return null
        val offset = fractionEnd(text)?.let { offsetMinutes(text, it) } ?: return null
        val year = fields[0]
        val month = fields[1]
        val day = fields[2]
        val hour = fields[3]
        val minute = fields[4]
        val second = fields[5]
        val inRange = year in 1970..MAX_YEAR &&
            month in 1..MONTHS_PER_YEAR &&
            hour in 0 until HOURS_PER_DAY &&
            minute in 0 until MINUTES_PER_HOUR &&
            second in 0..MAX_SECOND
        if (!inRange || day < 1 || day > daysInMonth(year, month)) return null
        return daysFromCivil(year, month, day) * DAY + hour * HOUR + minute * MINUTE + second * SECOND - offset * MINUTE
    }

    /** Year, month, day, hour, minute, second of `YYYY-MM-DDThh:mm:ss`, or null if one is not all digits. */
    private fun instantFields(text: String): IntArray? {
        val at = intArrayOf(0, 5, 8, 11, 14, 17)
        val out = IntArray(at.size)
        for (i in at.indices) out[i] = number(text, at[i], if (i == 0) 4 else 2) ?: return null
        return out
    }

    /** Index just after the seconds and their optional fraction, or null for a fraction with no or too many digits. */
    private fun fractionEnd(text: String): Int? {
        var i = 19
        if (i < text.length && text[i] == '.') {
            val start = ++i
            while (i < text.length && text[i] in '0'..'9') i++
            if (i - start !in 1..9) return null
        }
        return i
    }

    /** The offset of an RFC 3339 time zone in minutes (`Z`, `+hh:mm`, `-hh:mm`) that ends [text] at [at], or null. */
    @Suppress("ReturnCount")
    private fun offsetMinutes(text: String, at: Int): Int? {
        val rest = text.length - at
        if (rest == 1 && text[at] in "Zz") return 0
        val sign = if (rest == 6) signOf(text[at]) else 0
        val hours = if (sign != 0 && text[at + 3] == ':') number(text, at + 1, 2) else null
        val minutes = if (hours != null) number(text, at + 4, 2) else null
        if (hours == null || minutes == null) return null
        if (hours >= HOURS_PER_DAY || minutes >= MINUTES_PER_HOUR) return null
        return sign * (hours * MINUTES_PER_HOUR + minutes)
    }

    private fun signOf(c: Char): Int = when (c) {
        '+' -> 1
        '-' -> -1
        else -> 0
    }

    /** `YYYY-MM-DDThh:mm:ss`: the dashes, the `T` (or `t`) and the colons, at their places. */
    private fun hasInstantSeparators(text: String): Boolean {
        val dashes = text[4] == '-' && text[7] == '-'
        val colons = text[13] == ':' && text[16] == ':'
        return dashes && colons && text[10] in "Tt"
    }

    /** The decimal number of [length] digits at [at], or null. */
    private fun number(text: String, at: Int, length: Int): Int? {
        var value = 0
        for (i in at until at + length) {
            val c = text[i]
            if (c !in '0'..'9') return null
            value = value * 10 + (c - '0')
        }
        return value
    }

    /** Milliseconds of `YYYY-MM-DDT00:00:00Z`, for constants. */
    fun midnight(year: Int, month: Int, day: Int): Long = daysFromCivil(year, month, day) * DAY

    private fun shapeIsValid(text: String): Boolean {
        if (text.length != STAMP_TEMPLATE.length) return false
        return STAMP_TEMPLATE.indices.all { i ->
            if (STAMP_TEMPLATE[i] == 'd') text[i] in '0'..'9' else text[i] == STAMP_TEMPLATE[i]
        }
    }

    private fun daysInMonth(year: Int, month: Int): Int {
        val leap = (year % LEAP_STEP == 0 && year % 100 != 0) || year % LEAP_CYCLE == 0
        return if (month == 2 && leap) MONTH_LENGTHS[1] + 1 else MONTH_LENGTHS[month - 1]
    }

    /** Days since 1970-01-01 of a proleptic Gregorian date (Howard Hinnant's algorithm). */
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = if (month <= 2) year - 1 else year
        val era = Math.floorDiv(y.toLong(), YEARS_PER_ERA)
        val yearOfEra = y - era * YEARS_PER_ERA
        val shifted = if (month > 2) month - 3 else month + MARCH_SHIFT
        val dayOfYear = (MARCH_GROUP_DAYS * shifted + MARCH_GROUP_OFFSET) / MARCH_GROUP_DIVISOR + day - 1
        val dayOfEra = yearOfEra * DAYS_PER_YEAR + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        return era * DAYS_PER_ERA + dayOfEra - DAYS_TO_UNIX_EPOCH
    }
}
