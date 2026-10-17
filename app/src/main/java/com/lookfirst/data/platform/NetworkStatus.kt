package com.lookfirst.data.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Is there a network at all? Used only to disable the optional checks offline; a seam for tests. */
fun interface NetworkStatus {
    fun isOnline(): Boolean
}

class AndroidNetworkStatus @Inject constructor(@ApplicationContext private val context: Context) : NetworkStatus {
    /** When the answer cannot be read, the check stays available: a failed attempt is explained, a hidden one is not. */
    override fun isOnline(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return true
        return try {
            val network = manager.activeNetwork ?: return false
            manager.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        } catch (ignored: SecurityException) {
            true
        }
    }
}
