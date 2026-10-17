package com.lookfirst.data.scan.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.zxing.BinaryBitmap
import com.google.zxing.ChecksumException
import com.google.zxing.DecodeHintType
import com.google.zxing.FormatException
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.Result
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.qrcode.QRCodeMultiReader
import com.google.zxing.qrcode.QRCodeReader
import com.lookfirst.di.IoDispatcher
import com.lookfirst.domain.scan.BarcodeDecoder
import com.lookfirst.domain.scan.LuminanceFrame
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

/**
 * [ImageQrReader] with hard bounds against a booby-trapped image (threat model T-IMG-1, T-IMG-4):
 * - only `content://` URIs (what the Photo Picker and the document picker return);
 * - at most [MAX_FILE_BYTES] are read, whatever the provider claims about the size;
 * - the header is read first: accepted formats only, at most [MAX_SOURCE_PIXELS] declared, then the bitmap is
 *   decoded sub-sampled to about [MAX_DECODE_PIXELS];
 * - platform decoder only (patched by OS updates), no animated format, one frame;
 * - the bitmap is recycled as soon as its luminance is copied; nothing is written to disk.
 * A QR code is searched for with the multi-code reader (all codes), then with the shared [BarcodeDecoder].
 */
class BoundedImageQrReader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val decoder: BarcodeDecoder,
    @IoDispatcher private val dispatcher: CoroutineDispatcher,
) : ImageQrReader {
    override suspend fun read(uri: Uri): ImageReadResult = withContext(dispatcher) {
        try {
            readBounded(uri)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (refused: ImageRefusedException) {
            failed(refused.reason)
        } catch (ignored: OutOfMemoryError) {
            failed(ImageFailure.TOO_LARGE)
        } catch (ignored: IOException) {
            failed(ImageFailure.CORRUPT)
        } catch (ignored: RuntimeException) {
            // SecurityException, a provider or decoder fault: a hostile image must never crash the process.
            failed(ImageFailure.CORRUPT)
        }
    }

    private suspend fun readBounded(uri: Uri): ImageReadResult {
        if (uri.scheme != "content") return failed(ImageFailure.CORRUPT)
        val bytes = readCapped(uri) ?: throw ImageRefusedException(ImageFailure.TOO_LARGE)
        coroutineContext.ensureActive()
        val frame = decodeLuminance(bytes, sampleSizeOf(bytes))
        coroutineContext.ensureActive()
        return findCode(frame)
    }

    /** Reads the header only: refuses an unaccepted format or a picture declaring too many pixels. */
    private fun sampleSizeOf(bytes: ByteArray): Int {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0 || bounds.outMimeType !in ACCEPTED_MIME_TYPES) {
            throw ImageRefusedException(ImageFailure.CORRUPT)
        }
        if (width.toLong() * height > MAX_SOURCE_PIXELS) throw ImageRefusedException(ImageFailure.TOO_LARGE)
        return sampleSizeFor(width, height)
    }

    private fun decodeLuminance(bytes: ByteArray, sampleSize: Int): LuminanceFrame {
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: throw ImageRefusedException(ImageFailure.CORRUPT)
        try {
            return bitmap.toLuminanceFrame()
        } finally {
            bitmap.recycle()
        }
    }

    /** The bytes of [uri], or null when there are more than [MAX_FILE_BYTES] (reading stops there). */
    private fun readCapped(uri: Uri): ByteArray? {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("no stream")
        input.use { stream ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(BUFFER_BYTES)
            var total = 0L
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_FILE_BYTES) return null
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
    }

    private fun findCode(frame: LuminanceFrame): ImageReadResult {
        val bitmap = frame.toBinaryBitmap()
        val codes = try {
            QRCodeMultiReader().decodeMultiple(bitmap, HINTS).toList().inReadingOrder()
        } catch (ignored: ReaderException) {
            emptyList()
        }
        val texts = codes.mapNotNull { it.text?.takeIf(String::isNotEmpty) }.distinct()
        if (texts.isNotEmpty()) return ImageReadResult.Found(texts.first(), multiple = texts.size > 1)

        decoder.decode(frame)?.let { return ImageReadResult.Found(it, multiple = false) }
        return failed(classifyMiss(bitmap))
    }

    /** Tells "nothing there" from "a code was spotted but cannot be decoded". */
    private fun classifyMiss(bitmap: BinaryBitmap): ImageFailure = try {
        QRCodeReader().decode(bitmap, HINTS)
        ImageFailure.UNREADABLE
    } catch (ignored: NotFoundException) {
        ImageFailure.NO_QR
    } catch (ignored: FormatException) {
        ImageFailure.UNREADABLE
    } catch (ignored: ChecksumException) {
        ImageFailure.UNREADABLE
    }

    private fun failed(reason: ImageFailure) = ImageReadResult.Failed(reason)

    /** Internal early exit of [readBounded]: the image is refused for [reason]. */
    private class ImageRefusedException(val reason: ImageFailure) : Exception()

    internal companion object {
        const val MAX_FILE_BYTES = 20L * 1024 * 1024
        const val MAX_SOURCE_PIXELS = 100_000_000L
        const val MAX_DECODE_PIXELS = 4_000_000L
        private const val BUFFER_BYTES = 16 * 1024
        private const val OPAQUE = 255
        private const val RED_WEIGHT = 299
        private const val GREEN_WEIGHT = 587
        private const val BLUE_WEIGHT = 114
        private const val WEIGHT_TOTAL = 1000
        private const val GREEN_SHIFT = 8
        private const val RED_SHIFT = 16
        private const val ALPHA_SHIFT = 24
        private const val CHANNEL_MASK = 0xFF
        private val ACCEPTED_MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp", "image/heif", "image/heic")
        private val HINTS: Map<DecodeHintType, Any> = mapOf(DecodeHintType.TRY_HARDER to true)

        /** Power of two for `inSampleSize` so that the decoded bitmap has at most [MAX_DECODE_PIXELS]. */
        fun sampleSizeFor(width: Int, height: Int): Int {
            var sample = 1
            while ((width / sample).toLong() * (height / sample) > MAX_DECODE_PIXELS) sample *= 2
            return sample
        }

        /** 8-bit luminance, a transparent pixel counting as white (a QR on a transparent PNG stays readable). */
        private fun Bitmap.toLuminanceFrame(): LuminanceFrame {
            val w = width
            val h = height
            val luminance = ByteArray(w * h)
            val row = IntArray(w)
            for (y in 0 until h) {
                getPixels(row, 0, w, 0, y, w, 1)
                for (x in 0 until w) {
                    val argb = row[x]
                    val alpha = (argb ushr ALPHA_SHIFT) and CHANNEL_MASK
                    val red = (argb shr RED_SHIFT) and CHANNEL_MASK
                    val green = (argb shr GREEN_SHIFT) and CHANNEL_MASK
                    val blue = argb and CHANNEL_MASK
                    val grey = (red * RED_WEIGHT + green * GREEN_WEIGHT + blue * BLUE_WEIGHT) / WEIGHT_TOTAL
                    luminance[y * w + x] = (OPAQUE - alpha * (OPAQUE - grey) / OPAQUE).toByte()
                }
            }
            return LuminanceFrame(luminance, w, h)
        }

        private fun LuminanceFrame.toBinaryBitmap() = BinaryBitmap(
            HybridBinarizer(PlanarYUVLuminanceSource(luminance, width, height, 0, 0, width, height, false)),
        )

        private fun List<Result>.inReadingOrder(): List<Result> = sortedWith(
            compareBy<Result>({ it.topEdge() }, { it.leftEdge() }),
        )

        private fun Result.topEdge(): Float = resultPoints.minOfOrNull { it.y } ?: 0f

        private fun Result.leftEdge(): Float = resultPoints.minOfOrNull { it.x } ?: 0f
    }
}
