package com.lookfirst.ui.screens.scan

import androidx.lifecycle.ViewModel
import com.lookfirst.data.platform.AppSettingsOpener
import com.lookfirst.data.platform.CameraPermission
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

sealed interface ScanUiState {
    /** The camera permission has not been asked yet: the rationale is shown before the system dialog. */
    data object PermissionRationale : ScanUiState

    /** Refused (possibly "don't ask again"): offer the Android settings and the image scan, never ask again. */
    data object PermissionDenied : ScanUiState

    /** The viewfinder is live and the analysis is running. */
    data class Scanning(val torchAvailable: Boolean = false, val torchOn: Boolean = false) : ScanUiState

    /** A code was read: the camera is paused. Only its length is known here (LF-11 shows the result). */
    data class Captured(val contentLength: Int) : ScanUiState
}

sealed interface ScanEvent {
    /** A code was read: light haptic tick, no sound, nothing opens. */
    data object CodeRead : ScanEvent
}

@HiltViewModel
class ScanViewModel @Inject constructor(
    private val cameraPermission: CameraPermission,
    private val appSettings: AppSettingsOpener,
) : ViewModel() {
    private val mutableState = MutableStateFlow<ScanUiState>(initialState())
    val state: StateFlow<ScanUiState> = mutableState.asStateFlow()

    private val eventChannel = Channel<ScanEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    private fun initialState(): ScanUiState =
        if (cameraPermission.isGranted()) ScanUiState.Scanning() else ScanUiState.PermissionRationale

    /** Called when the screen comes back to the foreground (e.g. from Android settings): re-reads the permission. */
    fun onResume() {
        val granted = cameraPermission.isGranted()
        mutableState.update { current ->
            when {
                granted && (current is ScanUiState.PermissionRationale || current is ScanUiState.PermissionDenied) ->
                    ScanUiState.Scanning()
                !granted && (current is ScanUiState.Scanning || current is ScanUiState.Captured) ->
                    ScanUiState.PermissionDenied
                else -> current
            }
        }
    }

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
     * the camera is paused and a later read can never replace it. The raw text is not kept here yet.
     */
    fun onCodeRead(raw: String) {
        var accepted = false
        mutableState.update { current ->
            accepted = current is ScanUiState.Scanning
            if (accepted) ScanUiState.Captured(raw.length) else current
        }
        if (accepted) eventChannel.trySend(ScanEvent.CodeRead)
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

    /** Cancel on the result: back to the viewfinder. Never an action. */
    fun onCancel() {
        mutableState.update { current ->
            if (current is ScanUiState.Captured) ScanUiState.Scanning() else current
        }
    }

    fun onOpenAndroidSettings() {
        appSettings.open()
    }
}
