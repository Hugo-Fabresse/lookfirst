package com.lookfirst

import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.lookfirst.data.scan.camera.QrAnalyzerFactory
import com.lookfirst.ui.screens.scan.ScanRoute
import com.lookfirst.ui.screens.scan.ScanViewModel
import com.lookfirst.ui.theme.LookFirstTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val scanViewModel: ScanViewModel by viewModels()

    @Inject
    lateinit var analyzerFactory: QrAnalyzerFactory

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LookFirstTheme {
                ScanRoute(viewModel = scanViewModel, analyzerFactory = analyzerFactory)
            }
        }
        hardenAgainstOverlays()
    }

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
