package com.lookfirst.domain.scan

/**
 * One camera or image frame reduced to its 8-bit luminance plane, row-major, no padding
 * (`luminance.size == width * height`). [rotationDegrees] is the clockwise rotation to apply to
 * display the frame upright; a QR code is detectable at any rotation, so decoders may ignore it.
 */
class LuminanceFrame(val luminance: ByteArray, val width: Int, val height: Int, val rotationDegrees: Int = 0) {
    init {
        require(width > 0 && height > 0) { "empty frame" }
        require(luminance.size.toLong() == width.toLong() * height) { "luminance size does not match the frame" }
    }

    override fun toString(): String = "LuminanceFrame(${width}x$height, rotation=$rotationDegrees)"
}

/**
 * Reads a QR code from a frame. Pure and side-effect free: it never opens, stores or logs anything.
 * The result is untrusted text: it must not be interpreted by the caller before the parser has run.
 */
fun interface BarcodeDecoder {
    /** The raw text of the QR code found in [frame], or null when there is none. */
    fun decode(frame: LuminanceFrame): String?
}
