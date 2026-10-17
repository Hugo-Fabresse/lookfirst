package com.lookfirst.data.scan.image

import android.graphics.Bitmap
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.lookfirst.data.scan.camera.ZxingBarcodeDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import kotlin.random.Random

/**
 * Images are generated here (ZXing writer + Bitmap), nothing is stored in the repository. Native graphics: the
 * real Skia codecs encode and decode the images (the legacy shadows return placeholder bitmaps).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BoundedImageQrReaderTest {
    private val context = RuntimeEnvironment.getApplication()
    private val reader = BoundedImageQrReader(context, ZxingBarcodeDecoder(), Dispatchers.Unconfined)

    private fun read(bytes: ByteArray, scheme: String = "content"): ImageReadResult = read(bytes.inputStream(), scheme)

    private fun read(stream: InputStream, scheme: String = "content"): ImageReadResult {
        val uri = Uri.parse("$scheme://lookfirst.test/image/1")
        shadowOf(context.contentResolver).registerInputStream(uri, stream)
        return runBlocking { reader.read(uri) }
    }

    private fun failure(result: ImageReadResult): ImageFailure = (result as ImageReadResult.Failed).reason

    @Test
    fun readsAQrCodeFromAPng() {
        val result = read(png(qrBitmap("https://example.com/a?b=c")))
        assertEquals(ImageReadResult.Found("https://example.com/a?b=c", multiple = false), result)
    }

    @Test
    fun readsAQrCodeFromAJpegAndAWebp() {
        val bitmap = qrBitmap("WIFI:T:WPA;S:cafe;P:secret;;")
        val expected = ImageReadResult.Found("WIFI:T:WPA;S:cafe;P:secret;;", multiple = false)
        assertEquals(expected, read(encode(bitmap, Bitmap.CompressFormat.JPEG)))
        assertEquals(expected, read(encode(bitmap, Bitmap.CompressFormat.WEBP_LOSSLESS)))
    }

    @Test
    fun anImageWithoutQrCodeIsNoQr() {
        val blank = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFFFFFFFF.toInt()) }
        assertEquals(ImageFailure.NO_QR, failure(read(png(blank))))
    }

    @Test
    fun aCodeWithGarbledDataIsUnreadableNotAbsent() {
        val result = read(png(garbledQr("https://example.com/garbled-code-to-ruin")))
        assertEquals(ImageFailure.UNREADABLE, failure(result))
    }

    @Test
    fun severalCodesGiveTheFirstInReadingOrderAndSayMultiple() {
        val result = read(png(sideBySide("https://first.example/", "https://second.example/")))
        assertEquals(ImageReadResult.Found("https://first.example/", multiple = true), result)
    }

    @Test
    fun aHugeImageIsSubSampledAndStillRead() {
        // 3200 x 3200 = 10.2 Mpx: decoded at half size (2.5 Mpx), the code stays readable.
        val result = read(png(qrBitmap("https://example.com/huge", size = 3200)))
        assertEquals(ImageReadResult.Found("https://example.com/huge", multiple = false), result)
    }

    @Test
    fun sampleSizeKeepsTheDecodedBitmapUnderTheBound() {
        assertEquals(1, BoundedImageQrReader.sampleSizeFor(1000, 1000))
        assertEquals(1, BoundedImageQrReader.sampleSizeFor(2000, 2000))
        assertEquals(2, BoundedImageQrReader.sampleSizeFor(3200, 3200))
        assertEquals(4, BoundedImageQrReader.sampleSizeFor(8000, 6000))
        assertEquals(8, BoundedImageQrReader.sampleSizeFor(10_000, 10_000))
    }

    @Test
    fun anImageDeclaringTooManyPixelsIsRefusedBeforeDecoding() {
        val result = read(withDeclaredSize(png(qrBitmap("https://example.com/x", size = 100)), 20_000, 20_000))
        assertEquals(ImageFailure.TOO_LARGE, failure(result))
    }

    @Test
    fun aFileOverTheSizeBoundIsTooLarge() {
        val endless = object : InputStream() {
            var served = 0L

            override fun read(): Int = if (served++ < OVER_LIMIT) 0 else -1

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (served >= OVER_LIMIT) return -1
                val n = minOf(len.toLong(), OVER_LIMIT - served).toInt()
                served += n
                return n
            }
        }
        assertEquals(ImageFailure.TOO_LARGE, failure(read(endless)))
        // Reading stopped right after the bound: the whole stream was not consumed.
        assertEquals(true, endless.served <= BoundedImageQrReader.MAX_FILE_BYTES + 16 * 1024)
    }

    @Test
    fun aCorruptFileIsCorrupt() {
        assertEquals(ImageFailure.CORRUPT, failure(read(Random(1).nextBytes(5000))))
        assertEquals(ImageFailure.CORRUPT, failure(read(ByteArray(0))))
        val truncated = png(qrBitmap("https://example.com/t")).let { it.copyOf(40) }
        assertEquals(ImageFailure.CORRUPT, failure(read(truncated)))
    }

    @Test
    fun aFormatOutsideTheAcceptedListIsRefused() {
        val gif = "GIF89a".toByteArray() + ByteArray(40)
        assertEquals(ImageFailure.CORRUPT, failure(read(gif)))
        assertEquals(ImageFailure.CORRUPT, failure(read("<svg xmlns='http://www.w3.org/2000/svg'/>".toByteArray())))
    }

    @Test
    fun onlyContentUrisAreRead() {
        val bytes = png(qrBitmap("https://example.com/a"))
        assertEquals(ImageFailure.CORRUPT, failure(read(bytes, scheme = "file")))
    }

    @Test
    fun aMissingStreamIsCorruptNotACrash() {
        val uri = Uri.parse("content://lookfirst.test/missing")
        val result = runBlocking { reader.read(uri) }
        assertEquals(ImageFailure.CORRUPT, failure(result))
    }

    // --- image generation -------------------------------------------------------------------------------

    private fun matrix(text: String, size: Int): BitMatrix {
        val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 4)
        return QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
    }

    private fun qrBitmap(text: String, size: Int = 400): Bitmap = draw(matrix(text, size), size, size)

    private fun draw(matrix: BitMatrix, width: Int, height: Int, offsetX: Int = 0, into: Bitmap? = null): Bitmap {
        val bitmap = into ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(0xFFFFFFFF.toInt())
        }
        val row = IntArray(matrix.width)
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) row[x] = if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            bitmap.setPixels(row, 0, matrix.width, offsetX, y, matrix.width, 1)
        }
        return bitmap
    }

    private fun sideBySide(left: String, right: String): Bitmap {
        val size = 300
        val bitmap = Bitmap.createBitmap(size * 2 + 40, size, Bitmap.Config.ARGB_8888).apply {
            eraseColor(0xFFFFFFFF.toInt())
        }
        draw(matrix(left, size), size, size, offsetX = 0, into = bitmap)
        return draw(matrix(right, size), size, size, offsetX = size + 40, into = bitmap)
    }

    /** Keeps the quiet zone and the three finder patterns and scrambles every data module: spotted, not decodable. */
    private fun garbledQr(text: String): Bitmap {
        val modules = matrix(text, 0)
        val n = modules.width
        val random = Random(5)
        val scale = 8
        val scrambled = BitMatrix(n * scale, n * scale)
        for (my in 0 until n) {
            for (mx in 0 until n) {
                val inside = mx in QUIET until n - QUIET && my in QUIET until n - QUIET
                val nearFinder = (mx < QUIET + FINDER && my < QUIET + FINDER) ||
                    (mx >= n - QUIET - FINDER && my < QUIET + FINDER) ||
                    (mx < QUIET + FINDER && my >= n - QUIET - FINDER)
                val on = inside && (if (nearFinder) modules.get(mx, my) else random.nextBoolean())
                if (on) scrambled.setRegion(mx * scale, my * scale, scale, scale)
            }
        }
        return draw(scrambled, n * scale, n * scale)
    }

    private fun encode(bitmap: Bitmap, format: Bitmap.CompressFormat): ByteArray {
        val out = ByteArrayOutputStream()
        check(bitmap.compress(format, 95, out))
        return out.toByteArray()
    }

    private fun png(bitmap: Bitmap): ByteArray = encode(bitmap, Bitmap.CompressFormat.PNG)

    /** Rewrites the width and height of the PNG header (and its CRC): the file claims a huge picture. */
    private fun withDeclaredSize(png: ByteArray, width: Int, height: Int): ByteArray {
        val copy = png.copyOf()
        ByteBuffer.wrap(copy).putInt(IHDR_DATA, width).putInt(IHDR_DATA + 4, height)
        val crc = CRC32().apply { update(copy, IHDR_TYPE, IHDR_LENGTH + 4) }
        ByteBuffer.wrap(copy).putInt(IHDR_DATA + IHDR_LENGTH, crc.value.toInt())
        return copy
    }

    private companion object {
        const val QUIET = 4
        const val FINDER = 9
        const val IHDR_TYPE = 12
        const val IHDR_DATA = 16
        const val IHDR_LENGTH = 13
        const val OVER_LIMIT = 21L * 1024 * 1024
    }
}
