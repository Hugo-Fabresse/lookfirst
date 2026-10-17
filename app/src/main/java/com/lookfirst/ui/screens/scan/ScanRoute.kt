package com.lookfirst.ui.screens.scan

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lookfirst.data.scan.camera.QrAnalyzerFactory
import com.lookfirst.domain.model.Untrusted

/**
 * Wires [ScanViewModel] to [ScanScreen]: permission dialog, image picker, one-shot haptic tick, camera slot.
 * [onCodeRead] receives the scanned text once (and whether the image held several codes), to show it on the
 * Result screen.
 */
@Composable
fun ScanRoute(
    viewModel: ScanViewModel,
    analyzerFactory: QrAnalyzerFactory,
    onCodeRead: (Untrusted, multipleCodes: Boolean) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
        viewModel::onPermissionResult,
    )
    // Photo Picker, or the document picker on devices without it: images only, no storage permission.
    val imageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
        viewModel::onImagePicked,
    )

    BackHandler(enabled = state is ScanUiState.ReadingImage, onBack = viewModel::onBackToCamera)

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResume() }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ScanEvent.CodeRead -> {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onCodeRead(event.content, event.multipleCodes)
                }
            }
        }
    }

    ScanScreen(
        state = state,
        cameraContent = { modifier, scanning ->
            CameraPreview(
                analyzerFactory = analyzerFactory,
                torchOn = scanning.torchOn,
                onCodeRead = viewModel::onCodeRead,
                onTorchAvailable = viewModel::onTorchAvailable,
                onCameraUnavailable = viewModel::onCameraUnavailable,
                modifier = modifier,
            )
        },
        onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
        onOpenAndroidSettings = viewModel::onOpenAndroidSettings,
        onTorchToggle = viewModel::onTorchToggle,
        onOpenSettings = {},
        onCameraRetry = viewModel::onCameraRetry,
        onPickImage = {
            imageLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
        onBackToCamera = viewModel::onBackToCamera,
    )
}
