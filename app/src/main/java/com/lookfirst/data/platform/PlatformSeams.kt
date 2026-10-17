package com.lookfirst.data.platform

import android.content.Context
import android.provider.Telephony
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject

/** Package of the user's default SMS app, null when the device has none (tablet). A seam for tests. */
fun interface DefaultSmsPackage {
    fun get(): String?
}

class AndroidDefaultSmsPackage @Inject constructor(@ApplicationContext private val context: Context) :
    DefaultSmsPackage {
    override fun get(): String? = Telephony.Sms.getDefaultSmsPackage(context)
}

/** A fresh host name for the browser probe on each call, so that no app can register for it. */
fun interface ProbeHost {
    fun next(): String
}

class RandomProbeHost @Inject constructor() : ProbeHost {
    override fun next(): String = UUID.randomUUID().toString().replace("-", "") + ".invalid"
}
