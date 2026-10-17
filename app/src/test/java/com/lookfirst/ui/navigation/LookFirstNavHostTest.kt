package com.lookfirst.ui.navigation

import android.net.Uri
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.lookfirst.R
import com.lookfirst.data.platform.ActionExecutor
import com.lookfirst.data.platform.AppSettingsOpener
import com.lookfirst.data.platform.CameraPermission
import com.lookfirst.data.platform.ClipboardWriter
import com.lookfirst.data.platform.OpenOutcome
import com.lookfirst.data.platform.PayloadIdGenerator
import com.lookfirst.data.reputation.ListUpdateControl
import com.lookfirst.data.reputation.ListsStatus
import com.lookfirst.data.reputation.NoReputationLists
import com.lookfirst.data.scan.camera.QrAnalyzerFactory
import com.lookfirst.data.scan.image.ImageQrReader
import com.lookfirst.data.scan.image.ImageReadResult
import com.lookfirst.domain.model.ScanPayload
import com.lookfirst.domain.rdap.RdapClient
import com.lookfirst.domain.rdap.RdapFailure
import com.lookfirst.domain.rdap.RdapOutcome
import com.lookfirst.domain.rdap.RdapPlan
import com.lookfirst.domain.rdap.RdapPlanner
import com.lookfirst.domain.rdap.RdapUnavailable
import com.lookfirst.domain.redirect.TraceFailure
import com.lookfirst.domain.redirect.TraceOutcome
import com.lookfirst.domain.usecase.AnalyzeScan
import com.lookfirst.domain.usecase.ApplyDomainAge
import com.lookfirst.domain.usecase.ApplyRedirectTrace
import com.lookfirst.ui.screens.result.ResultUiState
import com.lookfirst.ui.screens.result.ResultViewModel
import com.lookfirst.ui.screens.scan.ScanUiState
import com.lookfirst.ui.screens.scan.ScanViewModel
import com.lookfirst.ui.screens.settings.SettingsViewModel
import com.lookfirst.ui.theme.LookFirstTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Navigation between the screens with real view models and fakes at the platform edge (no camera, no network). */
@RunWith(RobolectricTestRunner::class)
class LookFirstNavHostTest {
    @get:Rule
    val composeRule = createComposeRule()

    private class Image(var content: String) : ImageQrReader {
        override suspend fun read(uri: Uri): ImageReadResult = ImageReadResult.Found(content, multiple = false)
    }

    private class NoControl : ListUpdateControl {
        override val status: StateFlow<ListsStatus> = MutableStateFlow(ListsStatus(null, null, true, null, false))

        override fun refresh() = Unit

        override fun setAutoUpdate(enabled: Boolean) = Unit

        override fun updateNow() = Unit

        override fun completeFirstLaunch(autoUpdate: Boolean) = Unit

        override fun applySchedule() = Unit
    }

    private class Actions : ActionExecutor {
        val opened = mutableListOf<ScanPayload>()

        override fun hasNonBrowserHandler(url: ScanPayload.Url): Boolean = false

        override fun open(payload: ScanPayload): OpenOutcome {
            opened += payload
            return OpenOutcome.Started
        }
    }

    private class Clipboard : ClipboardWriter {
        val copies = mutableListOf<String>()

        override fun copy(text: String, sensitive: Boolean) {
            copies += text
        }

        override fun onAppStopped() = Unit

        override fun onWindowFocused() = Unit
    }

    private val actions = Actions()
    private val clipboard = Clipboard()

    // The permission is missing, so the Scan screen shows the rationale and never builds a camera preview.
    private val scanViewModel = ScanViewModel(
        cameraPermission = object : CameraPermission {
            override fun isGranted() = false
        },
        appSettings = object : AppSettingsOpener {
            override fun open() = Unit
        },
        imageReader = Image("https://example.com/path"),
    )

    private val settingsViewModel = SettingsViewModel(NoControl())

    private val resultViewModel = ResultViewModel(
        clipboard = clipboard,
        actions = actions,
        analyzeScan = AnalyzeScan(NoReputationLists()),
        regionHints = { null },
        payloadIds = PayloadIdGenerator { "id-1" },
        redirectTracer = { TraceOutcome.Failed(TraceFailure.NETWORK) },
        applyRedirectTrace = ApplyRedirectTrace(AnalyzeScan(NoReputationLists())),
        rdapPlanner = RdapPlanner { RdapPlan.Unavailable(RdapUnavailable.NO_BOOTSTRAP) },
        rdapClient = RdapClient { RdapOutcome.Failed(RdapFailure.NETWORK) },
        applyDomainAge = ApplyDomainAge(),
        networkStatus = { true },
        workDispatcher = Dispatchers.Unconfined,
    )

    private fun text(id: Int): String = RuntimeEnvironment.getApplication().getString(id)

    private fun show() {
        composeRule.setContent {
            LookFirstTheme {
                LookFirstNavHost(
                    scanViewModel = scanViewModel,
                    resultViewModel = resultViewModel,
                    settingsViewModel = settingsViewModel,
                    analyzerFactory = QrAnalyzerFactory { error("no camera in this test") },
                    showFirstLaunch = false,
                    onFirstLaunchDone = {},
                )
            }
        }
    }

    private fun scanOneImage() {
        composeRule.runOnUiThread { scanViewModel.onImagePicked(Uri.parse("content://test/image")) }
        composeRule.waitUntil(timeoutMillis = 5_000) { resultViewModel.state.value is ResultUiState.Content }
    }

    @Test
    fun aReadCodeLeadsFromScanToTheResultWithoutAnyAction() {
        show()
        composeRule.onNodeWithText(text(R.string.camera_rationale)).assertExists()

        scanOneImage()

        composeRule.onNodeWithText(text(R.string.action_cancel)).assertExists()
        assertFalse(composeRule.hasNodeWithText(R.string.camera_rationale))
        assertEquals(emptyList<ScanPayload>(), actions.opened)
        assertEquals(emptyList<String>(), clipboard.copies)
    }

    @Test
    fun cancelOnTheResultGoesBackToScanAndResumesTheCamera() {
        show()
        scanOneImage()

        composeRule.onNodeWithText(text(R.string.action_cancel)).performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.hasNodeWithText(R.string.camera_rationale)
        }
        assertEquals(ResultUiState.NoContent, resultViewModel.state.value)
        assertTrue(scanViewModel.state.value !is ScanUiState.Captured)
        assertEquals(emptyList<ScanPayload>(), actions.opened)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.hasNodeWithText(id: Int): Boolean =
        onAllNodes(hasText(text(id))).fetchSemanticsNodes().isNotEmpty()
}
