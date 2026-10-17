package com.lookfirst.data.scan.image

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageMagicBytesTest {
    private fun ascii(text: String) = text.toByteArray(Charsets.ISO_8859_1)

    @Test
    fun acceptsJpegPngWebpAndHeif() {
        assertTrue(ImageMagicBytes.isAccepted(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
        assertTrue(ImageMagicBytes.isAccepted(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0)))
        assertTrue(ImageMagicBytes.isAccepted(ascii("RIFF") + ByteArray(4) + ascii("WEBPVP8 ")))
        for (brand in listOf("heic", "heix", "mif1", "msf1")) {
            assertTrue(ImageMagicBytes.isAccepted(ByteArray(4) + ascii("ftyp$brand") + ByteArray(8)))
        }
    }

    @Test
    fun refusesEverythingElse() {
        assertFalse(ImageMagicBytes.isAccepted(ByteArray(0)))
        assertFalse(ImageMagicBytes.isAccepted(ascii("GIF89a") + ByteArray(20)))
        assertFalse(ImageMagicBytes.isAccepted(ascii("<svg xmlns='http://www.w3.org/2000/svg'/>")))
        assertFalse(ImageMagicBytes.isAccepted(ascii("%PDF-1.7") + ByteArray(20)))
        assertFalse(ImageMagicBytes.isAccepted(ascii("RIFF") + ByteArray(4) + ascii("WAVEfmt ")))
        assertFalse(ImageMagicBytes.isAccepted(ByteArray(4) + ascii("ftypavif") + ByteArray(8)))
        assertFalse(ImageMagicBytes.isAccepted(byteArrayOf(0xFF.toByte(), 0xD8.toByte())))
    }
}
