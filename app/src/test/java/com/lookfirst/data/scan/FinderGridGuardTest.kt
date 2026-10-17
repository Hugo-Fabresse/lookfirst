package com.lookfirst.data.scan

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.EncodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.lookfirst.data.scan.camera.ZxingBarcodeDecoder
import com.lookfirst.domain.scan.LuminanceFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FinderGridGuardTest {
    private val decoder = ZxingBarcodeDecoder()

    @Test
    fun aVersion40QrCodeIsNotRefusedAndStillRead() {
        val text = "https://example.com/" + "a1b2c3d4e5".repeat(MAX_TEXT / 10)
        val frame = qrFrame(text, modulePx = 4)
        assertEquals("version 40 expected (177 modules + quiet zone)", 185 * 4, frame.width)
        assertEquals(FinderGridGuard.Load.MULTI_OK, load(frame))
        assertEquals(text, decoder.decode(frame))
    }

    @Test
    fun twelveQrCodesOnOneFrameAreNotRefusedAndStillRead() {
        val frame = page(codes = 12, columns = 4, modulePx = 6)
        val counting = counting(frame)
        println("LF-35 12 QR: ${counting.candidates} candidates, ${counting.checks} checks")
        assertEquals(FinderGridGuard.Load.MULTI_OK, counting.load)
        assertNotNull(decoder.decode(frame))
    }

    @Test
    fun aCrowdedPageGoesToThePlainReaderOnlyAndIsStillRead() {
        val frame = page(codes = 40, columns = 8, modulePx = 3)
        val counting = counting(frame)
        println("LF-35 40 QR: ${counting.candidates} candidates, ${counting.checks} checks")
        assertEquals(FinderGridGuard.Load.SIMPLE_ONLY, counting.load)
        assertNotNull(decoder.decode(frame))
    }

    @Test
    fun aGridOfFinderPatternsNeverReachesTheReader() {
        val frame = finderGrid(1280, 720, module = 2)
        var reads = 0
        val started = System.nanoTime()
        val result = decoder.decode(frame) { reads++.let { null } }
        val millis = (System.nanoTime() - started) / NANOS_PER_MILLI
        println("LF-35 grid 1280x720 guard+decode: $millis ms")
        assertNull(result)
        assertEquals(0, reads)
        assertEquals(FinderGridGuard.Load.REFUSED, load(frame))
        assertTrue("took $millis ms", millis < GENEROUS_MS)
    }

    @Test
    fun aHugeGridIsRefusedToo() {
        val frame = finderGrid(4000, 3000, module = 2)
        var reads = 0
        val started = System.nanoTime()
        assertNull(decoder.decode(frame) { reads++.let { null } })
        val millis = (System.nanoTime() - started) / NANOS_PER_MILLI
        println("LF-35 grid 4000x3000 decode: $millis ms")
        assertEquals(0, reads)
        assertTrue("took $millis ms", millis < GENEROUS_MS)
    }

    @Test
    fun countingCostIsBoundedOnLargeFrames() {
        val cases = listOf(
            "plain stripes 1280x720" to stripes(1280, 720, pattern = intArrayOf(3, 3)),
            "plain stripes 4000x3000" to stripes(4000, 3000, pattern = intArrayOf(3, 3)),
            "1:1:3:1:1 stripes 1280x720" to stripes(1280, 720, pattern = FINDER_RUNS),
            "1:1:3:1:1 stripes 4000x3000" to stripes(4000, 3000, pattern = FINDER_RUNS),
            "1:1:3:1:1 stripes 100x2048" to stripes(100, 2048, pattern = FINDER_RUNS),
            "1:1:3:1:1 stripes 100x40000" to stripes(100, 40_000, pattern = FINDER_RUNS),
        )
        for ((name, frame) in cases) {
            val matrix = BinaryBitmap(HybridBinarizer(source(frame))).blackMatrix
            val started = System.nanoTime()
            val counting = FinderGridGuard.count(matrix)
            val millis = (System.nanoTime() - started) / NANOS_PER_MILLI
            println("LF-35 count $name: $millis ms, ${counting.checks} checks, over budget ${counting.overBudget}")
            assertTrue("$name took $millis ms", millis < GENEROUS_MS)
        }
    }

    private fun source(frame: LuminanceFrame) =
        PlanarYUVLuminanceSource(frame.luminance, frame.width, frame.height, 0, 0, frame.width, frame.height, false)

    private fun load(frame: LuminanceFrame) = counting(frame).load

    private fun counting(frame: LuminanceFrame) =
        FinderGridGuard.count(BinaryBitmap(HybridBinarizer(source(frame))).blackMatrix)

    /** Vertical stripes whose run lengths (in 2 px) repeat [pattern]: every row looks like a finder pattern. */
    private fun stripes(w: Int, h: Int, pattern: IntArray): LuminanceFrame {
        val column = ByteArray(w)
        var x = 0
        var index = 0
        while (x < w) {
            val run = pattern[index % pattern.size] * 2
            for (i in 0 until run) if (x + i < w) column[x + i] = if (index % 2 == 0) 0 else 0xFF.toByte()
            x += run
            index++
        }
        val pixels = ByteArray(w * h)
        for (y in 0 until h) System.arraycopy(column, 0, pixels, y * w, w)
        return LuminanceFrame(pixels, w, h)
    }

    private fun assertNotNull(value: Any?) = assertTrue(value != null)

    private fun qrFrame(text: String, modulePx: Int): LuminanceFrame {
        val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L, EncodeHintType.MARGIN to 4)
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
        val w = matrix.width * modulePx
        val h = matrix.height * modulePx
        val pixels = ByteArray(w * h) { i -> if (matrix.get(i % w / modulePx, i / w / modulePx)) 0 else 0xFF.toByte() }
        return LuminanceFrame(pixels, w, h)
    }

    private fun page(codes: Int, columns: Int, modulePx: Int): LuminanceFrame {
        val frames = List(codes) { qrFrame("https://example.com/code/$it", modulePx) }
        val cell = frames.maxOf { it.width }
        val rows = (codes + columns - 1) / columns
        val w = cell * columns
        val h = cell * rows
        val pixels = ByteArray(w * h) { 0xFF.toByte() }
        frames.forEachIndexed { index, f ->
            val ox = (index % columns) * cell
            val oy = (index / columns) * cell
            for (y in 0 until f.height) System.arraycopy(f.luminance, y * f.width, pixels, (oy + y) * w + ox, f.width)
        }
        return LuminanceFrame(pixels, w, h)
    }

    /** Finder patterns (7 x 7 modules, 1:1:3:1:1) packed on a page: the shape that freezes the readers. */
    private fun finderGrid(w: Int, h: Int, module: Int): LuminanceFrame {
        val pixels = ByteArray(w * h) { 0xFF.toByte() }
        val side = 7 * module
        val pitch = side + module * 2
        for (top in module until h - side step pitch) {
            for (left in module until w - side step pitch) drawFinderPattern(pixels, w, left, top, module)
        }
        return LuminanceFrame(pixels, w, h)
    }

    private fun drawFinderPattern(pixels: ByteArray, w: Int, left: Int, top: Int, module: Int) {
        for (dy in 0 until 7 * module) {
            for (dx in 0 until 7 * module) {
                val mx = dx / module
                val my = dy / module
                val ring = mx == 0 || mx == 6 || my == 0 || my == 6
                if (ring || (mx in 2..4 && my in 2..4)) pixels[(top + dy) * w + left + dx] = 0
            }
        }
    }

    private companion object {
        const val MAX_TEXT = 2900
        const val NANOS_PER_MILLI = 1_000_000L
        const val GENEROUS_MS = 10_000L
        val FINDER_RUNS = intArrayOf(1, 1, 3, 1, 1, 4)
    }
}
