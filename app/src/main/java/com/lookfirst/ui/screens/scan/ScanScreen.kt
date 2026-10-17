package com.lookfirst.ui.screens.scan

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lookfirst.R

/**
 * Scan home screen (ux.md 5.2, 5.3) and the image scan screens (5.4). [cameraContent] is the live viewfinder: a
 * slot, so that the screen stays testable without a camera.
 */
@Composable
fun ScanScreen(
    state: ScanUiState,
    cameraContent: @Composable (Modifier, ScanUiState.Scanning) -> Unit,
    onRequestPermission: () -> Unit,
    onOpenAndroidSettings: () -> Unit,
    onTorchToggle: () -> Unit,
    onOpenSettings: () -> Unit,
    onCameraRetry: () -> Unit,
    onPickImage: () -> Unit,
    onBackToCamera: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (state is ScanUiState.ImageFailed) {
            ImageFailureScreen(reason = state.reason, onPickAnother = onPickImage, onBackToCamera = onBackToCamera)
        } else {
            Column(modifier = Modifier.safeDrawingPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                TopBar(state = state, onTorchToggle = onTorchToggle, onOpenSettings = onOpenSettings)
                when (state) {
                    is ScanUiState.Scanning -> ScanningContent(state, cameraContent, onPickImage)
                    ScanUiState.PermissionRationale -> PermissionContent(
                        icon = R.drawable.ic_camera,
                        message = R.string.camera_rationale,
                        actionLabel = R.string.allow_camera,
                        onAction = onRequestPermission,
                        onPickImage = onPickImage,
                    )
                    ScanUiState.PermissionDenied -> PermissionContent(
                        icon = R.drawable.ic_camera_off,
                        message = R.string.camera_denied,
                        actionLabel = R.string.open_android_settings,
                        onAction = onOpenAndroidSettings,
                        onPickImage = onPickImage,
                    )
                    ScanUiState.CameraUnavailable -> PermissionContent(
                        icon = R.drawable.ic_camera_off,
                        message = R.string.camera_unavailable,
                        actionLabel = R.string.camera_retry,
                        onAction = onCameraRetry,
                        onPickImage = onPickImage,
                    )
                    ScanUiState.ReadingImage -> ReadingImageContent()
                    // The Result screen is on top: nothing to draw, the camera is released.
                    ScanUiState.Captured -> Unit
                    is ScanUiState.ImageFailed -> Unit
                }
            }
        }
    }
}

@Composable
private fun TopBar(state: ScanUiState, onTorchToggle: () -> Unit, onOpenSettings: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        if (state is ScanUiState.Scanning && state.torchAvailable) {
            val colors = MaterialTheme.colorScheme
            IconButton(onClick = onTorchToggle) {
                Icon(
                    painter = painterResource(R.drawable.ic_torch),
                    contentDescription = stringResource(R.string.a11y_torch),
                    tint = if (state.torchOn) colors.primary else colors.onBackground,
                )
            }
        }
        // TODO(settings screen): no-op until the settings screen exists.
        IconButton(onClick = onOpenSettings) {
            Icon(
                painter = painterResource(R.drawable.ic_settings),
                contentDescription = stringResource(R.string.a11y_settings),
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
    }
}

@Composable
private fun ScanningContent(
    state: ScanUiState.Scanning,
    cameraContent: @Composable (Modifier, ScanUiState.Scanning) -> Unit,
    onPickImage: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(16.dp))) {
            cameraContent(Modifier.fillMaxSize(), state)
        }
        Text(
            text = stringResource(R.string.scan_hint),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        PickImageButton(onPickImage)
    }
}

@Composable
private fun PermissionContent(
    @DrawableRes icon: Int,
    @StringRes message: Int,
    @StringRes actionLabel: Int,
    onAction: () -> Unit,
    onPickImage: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = stringResource(message),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onAction, modifier = Modifier.fillMaxWidth()) { Text(stringResource(actionLabel)) }
        PickImageButton(onPickImage)
    }
}

@Composable
private fun ReadingImageContent() {
    val reading = stringResource(R.string.reading_image)
    Column(
        modifier = Modifier.fillMaxSize().semantics(mergeDescendants = true) { contentDescription = reading },
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text(
            text = reading,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

/** Opens the system image picker (no storage permission); the image is read only once the user has picked it. */
@Composable
private fun PickImageButton(onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Icon(painter = painterResource(R.drawable.ic_image), contentDescription = null, modifier = Modifier.size(20.dp))
        Text(text = stringResource(R.string.pick_image), modifier = Modifier.padding(start = 8.dp))
    }
}
