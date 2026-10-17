package com.lookfirst.domain.model

/**
 * Text that comes from a scanned QR code. Its [toString] never shows the content, so it cannot leak into logs,
 * crash reports or assertion messages by accident. Read the text explicitly through [value].
 */
@JvmInline
value class Untrusted(val value: String) {
    override fun toString(): String = "Untrusted(len=${value.length})"
}
