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
import org.junit.Assert.assertFalse
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
        assertFalse(isGrid(frame))
        assertEquals(text, decoder.decode(frame))
    }

    @Test
    fun severalQrCodesOnOneFrameAreNotRefused() {
        val frame = tile(listOf("https://a.example/1", "https://b.example/2", "WIFI:T:WPA;S:x;P:y;;", "tel:+33123"))
        assertFalse(isGrid(frame))
        assertNotNull(decoder.decode(frame))
    }

    @Test
    fun aGridOfFinderPatternsIsRefusedWithoutCallingTheReader() {
        val frame = finderGrid(1280, 720, module = 2)
        val started = System.nanoTime()
        assertTrue(isGrid(frame))
        val result = decoder.decode(frame)
        val millis = (System.nanoTime() - started) / NANOS_PER_MILLI
        println("LF-35 grid 1280x720 guard+decode: $millis ms")
        assertNull(result)
        assertTrue("took $millis ms", millis < GENEROUS_MS)
    }

    @Test
    fun aHugeGridIsRefusedToo() {
        val frame = finderGrid(4000, 3000, module = 2)
        val started = System.nanoTime()
        assertNull(decoder.decode(frame))
        val millis = (System.nanoTime() - started) / NANOS_PER_MILLI
        println("LF-35 grid 4000x3000 decode: $millis ms")
        assertTrue("took $millis ms", millis < GENEROUS_MS)
    }

    @Test
    fun countingCostStaysLinearOnLargeFramesWithoutCandidates() {
        for ((w, h) in listOf(1280 to 720, 4000 to 3000)) {
            val stripes = LuminanceFrame(ByteArray(w * h) { i -> if ((i % w) / 3 % 2 == 0) 0 else 0xFF.toByte() }, w, h)
            val matrix = BinaryBitmap(HybridBinarizer(source(stripes))).blackMatrix
            val started = System.nanoTime()
            val grid = FinderGridGuard.isGrid(matrix)
            val millis = (System.nanoTime() - started) / NANOS_PER_MILLI
            println("LF-35 count ${w}x$h (no candidate, full scan): $millis ms")
            assertFalse(grid)
            assertTrue("took $millis ms", millis < GENEROUS_MS)
        }
    }

    private fun source(frame: LuminanceFrame) =
        PlanarYUVLuminanceSource(frame.luminance, frame.width, frame.height, 0, 0, frame.width, frame.height, false)

    private fun isGrid(frame: LuminanceFrame) =
        FinderGridGuard.isGrid(BinaryBitmap(HybridBinarizer(source(frame))).blackMatrix)

    private fun assertNotNull(value: Any?) = assertTrue(value != null)

    private fun qrFrame(text: String, modulePx: Int): LuminanceFrame {
        val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L, EncodeHintType.MARGIN to 4)
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
        val w = matrix.width * modulePx
        val h = matrix.height * modulePx
        val pixels = ByteArray(w * h) { i -> if (matrix.get(i % w / modulePx, i / w / modulePx)) 0 else 0xFF.toByte() }
        return LuminanceFrame(pixels, w, h)
    }

    private fun tile(texts: List<String>): LuminanceFrame {
        val frames = texts.map { qrFrame(it, modulePx = 5) }
        val cell = frames.maxOf { it.width }
        val w = cell * 2
        val pixels = ByteArray(w * w) { 0xFF.toByte() }
        frames.forEachIndexed { index, f ->
            val ox = (index % 2) * cell
            val oy = (index / 2) * cell
            for (y in 0 until f.height) System.arraycopy(f.luminance, y * f.width, pixels, (oy + y) * w + ox, f.width)
        }
        return LuminanceFrame(pixels, w, w)
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
    }
}
