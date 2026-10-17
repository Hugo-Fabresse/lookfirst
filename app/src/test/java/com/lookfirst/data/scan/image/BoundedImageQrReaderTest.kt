package com.lookfirst.data.scan.image

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
    private val reader = BoundedImageQrReader(context, Dispatchers.Unconfined)

    private fun read(bytes: ByteArray, scheme: String = "content"): ImageReadResult = read(bytes.inputStream(), scheme)

    private fun read(stream: InputStream, scheme: String = "content"): ImageReadResult {
        val uri = Uri.parse("$scheme://lookfirst.test/image/1")
        shadowOf(context.contentResolver).registerInputStream(uri, stream)
        return runBlocking { reader.read(uri) }
    }

    private fun readFromProvider(name: String, file: File): ImageReadResult {
        Robolectric.setupContentProvider(FileProvider::class.java, "lookfirst.fd")
        FileProvider.files[name] = file
        return runBlocking { reader.read(Uri.parse("content://lookfirst.fd/$name")) }
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
        // The longest side is bounded too: 100 x 20 000 is only 2 Mpx but a very tall column.
        assertEquals(16, BoundedImageQrReader.sampleSizeFor(100, 20_000))
    }

    @Test
    fun sampleSizeRoundsUpSoAnExtremeRatioStaysBounded() {
        // 1 x 100 000 000: with rounding down the width would reach 0 and the bound would never hold.
        val sample = BoundedImageQrReader.sampleSizeFor(1, 100_000_000)
        assertEquals(65_536, sample)
        assertTrue(1L * ((100_000_000 + sample - 1) / sample) <= BoundedImageQrReader.MAX_DECODE_SIDE)
    }

    @Test
    fun anImageDeclaringTooManyPixelsIsRefusedBeforeDecoding() {
        val result = read(withDeclaredSize(png(qrBitmap("https://example.com/x", size = 100)), 20_000, 20_000))
        assertEquals(ImageFailure.TOO_LARGE, failure(result))
    }

    @Test
    fun aGridOfFinderPatternsIsRefusedFastInsteadOfFreezingTheMultiReader() {
        val grid = finderPatternGrid(size = 1200)
        val started = System.nanoTime()
        val result = read(png(grid))
        val millis = (System.nanoTime() - started) / 1_000_000
        println("finder pattern grid refused in $millis ms")
        assertEquals(ImageFailure.UNREADABLE, failure(result))
        assertTrue("took $millis ms", millis < 2_000)
    }

    @Test
    fun twelveQrCodesAreNotRefusedAndTheFirstIsReturned() {
        val result = read(png(qrPage(codes = 12, columns = 4)))
        assertEquals(ImageReadResult.Found("https://example.com/code/0", multiple = true), result)
    }

    @Test
    fun aVeryTallStripedImageIsRefusedOrReducedFast() {
        val started = System.nanoTime()
        val result = read(png(tallStripes(width = 100, height = 40_000)))
        val millis = (System.nanoTime() - started) / 1_000_000
        println("LF-35 tall striped image 100x40000 read in $millis ms")
        assertTrue(result is ImageReadResult.Failed)
        assertTrue("took $millis ms", millis < 5_000)
    }

    @Test
    fun aSecondReadIsRefusedWhileTheFirstOneStillRuns() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocking = object : InputStream() {
            override fun read(): Int = read(ByteArray(1), 0, 1)

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                started.countDown()
                release.await(10, TimeUnit.SECONDS)
                return -1
            }
        }
        val firstUri = Uri.parse("content://lookfirst.test/image/slow")
        shadowOf(context.contentResolver).registerInputStream(firstUri, blocking)
        val first = async(Dispatchers.Default) { reader.read(firstUri) }
        assertTrue(started.await(10, TimeUnit.SECONDS))
        assertEquals(ImageFailure.BUSY, failure(read(png(qrBitmap("https://example.com/b")))))
        release.countDown()
        first.await()
        val again = read(png(qrBitmap("https://example.com/b")))
        assertEquals(ImageReadResult.Found("https://example.com/b", false), again)
    }

    @Test
    fun aFileDescriptorOverTheBoundIsRefusedFromItsSizeAlone() {
        val sparse = File.createTempFile("lf10", ".bin").apply { deleteOnExit() }
        RandomAccessFile(sparse, "rw").use { it.setLength(BoundedImageQrReader.MAX_FILE_BYTES + 1) }
        assertEquals(ImageFailure.TOO_LARGE, failure(readFromProvider("big", sparse)))
    }

    @Test
    fun aFileDescriptorIsReadLikeAStream() {
        val file = File.createTempFile("lf10", ".png").apply { deleteOnExit() }
        file.writeBytes(png(qrBitmap("https://example.com/fd")))
        assertEquals(ImageReadResult.Found("https://example.com/fd", false), readFromProvider("ok", file))
    }

    @Test
    fun aFileDescriptorOfAnotherFormatIsRefusedByItsMagicBytes() {
        val file = File.createTempFile("lf10", ".gif").apply { deleteOnExit() }
        file.writeBytes("GIF89a".toByteArray() + ByteArray(40))
        assertEquals(ImageFailure.CORRUPT, failure(readFromProvider("gif", file)))
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

    private fun qrPage(codes: Int, columns: Int): Bitmap {
        val size = 400
        val rows = (codes + columns - 1) / columns
        val bitmap = Bitmap.createBitmap(size * columns, size * rows, Bitmap.Config.ARGB_8888).apply {
            eraseColor(0xFFFFFFFF.toInt())
        }
        for (index in 0 until codes) {
            val code = matrix("https://example.com/code/$index", size)
            val row = IntArray(size)
            for (y in 0 until size) {
                for (x in 0 until size) row[x] = if (code.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
                bitmap.setPixels(row, 0, size, (index % columns) * size, (index / columns) * size + y, size, 1)
            }
        }
        return bitmap
    }

    /** Vertical stripes 1:1:3:1:1 (2 px modules): every row looks like a finder pattern, no column does. */
    private fun tallStripes(width: Int, height: Int): Bitmap {
        val runs = intArrayOf(1, 1, 3, 1, 1, 4)
        val row = IntArray(width) { 0xFFFFFFFF.toInt() }
        var x = 0
        var index = 0
        while (x < width) {
            val run = runs[index % runs.size] * 2
            if (index % 2 == 0) for (i in 0 until run) if (x + i < width) row[x + i] = 0xFF000000.toInt()
            x += run
            index++
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) bitmap.setPixels(row, 0, width, 0, y, width, 1)
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

    /** A page full of finder patterns (7 x 7 modules, 1:1:3:1:1): the shape that freezes the multi-code reader. */
    private fun finderPatternGrid(size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFFFFFFFF.toInt()) }
        val module = 3
        val pitch = 9 * module
        for (y in module until size - 7 * module step pitch) {
            for (x in module until size - 7 * module step pitch) drawFinderPattern(bitmap, x, y, module)
        }
        return bitmap
    }

    private fun drawFinderPattern(bitmap: Bitmap, left: Int, top: Int, module: Int) {
        for (dy in 0 until 7 * module) {
            for (dx in 0 until 7 * module) {
                val mx = dx / module
                val my = dy / module
                val ring = mx == 0 || mx == 6 || my == 0 || my == 6
                if (ring || (mx in 2..4 && my in 2..4)) bitmap.setPixel(left + dx, top + dy, 0xFF000000.toInt())
            }
        }
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

    /** Serves files by name through a real file descriptor (`openFileDescriptor`), like a document provider. */
    class FileProvider : ContentProvider() {
        override fun onCreate() = true

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = ParcelFileDescriptor.open(
            files.getValue(uri.lastPathSegment.orEmpty()),
            ParcelFileDescriptor.MODE_READ_ONLY,
        )

        override fun query(uri: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null

        override fun getType(uri: Uri): String? = null

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0

        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?) = 0

        companion object {
            val files = mutableMapOf<String, File>()
        }
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
