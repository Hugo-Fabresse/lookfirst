package com.lookfirst.data.platform

import java.util.UUID
import javax.inject.Inject

/** A fresh identifier per displayed scan: it ties a verdict to the content shown (ux.md 4.1) and carries nothing else. */
fun interface PayloadIdGenerator {
    fun newId(): String
}

class RandomPayloadIdGenerator @Inject constructor() : PayloadIdGenerator {
    override fun newId(): String = UUID.randomUUID().toString()
}
