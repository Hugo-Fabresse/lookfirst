package com.lookfirst.data.scan.camera

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.lookfirst.domain.scan.BarcodeDecoder
import com.lookfirst.domain.scan.LuminanceFrame
import javax.inject.Inject

/**
 * [BarcodeDecoder] backed by ZXing core (D11): QR codes only, `TRY_HARDER` (slower but finds blurred or
 * small codes), no `PURE_BARCODE` (the frame is a photo, not a clean image of a code).
 * A reader is created per call: this class holds no state and is safe to call from any thread.
 */
class ZxingBarcodeDecoder @Inject constructor() : BarcodeDecoder {
    override fun decode(frame: LuminanceFrame): String? {
        if (frame.width.toLong() * frame.height > MAX_PIXELS) return null
        val source = PlanarYUVLuminanceSource(
            frame.luminance,
            frame.width,
            frame.height,
            0,
            0,
            frame.width,
            frame.height,
            false,
        )
        return try {
            QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), HINTS).text?.takeIf { it.isNotEmpty() }
        } catch (ignored: ReaderException) {
            null
        } catch (ignored: RuntimeException) {
            // A hostile image must never crash the process: no read.
            null
        }
    }

    private companion object {
        const val MAX_PIXELS = 12_000_000L
        val HINTS: Map<DecodeHintType, Any> = mapOf(DecodeHintType.TRY_HARDER to true)
    }
}
