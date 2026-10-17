package com.lookfirst.data.scan.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.lookfirst.domain.scan.BarcodeDecoder
import com.lookfirst.domain.scan.LuminanceFrame
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CameraX analyzer: decodes frames until the first code is read, then hands its raw text to [onCodeRead]
 * exactly once and ignores every later frame (the verdict is immutable, a second code never replaces the first).
 * Runs on the executor given to `ImageAnalysis.setAnalyzer`, never on the UI thread. Frames are never kept.
 */
class QrImageAnalyzer(private val decoder: BarcodeDecoder, private val onCodeRead: (String) -> Unit) :
    ImageAnalysis.Analyzer {
    private val finished = AtomicBoolean(false)

    override fun analyze(image: ImageProxy) {
        try {
            if (finished.get()) return
            val plane = image.planes[0]
            val frame = LuminanceFrame(
                luminance = copyLuminance(plane.buffer, plane.rowStride, image.width, image.height),
                width = image.width,
                height = image.height,
                rotationDegrees = image.imageInfo.rotationDegrees,
            )
            val text = decoder.decode(frame) ?: return
            if (finished.compareAndSet(false, true)) onCodeRead(text)
        } catch (ignored: RuntimeException) {
            // A hostile frame must never crash the process: no read, the next frame is tried.
        } finally {
            image.close()
        }
    }
}

/** Copies the Y plane into a tightly packed array, dropping the per-row padding ([rowStride] > [width]). */
internal fun copyLuminance(buffer: ByteBuffer, rowStride: Int, width: Int, height: Int): ByteArray {
    val out = ByteArray(width * height)
    val source = buffer.duplicate()
    for (row in 0 until height) {
        source.position(row * rowStride)
        source.get(out, row * width, width)
    }
    return out
}
