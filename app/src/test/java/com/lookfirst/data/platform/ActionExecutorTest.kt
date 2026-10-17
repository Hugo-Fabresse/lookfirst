package com.lookfirst.data.platform

import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import android.net.wifi.WifiNetworkSuggestion
import android.provider.Settings
import com.lookfirst.domain.decoder.PayloadParser
import com.lookfirst.domain.decoder.decode
import com.lookfirst.domain.model.ParseResult
import com.lookfirst.domain.model.ScanPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

private const val BROWSER = "org.example.browser"
private const val OTHER_BROWSER = "org.example.otherbrowser"
private const val SHOP_APP = "org.example.shop"
private const val SMS_APP = "org.example.sms"
private const val SYSTEM_SETTINGS = "com.android.settings"

/** Robolectric (sdk 34 only): the Intents LookFirst builds, checked on what the system would receive. */
@RunWith(RobolectricTestRunner::class)
class ActionExecutorTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val packageManager = shadowOf(app.packageManager)
    private var sdk = 34

    private var smsPackage: String? = SMS_APP

    private fun executor() = AndroidActionExecutor(
        app,
        SdkVersion { sdk },
        DefaultSmsPackage { smsPackage },
        ProbeHost { "probe.invalid" },
    )

    private fun resolveInfo(pkg: String, system: Boolean = false) = ResolveInfo().apply {
        activityInfo = ActivityInfo().apply {
            packageName = pkg
            name = "$pkg.Main"
            applicationInfo = ApplicationInfo().apply {
                packageName = pkg
                if (system) flags = ApplicationInfo.FLAG_SYSTEM
            }
        }
    }

    private fun selector() = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER)

    private fun dialogOf(outcome: OpenOutcome): Intent = (outcome as OpenOutcome.NeedsResult).request.intent

    private fun browserProbe() = Intent(Intent.ACTION_VIEW, Uri.parse("https://probe.invalid"))
        .addCategory(Intent.CATEGORY_BROWSABLE)

    /** The device has [packages] as browsers; the first one is the default. */
    private fun installBrowsers(vararg packages: String) {
        for (pkg in packages) {
            packageManager.addResolveInfoForIntent(browserProbe(), resolveInfo(pkg))
            packageManager.addResolveInfoForIntent(selector(), resolveInfo(pkg))
        }
    }

    private fun installHandler(canonical: String, vararg packages: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(canonical)).addCategory(Intent.CATEGORY_BROWSABLE)
        for (pkg in packages) packageManager.addResolveInfoForIntent(intent, resolveInfo(pkg))
    }

    private inline fun <reified T : ScanPayload> payload(raw: String): T = decode(raw)

    /** The decoded value of [name] in the query of an opaque URI (`mailto:`, `geo:`), which `Uri` cannot parse. */
    private fun queryOf(intent: Intent, name: String): String? = intent.dataString.orEmpty().substringAfter('?')
        .split('&').map { it.split('=', limit = 2) }.firstOrNull { it[0] == name }?.getOrNull(1)?.let { Uri.decode(it) }

    private fun started(): Intent? = shadowOf(app).nextStartedActivity

    private fun openUrl(raw: String): OpenOutcome = executor().open(payload<ScanPayload.Url>(raw))

    @Test
    fun aLinkOpensInTheDefaultBrowserOnly() {
        installBrowsers(BROWSER, OTHER_BROWSER)
        assertEquals(OpenOutcome.Started, openUrl("https://example.com/path?q=1#top"))
        val intent = checkNotNull(started())
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https://example.com/path?q=1#top", intent.dataString)
        assertEquals(setOf(Intent.CATEGORY_BROWSABLE), intent.categories)
        assertEquals(BROWSER, intent.`package`)
        assertNull(intent.extras)
        assertNull(intent.clipData)
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, intent.flags)
    }

    @Test
    fun aBackslashBeforeAnAtSignNeverGivesTheDecoyHost() {
        installBrowsers(BROWSER)
        assertEquals(OpenOutcome.Started, openUrl("https://evil.tld\\@paypal.com"))
        val intent = checkNotNull(started())
        assertEquals("evil.tld", intent.data?.host)
        assertEquals("https://evil.tld/@paypal.com", intent.dataString)
        assertFalse(intent.data?.host == "paypal.com")
    }

    @Test
    fun theUserinfoNeverReachesTheBrowser() {
        installBrowsers(BROWSER)
        assertEquals(OpenOutcome.Started, openUrl("https://paypal.com@evil.example"))
        val intent = checkNotNull(started())
        assertEquals("evil.example", intent.data?.host)
        assertNull(intent.data?.userInfo)
        assertEquals("https://evil.example/", intent.dataString)
    }

    @Test
    fun anInternationalHostIsGivenAsPunycodeAndThePortIsKept() {
        installBrowsers(BROWSER)
        assertEquals(OpenOutcome.Started, openUrl("https://bücher.example:8443/x"))
        val intent = checkNotNull(started())
        assertEquals("xn--bcher-kva.example", intent.data?.host)
        assertEquals(8443, intent.data?.port)
    }

    @Test
    fun aDefaultPortStaysExplicitAndAMissingOneStaysMissing() {
        installBrowsers(BROWSER)
        openUrl("http://example.com:80/")
        assertEquals(80, checkNotNull(started()).data?.port)
        openUrl("http://example.com/")
        assertEquals(-1, checkNotNull(started()).data?.port)
    }

    @Test
    fun aUrlThatCannotBeRebuiltIsRefusedAndNothingStarts() {
        installBrowsers(BROWSER)
        assertEquals(OpenOutcome.Unsafe, openUrl("https://example.com:99999/"))
        assertEquals(OpenOutcome.Unsafe, openUrl("https://?x=1"))
        assertEquals(OpenOutcome.Unsafe, openUrl("https://exa<mple.com/"))
        assertNull(started())
    }

    @Test
    fun withoutADefaultBrowserNothingIsStartedAndTheUserIsTold() {
        packageManager.addResolveInfoForIntent(browserProbe(), resolveInfo("android"))
        assertEquals(OpenOutcome.NoApp, openUrl("https://example.com/"))
        assertNull(started())
    }

    @Test
    fun aPackageKnownOnlyAsABrowserApplicationIsNotACapture() {
        installBrowsers(BROWSER)
        packageManager.addResolveInfoForIntent(selector(), resolveInfo(OTHER_BROWSER))
        installHandler("https://x.example/", OTHER_BROWSER)
        assertFalse(executor().hasNonBrowserHandler(payload("https://x.example/")))
    }

    @Test
    fun anObfuscatedIpv4IsOpenedAsDottedDecimal() {
        installBrowsers(BROWSER)
        assertEquals(OpenOutcome.Started, openUrl("https://0x7f.1/x"))
        assertEquals("127.0.0.1", checkNotNull(started()).data?.host)
    }

    @Test
    fun aMalformedIpv6IsRefused() {
        installBrowsers(BROWSER)
        assertEquals(OpenOutcome.Unsafe, openUrl("https://[1:2:3]/"))
        assertEquals(OpenOutcome.Unsafe, openUrl("https://[1::2::3]/"))
        assertNull(started())
    }

    @Test
    fun anAppLinkHandlerThatIsNotABrowserIsReported() {
        installBrowsers(BROWSER)
        installHandler("https://shop.example/item", SHOP_APP)
        installHandler("https://plain.example/item", BROWSER)
        assertTrue(executor().hasNonBrowserHandler(payload("https://shop.example/item")))
        assertFalse(executor().hasNonBrowserHandler(payload("https://plain.example/item")))
        assertFalse(executor().hasNonBrowserHandler(payload("https://none.example/")))
        assertNull(started())
    }

    @Test
    fun askingAboutHandlersStartsNothingAndIgnoresUnusableLinks() {
        installBrowsers(BROWSER)
        assertFalse(executor().hasNonBrowserHandler(payload("https://example.com:99999/")))
        assertNull(started())
    }

    @Test
    fun aPhoneNumberOpensTheDialerNeverACall() {
        val outcome = executor().open(payload<ScanPayload.Tel>("tel:+33123456789"))
        assertEquals(OpenOutcome.Started, outcome)
        val intent = checkNotNull(started())
        assertEquals(Intent.ACTION_DIAL, intent.action)
        assertEquals("+33123456789", intent.data?.schemeSpecificPart)
        assertNull(intent.extras)
    }

    @Test
    fun aUssdCodeStaysInTheDialerAsText() {
        executor().open(payload<ScanPayload.Tel>("tel:*#06#"))
        val intent = checkNotNull(started())
        assertEquals(Intent.ACTION_DIAL, intent.action)
        assertEquals("tel", intent.data?.scheme)
    }

    @Test
    fun aNumberWithLettersIsRefused() {
        assertEquals(OpenOutcome.Unsafe, executor().open(payload<ScanPayload.Tel>("tel:12abc")))
        assertNull(started())
    }

    @Test
    fun anSmsIsPrefilledNotSent() {
        val outcome = executor().open(payload<ScanPayload.Sms>("SMSTO:+33123456789:Hello there"))
        assertEquals(OpenOutcome.Started, outcome)
        val intent = checkNotNull(started())
        assertEquals(Intent.ACTION_SENDTO, intent.action)
        assertEquals("smsto", intent.data?.scheme)
        assertEquals("+33123456789", intent.data?.schemeSpecificPart)
        assertEquals("Hello there", intent.getStringExtra("sms_body"))
        assertEquals(setOf("sms_body"), intent.extras?.keySet())
        assertEquals(SMS_APP, intent.`package`)
    }

    @Test
    fun withoutADefaultSmsAppNothingIsStarted() {
        smsPackage = null
        assertEquals(OpenOutcome.NoApp, executor().open(payload<ScanPayload.Sms>("SMSTO:+33123456789:Hello")))
        assertNull(started())
    }

    @Test
    fun anEmailHasRecipientSubjectAndBodyButNoAttachmentNorHiddenRecipient() {
        val raw = "mailto:bob@example.com?subject=Hi%20there&body=Line%201&cc=eve@example.com&bcc=mallory@example.com" +
            "&attach=/sdcard/secret"
        assertEquals(OpenOutcome.Started, executor().open(payload<ScanPayload.Mail>(raw)))
        val intent = checkNotNull(started())
        assertEquals(Intent.ACTION_SENDTO, intent.action)
        assertEquals("mailto", intent.data?.scheme)
        assertEquals("bob@example.com", intent.data?.schemeSpecificPart?.substringBefore('?'))
        assertEquals("Hi there", queryOf(intent, "subject"))
        assertEquals("Line 1", queryOf(intent, "body"))
        assertFalse(intent.dataString.orEmpty().contains("mallory"))
        assertFalse(intent.dataString.orEmpty().contains("eve@"))
        assertFalse(intent.dataString.orEmpty().contains("secret"))
        assertNull(intent.extras)
    }

    @Test
    fun aGeoPointIsRebuiltFromTheParsedNumbers() {
        val outcome = executor().open(payload<ScanPayload.Geo>("geo:48.8584,2.2945?q=Eiffel Tower"))
        assertEquals(OpenOutcome.Started, outcome)
        val intent = checkNotNull(started())
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("geo", intent.data?.scheme)
        assertEquals("48.8584,2.2945", intent.data?.schemeSpecificPart?.substringBefore('?'))
        assertEquals("Eiffel Tower", queryOf(intent, "q"))
    }

    @Test
    fun aGeoPointWithoutLabelHasNoQuery() {
        executor().open(payload<ScanPayload.Geo>("geo:0.00001,-1.5"))
        assertEquals("geo:0.00001,-1.5", checkNotNull(started()).dataString)
    }

    @Test
    fun anAppWithoutHandlerGivesNoAppInsteadOfCrashing() {
        shadowOf(app).checkActivities(true)
        assertEquals(OpenOutcome.NoApp, executor().open(payload<ScanPayload.Tel>("tel:+33123456789")))
    }

    @Test
    fun wifiOnApi30PlusUsesTheSystemDialogRestrictedToASystemPackage() {
        val add = Intent(Settings.ACTION_WIFI_ADD_NETWORKS)
        packageManager.addResolveInfoForIntent(add, resolveInfo("org.evil.settings"))
        packageManager.addResolveInfoForIntent(add, resolveInfo(SYSTEM_SETTINGS, system = true))
        val outcome = executor().open(payload<ScanPayload.Wifi>("WIFI:T:WPA;S:HomeNet;P:hunter2222;;"))
        val intent = dialogOf(outcome)
        assertNull(started())
        assertEquals(Settings.ACTION_WIFI_ADD_NETWORKS, intent.action)
        assertEquals(SYSTEM_SETTINGS, intent.`package`)
        assertEquals(0, intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
        val list = intent.getParcelableArrayListExtra<WifiNetworkSuggestion>(Settings.EXTRA_WIFI_NETWORK_LIST)
        assertEquals(1, list?.size)
        assertEquals("HomeNet", list?.first()?.ssid)
        assertNull(intent.data)
    }

    @Test
    fun anOpenNetworkIsSuggestedWithoutPassphrase() {
        packageManager.addResolveInfoForIntent(
            Intent(Settings.ACTION_WIFI_ADD_NETWORKS),
            resolveInfo(SYSTEM_SETTINGS, system = true),
        )
        val intent = dialogOf(executor().open(payload<ScanPayload.Wifi>("WIFI:T:nopass;S:Cafe;;")))
        assertEquals(Settings.ACTION_WIFI_ADD_NETWORKS, intent.action)
        val list = intent.getParcelableArrayListExtra<WifiNetworkSuggestion>(Settings.EXTRA_WIFI_NETWORK_LIST)
        assertNull(checkNotNull(list)[0].passphrase)
    }

    @Test
    fun wifiBelowApi30OpensTheSettingsPageAndKeepsTheCredentialsOnScreen() {
        sdk = 28
        val outcome = executor().open(payload<ScanPayload.Wifi>("WIFI:T:WPA;S:HomeNet;P:hunter2222;;"))
        assertEquals(OpenOutcome.StartedKeepScreen, outcome)
        val intent = checkNotNull(started())
        assertEquals(Settings.ACTION_WIFI_SETTINGS, intent.action)
        assertNull(intent.extras)
        assertNull(intent.data)
    }

    @Test
    fun aWepNetworkOrAnInvalidPassphraseFallsBackToTheSettingsPage() {
        packageManager.addResolveInfoForIntent(
            Intent(Settings.ACTION_WIFI_ADD_NETWORKS),
            resolveInfo(SYSTEM_SETTINGS, system = true),
        )
        assertEquals(
            OpenOutcome.StartedKeepScreen,
            executor().open(payload<ScanPayload.Wifi>("WIFI:T:WEP;S:Old;P:abcde;;")),
        )
        assertEquals(Settings.ACTION_WIFI_SETTINGS, checkNotNull(started()).action)
        assertEquals(
            OpenOutcome.StartedKeepScreen,
            executor().open(payload<ScanPayload.Wifi>("WIFI:T:WPA;S:Short;P:abc;;")),
        )
        assertEquals(Settings.ACTION_WIFI_SETTINGS, checkNotNull(started()).action)
    }

    @Test
    fun withoutASystemDialogTheSettingsPageIsUsed() {
        packageManager.addResolveInfoForIntent(
            Intent(Settings.ACTION_WIFI_ADD_NETWORKS),
            resolveInfo("org.evil.settings"),
        )
        val outcome = executor().open(payload<ScanPayload.Wifi>("WIFI:T:WPA;S:HomeNet;P:hunter2222;;"))
        assertEquals(OpenOutcome.StartedKeepScreen, outcome)
        assertEquals(Settings.ACTION_WIFI_SETTINGS, checkNotNull(started()).action)
    }

    @Test
    fun contentWithoutAnOpenActionStartsNothing() {
        val samples = listOf(
            "otpauth://totp/Acme:bob?secret=JBSWY3DPEHPK3PXP&issuer=Acme",
            "monappli://pay?to=bob",
            "javascript:alert(1)",
            "intent://x#Intent;component=a/b;end",
            "bitcoin:bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq",
            "hello",
            "BCD\n002\n1\nSCT\nBPOTBEB1\nBob\nBE68539007547034\nEUR12.5\n\n\n",
        )
        for (raw in samples) {
            val content = (PayloadParser.parse(raw) as ParseResult.Ok).payload
            assertEquals(raw, OpenOutcome.Unsafe, executor().open(content))
        }
        assertNull(started())
    }
}
