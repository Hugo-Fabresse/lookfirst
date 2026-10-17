package com.lookfirst

import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.lookfirst.data.platform.AppPreferences
import com.lookfirst.data.platform.ClipboardWriter
import com.lookfirst.data.reputation.ListUpdateControl
import com.lookfirst.data.scan.camera.QrAnalyzerFactory
import com.lookfirst.ui.components.TouchGuard
import com.lookfirst.ui.components.isPartiallyObscured
import com.lookfirst.ui.navigation.LookFirstNavHost
import com.lookfirst.ui.screens.result.ResultViewModel
import com.lookfirst.ui.screens.scan.ScanViewModel
import com.lookfirst.ui.screens.settings.SettingsViewModel
import com.lookfirst.ui.theme.LookFirstTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val scanViewModel: ScanViewModel by viewModels()
    private val resultViewModel: ResultViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()

    @Inject
    lateinit var analyzerFactory: QrAnalyzerFactory

    @Inject
    lateinit var clipboard: ClipboardWriter

    @Inject
    lateinit var preferences: AppPreferences

    @Inject
    lateinit var listUpdates: ListUpdateControl

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Stays on the main thread on purpose: the start destination must be known before the first frame, and this is
        // one boolean of a tiny preferences file (a blocking read here is the cost of not showing the wrong screen).
        val showFirstLaunch = !preferences.firstLaunchDone()
        setContent {
            LookFirstTheme {
                LookFirstNavHost(
                    scanViewModel = scanViewModel,
                    resultViewModel = resultViewModel,
                    settingsViewModel = settingsViewModel,
                    analyzerFactory = analyzerFactory,
                    showFirstLaunch = showFirstLaunch,
                    onFirstLaunchDone = listUpdates::completeFirstLaunch,
                )
            }
        }
        hardenAgainstOverlays()
    }

    override fun onStop() {
        super.onStop()
        // A rotation also stops the activity: our own secret must stay until it is pasted.
        if (!isChangingConfigurations) clipboard.onAppStopped()
    }

    /** From API 29 the clipboard is readable only with the focus: a sensitive copy is cleared here (T-SCH-11). */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) clipboard.onWindowFocused()
    }

    /** Reports touches that arrive under an overlay to [TouchGuard], which the action buttons consult. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean =
        TouchGuard.track(ev.isPartiallyObscured()) { super.dispatchTouchEvent(ev) }

    /**
     * Threat model T-UI-3 (tapjacking): touches are dropped while another window covers ours, and on API 31+
     * the system hides non-system overlay windows (needs the HIDE_OVERLAY_WINDOWS permission, see manifest).
     */
    private fun hardenAgainstOverlays() {
        findViewById<ViewGroup>(android.R.id.content).getChildAt(0)?.filterTouchesWhenObscured = true
        window.decorView.filterTouchesWhenObscured = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) window.setHideOverlayWindows(true)
    }
}
