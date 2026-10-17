package com.lookfirst.data.platform

import android.os.Build
import javax.inject.Inject

/** The Android API level of the running device, as a seam so that version-dependent choices can be tested. */
fun interface SdkVersion {
    fun current(): Int
}

class DeviceSdkVersion @Inject constructor() : SdkVersion {
    override fun current(): Int = Build.VERSION.SDK_INT
}
