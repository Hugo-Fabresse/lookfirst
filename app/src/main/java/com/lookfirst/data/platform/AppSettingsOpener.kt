package com.lookfirst.data.platform

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Opens the Android settings page of this app. Only called after an explicit tap. */
interface AppSettingsOpener {
    fun open()
}

/**
 * Explicit intent to the system "App info" screen of our own package: no data comes from a scan.
 * Started with the application context, hence NEW_TASK.
 */
class AndroidAppSettingsOpener @Inject constructor(@ApplicationContext private val context: Context) :
    AppSettingsOpener {
    override fun open() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (ignored: ActivityNotFoundException) {
            // No settings screen on this device: nothing else to do, the user can scan an image instead.
        }
    }
}
