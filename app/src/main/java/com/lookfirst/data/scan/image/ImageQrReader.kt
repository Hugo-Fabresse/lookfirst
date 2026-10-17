package com.lookfirst.data.scan.image

import android.net.Uri

/** Why no content could be taken from a picked image. Typed: the UI never shows an exception message. */
enum class ImageFailure {
    /** The image was read, nothing looks like a QR code. */
    NO_QR,

    /** A QR code was spotted but could not be decoded (damaged, blurred beyond repair). */
    UNREADABLE,

    /** The file or its pixel count is over the bounds, or memory ran out: nothing was decoded. */
    TOO_LARGE,

    /** Not an image of an accepted format, or a broken file. */
    CORRUPT,

    /** A previous read is still running on the single reading thread: try again in a moment. */
    BUSY,
}

sealed interface ImageReadResult {
    /**
     * The text of the first QR code, in reading order (top to bottom, then left to right).
     * [multiple] is true when other, different codes are in the image (D14: only the first is used).
     */
    data class Found(val content: String, val multiple: Boolean) : ImageReadResult

    data class Failed(val reason: ImageFailure) : ImageReadResult
}

/**
 * Reads a QR code from an image the user picked. Everything happens in memory and off the UI thread: the image
 * is never copied, stored or sent anywhere, and the scanned text is never logged.
 */
interface ImageQrReader {
    suspend fun read(uri: Uri): ImageReadResult
}
