package com.lookfirst.data.platform

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import com.lookfirst.domain.model.ScanPayload
import com.lookfirst.domain.open.CanonicalUrl
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Package name Android reports when no default app is set and the chooser would be shown. */
private const val RESOLVER_PACKAGE = "android"

/** What happened to an explicit "Open" tap. Typed: no system message ever reaches the user. */
sealed interface OpenOutcome {
    /** The target app was started: the user continues there, LookFirst goes back to scanning. */
    data object Started : OpenOutcome

    /**
     * The Wi-Fi settings page was opened because the network cannot be added by the system dialog (API < 30, WEP).
     * The credentials are still needed on screen, so LookFirst stays where it is.
     */
    data object StartedKeepScreen : OpenOutcome

    /** The content cannot be turned into an Intent that is guaranteed to match what was analysed. */
    data object Unsafe : OpenOutcome

    /** No application can handle this content. */
    data object NoApp : OpenOutcome

    /**
     * A system dialog must be started for a result (the Wi-Fi add-network dialog, API 30+). The screen launches
     * [request] through an activity result launcher and reports whether the user accepted.
     */
    data class NeedsResult(val request: SystemDialogRequest) : OpenOutcome
}

/** A system dialog to start for a result. Opaque to the UI; its content (a password) never reaches a log. */
class SystemDialogRequest internal constructor(val intent: Intent) {
    override fun toString(): String = "SystemDialogRequest"
}

/**
 * The ONLY class that starts an Intent from scanned content (threat model T-SCH-8, ADR-0003). Called solely from a
 * button handler, after the user's explicit tap (and the confirmation for SUSPICIOUS / DANGEROUS). Intents are built
 * from the parsed fields, never from the raw text; see [OpenIntents].
 */
interface ActionExecutor {
    /** True when an app that is not a browser would capture this link (verified App Link). Starts nothing. */
    fun hasNonBrowserHandler(url: ScanPayload.Url): Boolean

    /** Starts the action that matches [payload]; [OpenOutcome.Unsafe] for any type that has none. */
    fun open(payload: ScanPayload): OpenOutcome
}

/**
 * Started with the application context, hence `FLAG_ACTIVITY_NEW_TASK` (required there). http(s) goes to the default
 * browser only (`setPackage`), so that an App Link cannot capture it; there is no Custom Tab and no WebView.
 */
class AndroidActionExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sdk: SdkVersion,
    private val smsApp: DefaultSmsPackage,
    private val probeHost: ProbeHost,
) : ActionExecutor {
    private val packages: PackageManager get() = context.packageManager

    override fun hasNonBrowserHandler(url: ScanPayload.Url): Boolean {
        val uri = verifiedUri(url) ?: return false
        val browsers = browserPackages(probeHost.next())
        val handlers = queryPackages(OpenIntents.browse(uri, null), PackageManager.MATCH_DEFAULT_ONLY)
        return handlers.any { it !in browsers }
    }

    override fun open(payload: ScanPayload): OpenOutcome = when (payload) {
        is ScanPayload.Url -> openUrl(payload)
        is ScanPayload.Tel -> start(OpenIntents.dial(payload))
        is ScanPayload.Sms -> openSms(payload)
        is ScanPayload.Mail -> start(OpenIntents.mail(payload))
        is ScanPayload.Geo -> start(OpenIntents.geo(payload))
        is ScanPayload.Wifi -> openWifi(payload)
        else -> OpenOutcome.Unsafe
    }

    private fun openSms(sms: ScanPayload.Sms): OpenOutcome {
        val smsPackage = smsApp.get() ?: return OpenOutcome.NoApp
        return start(OpenIntents.sms(sms, smsPackage))
    }

    private fun openUrl(url: ScanPayload.Url): OpenOutcome {
        val uri = verifiedUri(url) ?: return OpenOutcome.Unsafe
        val browser = defaultBrowser() ?: return OpenOutcome.NoApp
        return start(OpenIntents.browse(uri, browser))
    }

    /** The canonical URL parsed back by `android.net.Uri`, only if scheme, host and port are the analysed ones. */
    private fun verifiedUri(url: ScanPayload.Url): Uri? {
        val canonical = CanonicalUrl.of(url) ?: return null
        val uri = Uri.parse(canonical.value)
        return if (matches(uri, canonical)) uri else null
    }

    /** Only the analysed scheme, host and port, no userinfo, and exactly the canonical text. */
    private fun matches(uri: Uri, url: CanonicalUrl): Boolean = uri.scheme == url.scheme &&
        uri.host == url.host &&
        uri.port == (url.port ?: -1) &&
        uri.userInfo == null &&
        uri.toString() == url.value

    /** Browsers: the apps that declare the browser role, and those that answer for an address nobody can claim. */
    private fun browserPackages(host: String): Set<String> =
        queryPackages(OpenIntents.appBrowserSelector(), 0) + queryPackages(OpenIntents.browserProbe(host), 0)

    /** The package that answers for an address no site claims; null when none is the default (chooser). */
    private fun defaultBrowser(): String? {
        val host = probeHost.next()
        val probe = OpenIntents.browserProbe(host)
        val chosen = packages.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        return chosen?.takeIf { it != RESOLVER_PACKAGE && it in browserPackages(host) }
    }

    /** The add-network dialog exists from API 30; the level comes from [sdk], which lint cannot follow. */
    @SuppressLint("NewApi")
    private fun openWifi(wifi: ScanPayload.Wifi): OpenOutcome {
        val add = if (sdk.current() >= Build.VERSION_CODES.R) OpenIntents.addWifiNetwork(wifi) else null
        val system = add?.let { systemPackageFor(it) }
        return if (add != null && system != null) {
            OpenOutcome.NeedsResult(SystemDialogRequest(add.setPackage(system)))
        } else {
            start(OpenIntents.wifiSettings(), OpenOutcome.StartedKeepScreen)
        }
    }

    /** The add-network dialog may only come from a system package. */
    private fun systemPackageFor(intent: Intent): String? = queryInfos(intent, PackageManager.MATCH_SYSTEM_ONLY)
        .firstOrNull { it.activityInfo?.applicationInfo?.flags?.and(ApplicationInfo.FLAG_SYSTEM) != 0 }
        ?.activityInfo?.packageName

    private fun start(intent: Intent?, success: OpenOutcome = OpenOutcome.Started): OpenOutcome {
        if (intent == null) return OpenOutcome.Unsafe
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            success
        } catch (ignored: ActivityNotFoundException) {
            OpenOutcome.NoApp
        } catch (ignored: SecurityException) {
            OpenOutcome.NoApp
        }
    }

    private fun queryPackages(intent: Intent, flags: Int): Set<String> =
        queryInfos(intent, flags).mapNotNull { it.activityInfo?.packageName }.toSet()

    private fun queryInfos(intent: Intent, flags: Int): List<ResolveInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packages.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            packages.queryIntentActivities(intent, flags)
        }
}
