package com.lookfirst.ui.screens.scan

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.lookfirst.R
import com.lookfirst.data.scan.image.ImageFailure
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
    private var retries = 0
    private var torchToggles = 0
    private var picks = 0
    private var backs = 0

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
                    onCameraRetry = { retries++ },
                    onPickImage = { picks++ },
                    onBackToCamera = { backs++ },
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
    fun scanningShowsViewfinderHintAndAnActiveImagePicker() {
        show(ScanUiState.Scanning())
        composeRule.onNodeWithText("camera-slot").assertExists()
        composeRule.onNodeWithText(text(R.string.scan_hint)).assertExists()
        composeRule.onNodeWithText(text(R.string.pick_image)).assertIsEnabled().performClick()
        assertEquals(1, picks)
    }

    @Test
    fun imagePickerStaysAvailableWhenTheCameraIsOffOrMissing() {
        show(ScanUiState.PermissionDenied)
        composeRule.onNodeWithText(text(R.string.pick_image)).performClick()
        assertEquals(1, picks)
    }

    @Test
    fun readingAnImageShowsProgressAndNoCamera() {
        show(ScanUiState.ReadingImage)
        composeRule.onNodeWithText(text(R.string.reading_image)).assertExists()
        composeRule.onNodeWithText("camera-slot").assertDoesNotExist()
    }

    @Test
    fun noQrFailureOffersAnotherImageAndTheCamera() {
        show(ScanUiState.ImageFailed(ImageFailure.NO_QR))
        composeRule.onNodeWithText(text(R.string.err_no_qr)).assertExists()
        composeRule.onNodeWithText(text(R.string.pick_other_image)).performClick()
        composeRule.onNodeWithText(text(R.string.back_to_camera)).performClick()
        assertEquals(1, picks)
        assertEquals(1, backs)
    }

    @Test
    fun eachFailureHasItsOwnExplanation() {
        val messages = ImageFailure.entries.map { text(it.message()) }
        assertEquals(ImageFailure.entries.size, messages.toSet().size)
        show(ScanUiState.ImageFailed(ImageFailure.UNREADABLE))
        composeRule.onNodeWithText(text(R.string.err_unreadable)).assertExists()
    }

    @Test
    fun backArrowOfTheFailureScreenGoesBackToTheCamera() {
        show(ScanUiState.ImageFailed(ImageFailure.CORRUPT))
        composeRule.onNodeWithText(text(R.string.err_image_corrupt)).assertExists()
        composeRule.onAllNodesWithContentDescription(text(R.string.back_to_camera))[0].performClick()
        assertEquals(1, backs)
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
    fun capturedDrawsNothingBecauseTheResultScreenIsOnTop() {
        show(ScanUiState.Captured)
        composeRule.onNodeWithText("camera-slot").assertDoesNotExist()
        composeRule.onNodeWithText(text(R.string.scan_hint)).assertDoesNotExist()
    }

    @Test
    fun missingOrBusyCameraIsExplainedAndCanBeRetried() {
        show(ScanUiState.CameraUnavailable)
        composeRule.onNodeWithText(text(R.string.camera_unavailable)).assertExists()
        composeRule.onNodeWithText(text(R.string.pick_image)).assertExists()
        composeRule.onNodeWithText(text(R.string.camera_retry)).performClick()
        assertEquals(1, retries)
    }
}
