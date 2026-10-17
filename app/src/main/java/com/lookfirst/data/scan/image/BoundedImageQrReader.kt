package com.lookfirst.data.scan.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
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
import com.lookfirst.data.scan.FinderGridGuard
import com.lookfirst.di.ImageReadDispatcher
import com.lookfirst.domain.scan.LuminanceFrame
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [ImageQrReader] with hard bounds against a booby-trapped image (threat model T-IMG-1, T-IMG-2, T-IMG-4):
 * - only `content://` URIs (what the Photo Picker and the document picker return);
 * - at most [MAX_FILE_BYTES] are read, whatever the provider claims about the size; cancellable while reading;
 * - the magic bytes are checked before the platform decoder sees the file: jpeg, png, webp, heif only;
 * - the header is read first: at most [MAX_SOURCE_PIXELS] declared, then the bitmap is decoded sub-sampled to
 *   about [MAX_DECODE_PIXELS] and [MAX_DECODE_SIDE] on the longest side (a very tall image makes the vertical
 *   cross-check of ZXing quadratic); platform decoder only, one frame; the bitmap is recycled right after use;
 * - ZXing's readers (multi-code and TRY_HARDER) are super-linear in the number of finder patterns and cannot be
 *   cancelled: [FinderGridGuard] counts the candidates first and a grid is refused as unreadable, ZXing untouched;
 * - one read at a time on a dedicated single thread: while a read is still running (even if cancelled), a new
 *   one is refused with [ImageFailure.BUSY];
 * - nothing is written to disk.
 */
@Singleton
class BoundedImageQrReader @Inject constructor(
    @ApplicationContext private val context: Context,
    @ImageReadDispatcher private val dispatcher: CoroutineDispatcher,
) : ImageQrReader {
    private val busy = AtomicBoolean(false)

    override suspend fun read(uri: Uri): ImageReadResult {
        if (!busy.compareAndSet(false, true)) return failed(ImageFailure.BUSY)
        return try {
            withContext(dispatcher) {
                try {
                    readBounded(uri)
                } finally {
                    busy.set(false)
                }
            }
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
        val bytes = readCapped(uri)
        if (!ImageMagicBytes.isAccepted(bytes)) return failed(ImageFailure.CORRUPT)
        currentCoroutineContext().ensureActive()
        val frame = decodeLuminance(bytes, sampleSizeOf(bytes))
        currentCoroutineContext().ensureActive()
        return findCode(frame)
    }

    /** Reads the header only: refuses a picture declaring too many pixels. */
    private fun sampleSizeOf(bytes: ByteArray): Int {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) throw ImageRefusedException(ImageFailure.CORRUPT)
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

    /**
     * The bytes of [uri], through a file descriptor when the provider gives one (its size is only used to refuse
     * early and to size the buffer, never as the bound), else through a stream. The descriptor is closed on
     * cancellation. Throws [ImageRefusedException] over [MAX_FILE_BYTES].
     */
    private suspend fun readCapped(uri: Uri): ByteArray {
        val descriptor = try {
            context.contentResolver.openFileDescriptor(uri, "r")
        } catch (ignored: FileNotFoundException) {
            null
        }
        if (descriptor == null) {
            val stream = context.contentResolver.openInputStream(uri) ?: throw IOException("no stream")
            return readCapped(stream, expectedSize = -1L)
        }
        descriptor.use { pfd ->
            val size = pfd.statSize
            if (size > MAX_FILE_BYTES) throw ImageRefusedException(ImageFailure.TOO_LARGE)
            return readCapped(ParcelFileDescriptor.AutoCloseInputStream(pfd), size)
        }
    }

    private suspend fun readCapped(input: InputStream, expectedSize: Long): ByteArray {
        input.use { stream ->
            val hint = if (expectedSize in 1..MAX_FILE_BYTES) expectedSize.toInt() else BUFFER_BYTES
            val out = ByteArrayOutputStream(hint)
            val buffer = ByteArray(BUFFER_BYTES)
            var total = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_FILE_BYTES) throw ImageRefusedException(ImageFailure.TOO_LARGE)
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
    }

    private fun findCode(frame: LuminanceFrame): ImageReadResult {
        val bitmap = frame.toBinaryBitmap()
        // Candidates are counted before any ZXing call (LF-35): a grid is refused, a crowded page skips the
        // multi-code reader (cubic, not cancellable) and gets the plain reader only.
        val load = FinderGridGuard.assess(bitmap.blackMatrix)
        if (load == FinderGridGuard.Load.REFUSED) return failed(ImageFailure.UNREADABLE)
        if (load == FinderGridGuard.Load.MULTI_OK) {
            val texts = decodeAll(bitmap)
            if (texts.isNotEmpty()) return ImageReadResult.Found(texts.first(), multiple = texts.size > 1)
        }
        return readOne(bitmap)
    }

    /** The plain reader on the already binarized (and already guarded) bitmap; also tells absent from unreadable. */
    private fun readOne(bitmap: BinaryBitmap): ImageReadResult = try {
        val text = QRCodeReader().decode(bitmap, HINTS).text
        if (text.isNullOrEmpty()) failed(ImageFailure.UNREADABLE) else ImageReadResult.Found(text, multiple = false)
    } catch (ignored: NotFoundException) {
        failed(ImageFailure.NO_QR)
    } catch (ignored: FormatException) {
        failed(ImageFailure.UNREADABLE)
    } catch (ignored: ChecksumException) {
        failed(ImageFailure.UNREADABLE)
    }

    /** The distinct texts of every code found, first in reading order. */
    private fun decodeAll(bitmap: BinaryBitmap): List<String> {
        val codes = try {
            QRCodeMultiReader().decodeMultiple(bitmap, HINTS).toList().inReadingOrder()
        } catch (ignored: ReaderException) {
            emptyList()
        }
        return codes.mapNotNull { it.text?.takeIf(String::isNotEmpty) }.distinct()
    }

    private fun failed(reason: ImageFailure) = ImageReadResult.Failed(reason)

    /** Internal early exit: the image is refused for [reason]. */
    private class ImageRefusedException(val reason: ImageFailure) : Exception()

    internal companion object {
        const val MAX_FILE_BYTES = 10L * 1024 * 1024
        const val MAX_SOURCE_PIXELS = 25_000_000L
        const val MAX_DECODE_PIXELS = 4_000_000L
        const val MAX_DECODE_SIDE = 2_048
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
        private val HINTS: Map<DecodeHintType, Any> = mapOf(DecodeHintType.TRY_HARDER to true)

        /** Power of two for `inSampleSize`: the decoded bitmap (sizes rounded up) keeps both bounds, pixels and side. */
        fun sampleSizeFor(width: Int, height: Int): Int {
            var sample = 1
            while (ceilDiv(width, sample).toLong() * ceilDiv(height, sample) > MAX_DECODE_PIXELS ||
                maxOf(ceilDiv(width, sample), ceilDiv(height, sample)) > MAX_DECODE_SIDE
            ) {
                sample *= 2
            }
            return sample
        }

        private fun ceilDiv(value: Int, divisor: Int): Int = (value + divisor - 1) / divisor

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
