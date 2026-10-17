package com.lookfirst.domain.host

private const val ZWNJ = 0x200C
private const val ZWJ = 0x200D

/** Code points of [text] (surrogate pairs joined). */
internal fun codePointsOf(text: String): IntArray {
    val out = IntArray(text.codePointCount(0, text.length))
    var i = 0
    var n = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        out[n++] = cp
        i += Character.charCount(cp)
    }
    return out
}

/** The context rules of UTS #46 section 4.1: CheckJoiners (RFC 5892 A.1, A.2) and CheckBidi (RFC 5893). */
internal object ContextRules {
    /** RFC 5892 appendix A.1 and A.2: ZWNJ / ZWJ only after a virama, or (ZWNJ) between joining letters. */
    fun joinersValid(cps: IntArray, tables: UnicodeTables): Boolean {
        for ((i, cp) in cps.withIndex()) {
            val afterVirama = i > 0 && tables.isVirama(cps[i - 1])
            val ok = when (cp) {
                ZWJ -> afterVirama
                ZWNJ -> afterVirama || joinsBothSides(cps, i, tables)
                else -> true
            }
            if (!ok) return false
        }
        return true
    }

    private fun joinsBothSides(cps: IntArray, at: Int, tables: UnicodeTables): Boolean {
        var left = at - 1
        while (left >= 0 && tables.joiningType(cps[left]) == JoiningType.T) left--
        var right = at + 1
        while (right < cps.size && tables.joiningType(cps[right]) == JoiningType.T) right++
        val leftType = if (left >= 0) tables.joiningType(cps[left]) else null
        val rightType = if (right < cps.size) tables.joiningType(cps[right]) else null
        return (leftType == JoiningType.L || leftType == JoiningType.D) &&
            (rightType == JoiningType.R || rightType == JoiningType.D)
    }

    private val RTL_ALLOWED = setOf(
        BidiClass.R, BidiClass.AL, BidiClass.AN, BidiClass.EN, BidiClass.ES,
        BidiClass.CS, BidiClass.ET, BidiClass.ON, BidiClass.BN, BidiClass.NSM,
    )
    private val LTR_ALLOWED = setOf(
        BidiClass.L,
        BidiClass.EN,
        BidiClass.ES,
        BidiClass.CS,
        BidiClass.ET,
        BidiClass.ON,
        BidiClass.BN,
        BidiClass.NSM,
    )
    private val RTL_END = setOf(BidiClass.R, BidiClass.AL, BidiClass.EN, BidiClass.AN)
    private val LTR_END = setOf(BidiClass.L, BidiClass.EN)

    /** RFC 5893 section 2, applied to every label when at least one label is right-to-left. */
    fun bidiValid(labels: List<String>, tables: UnicodeTables): Boolean {
        val classes = labels.filter {
            it.isNotEmpty()
        }.map { label -> codePointsOf(label).map { tables.bidiClass(it) } }
        val isBidiDomain = classes.any { list ->
            list.any { it == BidiClass.R || it == BidiClass.AL || it == BidiClass.AN }
        }
        return !isBidiDomain || classes.all { bidiLabelValid(it) }
    }

    private fun bidiLabelValid(classes: List<BidiClass>): Boolean {
        val last = classes.lastOrNull { it != BidiClass.NSM }
        return when (classes.firstOrNull()) {
            BidiClass.R, BidiClass.AL ->
                classes.all { it in RTL_ALLOWED } &&
                    last in RTL_END &&
                    !(BidiClass.EN in classes && BidiClass.AN in classes)
            BidiClass.L -> classes.all { it in LTR_ALLOWED } && last in LTR_END
            else -> false
        }
    }
}
