package com.lookfirst.ui.screens.scan

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lookfirst.data.platform.AppSettingsOpener
import com.lookfirst.data.platform.CameraPermission
import com.lookfirst.data.scan.image.ImageFailure
import com.lookfirst.data.scan.image.ImageQrReader
import com.lookfirst.data.scan.image.ImageReadResult
import com.lookfirst.domain.model.Untrusted
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ScanUiState {
    /** The camera permission has not been asked yet: the rationale is shown before the system dialog. */
    data object PermissionRationale : ScanUiState

    /** Refused (possibly "don't ask again"): offer the Android settings and the image scan, never ask again. */
    data object PermissionDenied : ScanUiState

    /** The viewfinder is live and the analysis is running. */
    data class Scanning(val torchAvailable: Boolean = false, val torchOn: Boolean = false) : ScanUiState

    /** A code was read: the camera is paused and the Result screen has the content. A later read is ignored. */
    data object Captured : ScanUiState

    /** No back camera, or it is used by another app: explained, with a retry. The image scan stays available. */
    data object CameraUnavailable : ScanUiState

    /** A picked image is being read off the UI thread; the camera is released. */
    data object ReadingImage : ScanUiState

    /** No content could be taken from the picked image (ux.md 5.4): choose another one or go back to the camera. */
    data class ImageFailed(val reason: ImageFailure) : ScanUiState
}

sealed interface ScanEvent {
    /**
     * A code was read (camera or image): light haptic tick, no sound, nothing opens. The content goes to the
     * Result screen. [multipleCodes]: the image held several codes, only the first is given (D14).
     */
    data class CodeRead(val content: Untrusted, val multipleCodes: Boolean = false) : ScanEvent
}

@HiltViewModel
class ScanViewModel @Inject constructor(
    private val cameraPermission: CameraPermission,
    private val appSettings: AppSettingsOpener,
    private val imageReader: ImageQrReader,
) : ViewModel() {
    private val mutableState = MutableStateFlow<ScanUiState>(initialState())
    val state: StateFlow<ScanUiState> = mutableState.asStateFlow()

    private val eventChannel = Channel<ScanEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    private var readingImage: Job? = null

    /** The permission screen the image scan was started from, to go back to it (null: the camera was live). */
    private var cameraBlockedState: ScanUiState? = null

    private fun initialState(): ScanUiState =
        if (cameraPermission.isGranted()) ScanUiState.Scanning() else ScanUiState.PermissionRationale

    /** Called when the screen comes back to the foreground (e.g. from Android settings): re-reads the permission. */
    fun onResume() {
        val granted = cameraPermission.isGranted()
        mutableState.update { current ->
            when {
                // An image scan or a Result screen is on top: the camera permission does not matter there.
                current.coversCamera() -> current
                granted && (current is ScanUiState.PermissionRationale || current is ScanUiState.PermissionDenied) ->
                    ScanUiState.Scanning()
                !granted && current !is ScanUiState.PermissionRationale && current !is ScanUiState.PermissionDenied ->
                    ScanUiState.PermissionDenied
                else -> current
            }
        }
    }

    private fun ScanUiState.coversCamera() =
        this is ScanUiState.ReadingImage || this is ScanUiState.ImageFailed || this is ScanUiState.Captured

    /** Result of the system permission dialog. */
    fun onPermissionResult(granted: Boolean) {
        mutableState.update { current ->
            if (current is ScanUiState.PermissionRationale || current is ScanUiState.PermissionDenied) {
                if (granted) ScanUiState.Scanning() else ScanUiState.PermissionDenied
            } else {
                current
            }
        }
    }

    /**
     * The analyzer read a code (called from the analysis thread). Only the first read while scanning counts:
     * the camera is paused and a later read can never replace it. The text is handed to the Result screen in the
     * event and is not kept here.
     */
    fun onCodeRead(raw: String) {
        var accepted = false
        mutableState.update { current ->
            accepted = current is ScanUiState.Scanning
            if (accepted) ScanUiState.Captured else current
        }
        if (accepted) eventChannel.trySend(ScanEvent.CodeRead(Untrusted(raw)))
    }

    fun onTorchAvailable(available: Boolean) {
        mutableState.update { current ->
            if (current is ScanUiState.Scanning) current.copy(torchAvailable = available) else current
        }
    }

    fun onTorchToggle() {
        mutableState.update { current ->
            if (current is ScanUiState.Scanning && current.torchAvailable) {
                current.copy(torchOn = !current.torchOn)
            } else {
                current
            }
        }
    }

    /** The camera could not be opened (absent or busy). */
    fun onCameraUnavailable() {
        mutableState.update { current ->
            if (current is ScanUiState.Scanning) ScanUiState.CameraUnavailable else current
        }
    }

    fun onCameraRetry() {
        mutableState.update { current ->
            if (current is ScanUiState.CameraUnavailable) ScanUiState.Scanning() else current
        }
    }

    /** The Result screen was closed (Cancel or back): back to the viewfinder. Never an action. */
    fun onResultClosed() {
        mutableState.update { current -> if (current is ScanUiState.Captured) restingState() else current }
    }

    /**
     * The user picked an image (null: the picker was dismissed). It is read off the UI thread; a success goes to
     * the Result screen exactly like a camera read, a failure to the image failure screen. Nothing else happens.
     */
    fun onImagePicked(uri: Uri?) {
        val current = mutableState.value
        val idle = current !is ScanUiState.ReadingImage && current !is ScanUiState.Captured
        if (uri == null || !idle || readingImage?.isActive == true) return
        if (current !is ScanUiState.ImageFailed) {
            cameraBlockedState = current.takeIf {
                it is ScanUiState.PermissionRationale || it is ScanUiState.PermissionDenied
            }
        }
        mutableState.value = ScanUiState.ReadingImage
        readingImage = viewModelScope.launch {
            when (val result = imageReader.read(uri)) {
                is ImageReadResult.Found -> {
                    mutableState.value = ScanUiState.Captured
                    eventChannel.trySend(ScanEvent.CodeRead(Untrusted(result.content), result.multiple))
                }
                is ImageReadResult.Failed -> mutableState.value = ScanUiState.ImageFailed(result.reason)
            }
        }
    }

    /** "Back to camera" (or system back) from the image screens; stops a read still running. */
    fun onBackToCamera() {
        readingImage?.cancel()
        mutableState.update { current ->
            if (current is ScanUiState.ReadingImage || current is ScanUiState.ImageFailed) restingState() else current
        }
    }

    /** Where the user was before the image scan: the viewfinder, or the permission screen when it is still off. */
    private fun restingState(): ScanUiState = when {
        cameraPermission.isGranted() -> ScanUiState.Scanning()
        else -> cameraBlockedState ?: ScanUiState.PermissionDenied
    }

    fun onOpenAndroidSettings() {
        appSettings.open()
    }
}
