package com.lookfirst.data.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Whether the camera permission is currently granted. The request itself is made by the UI (Activity Result API). */
interface CameraPermission {
    fun isGranted(): Boolean
}

class AndroidCameraPermission @Inject constructor(@ApplicationContext private val context: Context) :
    CameraPermission {
    override fun isGranted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
}
