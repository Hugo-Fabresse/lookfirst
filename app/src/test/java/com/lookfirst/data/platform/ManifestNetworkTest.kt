package com.lookfirst.data.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The network surface of the app, read from the files that define it (LF-24: INTERNET is the first and only one). */
class ManifestNetworkTest {
    private val manifest = File("src/main/AndroidManifest.xml").readText()
        .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
    private val networkConfig = File("src/main/res/xml/network_security_config.xml").readText()

    @Test
    fun internetIsDeclaredAndNoOtherNetworkOrPackagePermissionIs() {
        val permissions = Regex("""<uses-permission\s+android:name="([^"]+)"""").findAll(manifest)
            .map { it.groupValues[1] }.toSet()
        assertEquals(
            setOf(
                "android.permission.CAMERA",
                "android.permission.HIDE_OVERLAY_WINDOWS",
                "android.permission.INTERNET",
            ),
            permissions,
        )
        assertFalse(manifest.contains("QUERY_ALL_PACKAGES"))
    }

    @Test
    fun cleartextIsForbiddenEverywhereWithNoException() {
        assertTrue(manifest.contains("""android:usesCleartextTraffic="false""""))
        assertTrue(networkConfig.contains("""<base-config cleartextTrafficPermitted="false" />"""))
        assertFalse(networkConfig.contains("domain-config"))
        assertFalse(networkConfig.contains("""cleartextTrafficPermitted="true""""))
        assertFalse(networkConfig.contains("trust-anchors"))
    }

    @Test
    fun theOnlyHostTheAppTalksToIsCompiledIn() {
        assertEquals("codeberg.org", com.lookfirst.data.reputation.ListsEndpoint.HOST)
    }
}
