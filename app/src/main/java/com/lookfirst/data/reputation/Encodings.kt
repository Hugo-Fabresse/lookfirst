package com.lookfirst.data.reputation

import java.security.MessageDigest

internal fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
