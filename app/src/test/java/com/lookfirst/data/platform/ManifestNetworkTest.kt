package com.lookfirst.data.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File

/** The network surface of the app, read from the files that define it (LF-24: INTERNET; LF-26: ACCESS_NETWORK_STATE). */
class ManifestNetworkTest {
    private val manifest = File("src/main/AndroidManifest.xml").readText()
        .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
    private val networkConfig = File("src/main/res/xml/network_security_config.xml").readText()

    private fun declared(text: String): Map<String, Boolean> =
        Regex("""<uses-permission\s+([^>]*?)/?>""", RegexOption.DOT_MATCHES_ALL).findAll(text).associate {
            val attributes = it.groupValues[1]
            Regex("""android:name="([^"]+)"""").find(attributes)!!.groupValues[1] to
                attributes.contains("""tools:node="remove"""")
        }

    @Test
    fun internetIsDeclaredAndNoOtherNetworkOrPackagePermissionIs() {
        val permissions = declared(manifest).filterValues { !it }.keys
        assertEquals(
            setOf(
                "android.permission.CAMERA",
                "android.permission.HIDE_OVERLAY_WINDOWS",
                "android.permission.INTERNET",
                "android.permission.ACCESS_NETWORK_STATE",
            ),
            permissions,
        )
        assertFalse(manifest.contains("QUERY_ALL_PACKAGES"))
    }

    @Test
    fun unneededWorkManagerPermissionsAndReceiverAreRemovedFromTheMergedManifest() {
        val removed = declared(manifest).filterValues { it }.keys
        assertTrue("android.permission.FOREGROUND_SERVICE" in removed)
        assertTrue("android.permission.RECEIVE_BOOT_COMPLETED" in removed)
        val receiver = Regex("""<receiver\s+[^>]*DiagnosticsReceiver[^>]*>""", RegexOption.DOT_MATCHES_ALL)
            .find(manifest)?.value
        assertTrue(receiver != null && receiver.contains("""tools:node="remove""""))
    }

    /** Needs a previous build (`processDebugManifest`); skipped when the merged manifest is not there. */
    @Test
    fun mergedManifestKeepsToTheWhitelist() {
        val file = File("build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml")
        Assume.assumeTrue("no merged manifest, build first", file.isFile)
        val merged = file.readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        val allowed = setOf(
            "android.permission.CAMERA",
            "android.permission.HIDE_OVERLAY_WINDOWS",
            "android.permission.INTERNET",
            "android.permission.WAKE_LOCK",
            "android.permission.ACCESS_NETWORK_STATE",
            "com.lookfirst.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
        )
        assertEquals(allowed, declared(merged).keys)
        // Exported components: the launcher activity, components guarded by a system permission, debug tooling.
        val systemGuards = setOf("android.permission.BIND_JOB_SERVICE", "android.permission.DUMP")
        val debugTooling = setOf("androidx.compose.ui.tooling.PreviewActivity", "androidx.activity.ComponentActivity")
        val exported = Regex("""<(activity|service|receiver|provider)\s+[^>]*>""").findAll(merged)
            .map { it.value }.filter { it.contains("""android:exported="true"""") }
        exported.forEach { tag ->
            val name = Regex("""android:name="([^"]+)"""").find(tag)!!.groupValues[1]
            val guard = Regex("""android:permission="([^"]+)"""").find(tag)?.groupValues?.get(1)
            val ok = name == "com.lookfirst.MainActivity" || guard in systemGuards || name in debugTooling
            assertTrue("exported component not allowed: $name", ok)
        }
        assertFalse(merged.contains("DiagnosticsReceiver"))
    }

    @Test
    fun cleartextIsForbiddenEverywhereWithNoException() {
        assertTrue(manifest.contains("""android:usesCleartextTraffic="false""""))
        assertTrue(networkConfig.contains("""<base-config cleartextTrafficPermitted="false">"""))
        assertFalse(networkConfig.contains("domain-config"))
        assertFalse(networkConfig.contains("""cleartextTrafficPermitted="true""""))
        // System authorities only (API 24+); API 23 is covered by SystemTrust.
        val sources = Regex("""<certificates\s+src="([^"]+)"""").findAll(networkConfig).map { it.groupValues[1] }
        assertEquals(listOf("system"), sources.toList())
        assertFalse(networkConfig.contains("""src="user""""))
    }

    @Test
    fun theOnlyHostsTheAppTalksToAreCompiledIn() {
        val hosts = com.lookfirst.data.reputation.ListSources.ALL.map { it.url.host }
        assertEquals(listOf("hole.cert.pl"), hosts)
        assertTrue(com.lookfirst.data.reputation.ListSources.ALL.all { it.url.scheme == "https" })
    }
}
