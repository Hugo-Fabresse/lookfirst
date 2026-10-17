package com.lookfirst.data.scan

import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.detector.FinderPatternFinder

/**
 * Refuses a binarized frame holding a grid of finder patterns (a checkerboard poster, a booby-trapped image)
 * before any ZXing reader runs: those readers are super-linear in the number of candidates and cannot be
 * cancelled (threat model T-IMG-2). Shared by the camera and the image-import paths.
 */
internal object FinderGridGuard {
    /**
     * More candidates than any real photo holds: one QR has 3, a page with a few codes stays far under.
     * Measured on the JDK 17 host: 1 537 candidates already cost 0.8 s, 3 476 cost 2.4 s (LF-35).
     */
    const val MAX_FINDER_CANDIDATES = 30

    /** True when [matrix] holds more than [limit] finder-pattern candidates. Linear in the pixels. */
    fun isGrid(matrix: BitMatrix, limit: Int = MAX_FINDER_CANDIDATES): Boolean = Counter(matrix).exceeds(limit)

    /**
     * Counts the candidates with ZXing's own detector (same run-length scan as its `find`) and stops as soon as
     * there are more than a limit. No combination of patterns is ever formed.
     */
    private class Counter(matrix: BitMatrix) : FinderPatternFinder(matrix) {
        @Suppress("CyclomaticComplexMethod", "NestedBlockDepth")
        fun exceeds(limit: Int): Boolean {
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
                        if (foundPatternCross(state) && handlePossibleCenter(state, y, x)) {
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
                if (foundPatternCross(state)) handlePossibleCenter(state, y, width)
                if (possibleCenters.size > limit) return true
            }
            return false
        }

        private companion object {
            const val PATTERN_RUNS = 5
        }
    }
}
