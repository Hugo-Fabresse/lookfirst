package com.lookfirst.domain.host

private const val HEX_RADIX = 16

/** UTS #46 status of a code point; [mapping] is only meaningful for [MAPPED] and [STD3_MAPPED]. */
internal enum class IdnaStatus { VALID, IGNORED, MAPPED, DEVIATION, STD3_VALID, STD3_MAPPED }

internal class IdnaEntry(val status: IdnaStatus, val mapping: IntArray)

/** Bidi_Class values that UTS #46 / RFC 5893 care about; everything else is [OTHER]. */
internal enum class BidiClass { L, R, AL, EN, ES, ET, AN, CS, NSM, BN, ON, OTHER }

/** Joining_Type values used by the CheckJoiners rule (ZWNJ context). */
internal enum class JoiningType { D, L, R, T }

/**
 * Unicode properties embedded as generated resources (scripts/gen-host-data.py), so that the result does not
 * depend on the Unicode version of the Android runtime. All tables are loaded together, once, on first use.
 */
internal class UnicodeTables private constructor(
    private val idna: RangeTable<IdnaEntry>,
    private val bidi: RangeTable<BidiClass>,
    private val joining: RangeTable<JoiningType>,
    private val virama: RangeTable<Boolean>,
    private val marks: RangeTable<Boolean>,
) {
    /** Null when the code point is disallowed (or unassigned). */
    fun idnaEntry(codePoint: Int): IdnaEntry? = idna[codePoint]

    fun bidiClass(codePoint: Int): BidiClass = bidi[codePoint] ?: BidiClass.OTHER

    fun joiningType(codePoint: Int): JoiningType? = joining[codePoint]

    fun isVirama(codePoint: Int): Boolean = virama[codePoint] == true

    fun isMark(codePoint: Int): Boolean = marks[codePoint] == true

    companion object {
        /** Null when a data file is missing or empty: the callers then report an incomplete analysis. */
        val instance: UnicodeTables? by lazy { load() }

        private fun load(): UnicodeTables? {
            fun read(name: String) = HostResources.lines(name).orEmpty()
            val idnaLines = read("idna-mapping.txt")
            val bidiLines = read("bidi-class.txt")
            val joiningLines = read("joining-type.txt")
            val viramaLines = read("virama.txt")
            val markLines = read("marks.txt")
            if (listOf(idnaLines, bidiLines, joiningLines, viramaLines, markLines).any { it.isEmpty() }) return null
            return UnicodeTables(
                idna = RangeTable.parse(idnaLines, ::parseIdna),
                bidi = RangeTable.parse(bidiLines, ::parseBidi),
                joining = RangeTable.parse(joiningLines) { v -> JoiningType.entries.firstOrNull { it.name == v } },
                virama = RangeTable.parse(viramaLines) { true },
                marks = RangeTable.parse(markLines) { true },
            )
        }

        private fun parseBidi(value: String): BidiClass =
            BidiClass.entries.firstOrNull { it.name == value } ?: BidiClass.OTHER

        private fun parseIdna(value: String): IdnaEntry? {
            val parts = value.split(' ')
            val status = when (parts[0]) {
                "V" -> IdnaStatus.VALID
                "I" -> IdnaStatus.IGNORED
                "M" -> IdnaStatus.MAPPED
                "D" -> IdnaStatus.DEVIATION
                "3" -> IdnaStatus.STD3_VALID
                "4" -> IdnaStatus.STD3_MAPPED
                else -> return null
            }
            val mapping = parts.getOrNull(1)
                ?.split(',')
                ?.mapNotNull { it.toIntOrNull(HEX_RADIX) }
                ?.toIntArray()
            return IdnaEntry(status, mapping ?: IntArray(0))
        }
    }
}
