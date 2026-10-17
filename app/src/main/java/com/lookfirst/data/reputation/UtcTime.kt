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
private const val HOURS_PER_DAY = 24
private const val MINUTES_PER_HOUR = 60

/** Dates of the bundle manifest: only `YYYY-MM-DDThh:mm:00Z`, whole minutes, UTC. No `java.time` (API 26). */
@Suppress("MagicNumber") // calendar arithmetic: string positions and Gregorian constants
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
