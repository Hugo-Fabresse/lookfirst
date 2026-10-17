package com.lookfirst.ui.screens.scan

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.lookfirst.R
import com.lookfirst.ui.theme.LookFirstTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ScanScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var permissionRequests = 0
    private var settingsOpened = 0
    private var cancels = 0
    private var torchToggles = 0

    private fun text(id: Int, vararg args: Any): String = RuntimeEnvironment.getApplication().getString(id, *args)

    private fun show(state: ScanUiState) {
        composeRule.setContent {
            LookFirstTheme {
                ScanScreen(
                    state = state,
                    cameraContent = { _, _ -> Text("camera-slot") },
                    onRequestPermission = { permissionRequests++ },
                    onOpenAndroidSettings = { settingsOpened++ },
                    onTorchToggle = { torchToggles++ },
                    onOpenSettings = {},
                    onCancel = { cancels++ },
                )
            }
        }
    }

    @Test
    fun rationaleExplainsBeforeAskingAndNeverAsksByItself() {
        show(ScanUiState.PermissionRationale)
        composeRule.onNodeWithText(text(R.string.camera_rationale)).assertExists()
        assertEquals(0, permissionRequests)
        composeRule.onNodeWithText(text(R.string.allow_camera)).performClick()
        assertEquals(1, permissionRequests)
    }

    @Test
    fun deniedOffersAndroidSettingsAndKeepsImagePickerVisible() {
        show(ScanUiState.PermissionDenied)
        composeRule.onNodeWithText(text(R.string.camera_denied)).assertExists()
        composeRule.onNodeWithText(text(R.string.pick_image)).assertExists()
        composeRule.onNodeWithText(text(R.string.open_android_settings)).performClick()
        assertEquals(1, settingsOpened)
    }

    @Test
    fun scanningShowsViewfinderHintAndDisabledImagePicker() {
        show(ScanUiState.Scanning())
        composeRule.onNodeWithText("camera-slot").assertExists()
        composeRule.onNodeWithText(text(R.string.scan_hint)).assertExists()
        composeRule.onNodeWithText(text(R.string.pick_image)).assertIsNotEnabled()
    }

    @Test
    fun torchButtonOnlyWhenAFlashUnitExists() {
        show(ScanUiState.Scanning(torchAvailable = false))
        composeRule.onNodeWithContentDescription(text(R.string.a11y_torch)).assertDoesNotExist()
    }

    @Test
    fun torchButtonTogglesWhenAvailable() {
        show(ScanUiState.Scanning(torchAvailable = true))
        composeRule.onNodeWithContentDescription(text(R.string.a11y_torch)).assertIsEnabled().performClick()
        assertEquals(1, torchToggles)
    }

    @Test
    fun capturedShowsOnlyTheLengthAndCancelResumes() {
        show(ScanUiState.Captured(contentLength = 42))
        composeRule.onNodeWithText(text(R.string.scan_result_length, 42)).assertExists()
        composeRule.onNodeWithText("camera-slot").assertDoesNotExist()
        composeRule.onNodeWithText(text(R.string.action_cancel)).performClick()
        assertEquals(1, cancels)
    }
}
