package com.lookfirst.data.network

import com.lookfirst.data.platform.DeviceSdkVersion
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.security.KeyStore

private const val MIN_SYSTEM_AUTHORITIES = 100

/**
 * Milestone check on a real device or emulator (not run in CI). Installing a user CA cannot be automated: that part
 * is a manual exit criterion (see T-NET-2), status "to validate on device".
 */
class SystemTrustDeviceTest {
    @Test
    fun api23BuildsARestrictedFactoryFromTheRealAndroidCaStore() {
        assumeTrue(DeviceSdkVersion().current() < 24)
        assertNotNull(SelectingSystemTrust(DeviceSdkVersion()).socketFactory())
    }

    @Test
    fun androidCaStoreExposesManySystemAuthorities() {
        val store = KeyStore.getInstance("AndroidCAStore").also { it.load(null, null) }
        val system = store.aliases().toList().count { it.startsWith("system:") }
        assertTrue("system aliases: $system", system >= MIN_SYSTEM_AUTHORITIES)
    }
}
