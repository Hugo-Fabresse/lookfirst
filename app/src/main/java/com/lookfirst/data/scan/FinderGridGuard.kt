package com.lookfirst.data.scan

import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.detector.FinderPatternFinder

/**
 * Refuses a binarized frame holding a grid of finder patterns (a checkerboard poster, a booby-trapped image)
 * before any ZXing reader runs: those readers are super-linear in the number of candidates and cannot be
 * cancelled (threat model T-IMG-2). Shared by the camera and the image-import paths.
 *
 * Two thresholds, from the measured cost of each reader (JDK 17 host, LF-35): the multi-code reader is cubic
 * (64 candidates 31 ms, 121 -> 143 ms, 196 -> 0.6 s, 289 -> 2.4 s), the plain reader much milder (289 -> 13 ms,
 * 484 -> 46 ms, 729 -> 82 ms). The counting itself has a work budget ([MAX_CHECKS]): each candidate check may
 * walk a whole pixel column, so on a tall striped frame the count is not linear in the pixels; over the budget
 * the frame is a grid, whatever the candidate count. The importer also bounds the longest side, which bounds a
 * column.
 */
internal object FinderGridGuard {
    /** The multi-code reader is allowed up to this many candidates (about 20 codes on a page). */
    const val MAX_MULTI_CANDIDATES = 64

    /** The plain reader is allowed up to this many candidates; above, nothing is read. */
    const val MAX_SIMPLE_CANDIDATES = 400

    /** Budget of candidate checks (`handlePossibleCenter` calls); over it the frame is refused. */
    const val MAX_CHECKS = 20_000

    enum class Load { MULTI_OK, SIMPLE_ONLY, REFUSED }

    /** Outcome of a count: [candidates] stops growing once refused; [checks] is the work done. */
    class Counting(val candidates: Int, val checks: Int, val overBudget: Boolean) {
        val load: Load
            get() = when {
                overBudget || candidates > MAX_SIMPLE_CANDIDATES -> Load.REFUSED
                candidates > MAX_MULTI_CANDIDATES -> Load.SIMPLE_ONLY
                else -> Load.MULTI_OK
            }
    }

    fun assess(matrix: BitMatrix): Load = count(matrix).load

    fun count(matrix: BitMatrix): Counting = Counter(matrix).run()

    /**
     * Counts the candidates with ZXing's own detector (same run-length scan as its `find`) and stops as soon as
     * the plain-reader limit or the check budget is exceeded. No combination of patterns is ever formed.
     */
    private class Counter(matrix: BitMatrix) : FinderPatternFinder(matrix) {
        private var checks = 0

        @Suppress("CyclomaticComplexMethod", "NestedBlockDepth")
        fun run(): Counting {
            val width = image.width
            val state = IntArray(PATTERN_RUNS)
            for (y in 0 until image.height) {
                doClearCounts(state)
                var current = 0
                for (x in 0 until width) {
                    if (image.get(x, y)) {
                        if ((current and 1) == 1) current++
                        state[current]++
                    } else if ((current and 1) == 1) {
                        state[current]++
                    } else if (current == PATTERN_RUNS - 1) {
                        if (foundPatternCross(state) && check(state, y, x)) {
                            current = 0
                            doClearCounts(state)
                        } else {
                            doShiftCounts2(state)
                            current = PATTERN_RUNS - 2
                        }
                    } else {
                        state[++current]++
                    }
                }
                if (foundPatternCross(state)) check(state, y, width)
                val candidates = possibleCenters.size
                if (checks > MAX_CHECKS) return Counting(candidates, checks, overBudget = true)
                if (candidates > MAX_SIMPLE_CANDIDATES) return Counting(candidates, checks, overBudget = false)
            }
            return Counting(possibleCenters.size, checks, overBudget = false)
        }

        private fun check(state: IntArray, y: Int, x: Int): Boolean {
            checks++
            return handlePossibleCenter(state, y, x)
        }

        private companion object {
            const val PATTERN_RUNS = 5
        }
    }
}
