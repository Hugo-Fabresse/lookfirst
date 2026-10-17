package com.lookfirst.data.scan.image

/** Signature check done before the platform decoder sees a file (threat model T-IMG-1). */
internal object ImageMagicBytes {
    private const val FTYP_OFFSET = 4
    private const val WEBP_OFFSET = 8
    private const val BRAND_OFFSET = 8
    private val JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val RIFF = "RIFF".toByteArray()
    private val WEBP = "WEBP".toByteArray()
    private val FTYP = "ftyp".toByteArray()
    private val HEIF_BRANDS = listOf("heic", "heix", "mif1", "msf1").map(String::toByteArray)

    /** jpeg `FF D8 FF`, png, webp `RIFF....WEBP`, heif `ftyp` + heic / heix / mif1 / msf1. */
    fun isAccepted(bytes: ByteArray): Boolean = startsWith(bytes, 0, JPEG) ||
        startsWith(bytes, 0, PNG) ||
        (startsWith(bytes, 0, RIFF) && startsWith(bytes, WEBP_OFFSET, WEBP)) ||
        (startsWith(bytes, FTYP_OFFSET, FTYP) && HEIF_BRANDS.any { startsWith(bytes, BRAND_OFFSET, it) })

    private fun startsWith(bytes: ByteArray, offset: Int, prefix: ByteArray): Boolean =
        bytes.size >= offset + prefix.size && prefix.indices.all { bytes[offset + it] == prefix[it] }
}
