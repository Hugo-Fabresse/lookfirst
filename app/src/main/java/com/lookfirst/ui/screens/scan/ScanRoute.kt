package com.lookfirst.ui.screens.scan

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lookfirst.domain.scan.BarcodeDecoder

/** Wires [ScanViewModel] to [ScanScreen]: permission dialog, one-shot haptic tick, camera slot. */
@Composable
fun ScanRoute(viewModel: ScanViewModel, decoder: BarcodeDecoder) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
        viewModel::onPermissionResult,
    )

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResume() }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                ScanEvent.CodeRead -> haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        }
    }

    ScanScreen(
        state = state,
        cameraContent = { modifier, scanning ->
            CameraPreview(
                decoder = decoder,
                torchOn = scanning.torchOn,
                onCodeRead = viewModel::onCodeRead,
                onTorchAvailable = viewModel::onTorchAvailable,
                modifier = modifier,
            )
        },
        onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
        onOpenAndroidSettings = viewModel::onOpenAndroidSettings,
        onTorchToggle = viewModel::onTorchToggle,
        onOpenSettings = {},
        onCancel = viewModel::onCancel,
    )
}
