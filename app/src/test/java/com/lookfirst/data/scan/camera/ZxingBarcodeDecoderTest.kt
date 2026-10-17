package com.lookfirst.data.scan.camera

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.lookfirst.domain.scan.LuminanceFrame
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.random.Random

class ZxingBarcodeDecoderTest {
    private val decoder = ZxingBarcodeDecoder()

    @Test
    fun readsAValidQrCode() {
        val frame = qrFrame("https://example.com/a?b=c")
        assertEquals("https://example.com/a?b=c", decoder.decode(frame))
    }

    @Test
    fun readsARotatedQrCode() {
        val frame = rotate90(qrFrame("WIFI:T:WPA;S:cafe;P:secret;;"))
        assertEquals("WIFI:T:WPA;S:cafe;P:secret;;", decoder.decode(frame))
    }

    @Test
    fun readsABlurredAndNoisyQrCode() {
        val noisy = addNoise(blur(qrFrame("https://example.com/blur")), amplitude = 30, seed = 7)
        assertEquals("https://example.com/blur", decoder.decode(noisy))
    }

    @Test
    fun returnsNullForAnImageWithoutQrCode() {
        assertNull(decoder.decode(LuminanceFrame(ByteArray(300 * 300) { 0xFF.toByte() }, 300, 300)))
        assertNull(decoder.decode(addNoise(LuminanceFrame(ByteArray(300 * 300) { 0x80.toByte() }, 300, 300), 120, 3)))
    }

    @Test
    fun copyLuminanceDropsRowPadding() {
        val width = 3
        val stride = 5
        val padded = byteArrayOf(1, 2, 3, 9, 9, 4, 5, 6, 9, 9)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), copyLuminance(ByteBuffer.wrap(padded), stride, width, 2))
    }

    private fun qrFrame(text: String, size: Int = 400): LuminanceFrame {
        val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 4)
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val pixels = ByteArray(size * size) { i ->
            if (matrix.get(i % size, i / size)) 0 else 0xFF.toByte()
        }
        return LuminanceFrame(pixels, size, size)
    }

    private fun rotate90(frame: LuminanceFrame): LuminanceFrame {
        val w = frame.width
        val h = frame.height
        val out = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) out[x * h + (h - 1 - y)] = frame.luminance[y * w + x]
        return LuminanceFrame(out, h, w, rotationDegrees = 90)
    }

    /** 5x5 box blur: about a third of a module for a 400 px code. */
    private fun blur(frame: LuminanceFrame): LuminanceFrame {
        val w = frame.width
        val h = frame.height
        val out = ByteArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0
                var n = 0
                for (dy in -2..2) {
                    for (dx in -2..2) {
                        val yy = (y + dy).coerceIn(0, h - 1)
                        val xx = (x + dx).coerceIn(0, w - 1)
                        sum += frame.luminance[yy * w + xx].toInt() and 0xFF
                        n++
                    }
                }
                out[y * w + x] = (sum / n).toByte()
            }
        }
        return LuminanceFrame(out, w, h)
    }

    private fun addNoise(frame: LuminanceFrame, amplitude: Int, seed: Int): LuminanceFrame {
        val random = Random(seed)
        val out = ByteArray(frame.luminance.size) { i ->
            (
                (frame.luminance[i].toInt() and 0xFF) + random.nextInt(
                    -amplitude,
                    amplitude + 1,
                )
                ).coerceIn(0, 255).toByte()
        }
        return LuminanceFrame(out, frame.width, frame.height)
    }
}
