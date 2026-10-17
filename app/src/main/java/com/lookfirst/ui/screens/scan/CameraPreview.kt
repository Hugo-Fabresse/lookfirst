package com.lookfirst.ui.screens.scan

import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.lookfirst.R
import com.lookfirst.data.scan.camera.QrImageAnalyzer
import com.lookfirst.domain.scan.BarcodeDecoder
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.Executors

// Bounded analysis resolution: enough for a QR code at arm's length, cheap enough to decode every frame.
private const val ANALYSIS_WIDTH = 1280
private const val ANALYSIS_HEIGHT = 720

/**
 * Live viewfinder + QR analysis, bound to the lifecycle of the host: the camera runs only while this composable
 * is in the composition AND the screen is started, and is released (unbindAll, executor shut down) otherwise.
 * Frames are analysed on a dedicated single-thread executor with KEEP_ONLY_LATEST; none is stored.
 */
@Composable
fun CameraPreview(
    decoder: BarcodeDecoder,
    torchOn: Boolean,
    onCodeRead: (String) -> Unit,
    onTorchAvailable: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnCodeRead by rememberUpdatedState(onCodeRead)
    val currentOnTorchAvailable by rememberUpdatedState(onTorchAvailable)
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    var camera by remember { mutableStateOf<Camera?>(null) }

    DisposableEffect(lifecycleOwner, decoder) {
        val analysisExecutor = Executors.newSingleThreadExecutor()
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var released = false
        var provider: ProcessCameraProvider? = null
        providerFuture.addListener(
            {
                if (!released) {
                    provider = try {
                        providerFuture.get()
                    } catch (ignored: ExecutionException) {
                        null
                    }
                    provider?.let { p ->
                        camera = bindCamera(p, lifecycleOwner, previewView, analysisExecutor, decoder) {
                            currentOnCodeRead(it)
                        }
                        currentOnTorchAvailable(camera?.cameraInfo?.hasFlashUnit() == true)
                    }
                }
            },
            ContextCompat.getMainExecutor(context),
        )
        onDispose {
            released = true
            provider?.unbindAll()
            analysisExecutor.shutdown()
            camera = null
        }
    }

    LaunchedEffect(camera, torchOn) { camera?.cameraControl?.enableTorch(torchOn) }

    val description = stringResource(R.string.a11y_camera_preview)
    AndroidView(
        factory = { previewView },
        modifier = modifier.semantics { contentDescription = description },
    )
}

private fun bindCamera(
    provider: ProcessCameraProvider,
    lifecycleOwner: LifecycleOwner,
    previewView: PreviewView,
    analysisExecutor: Executor,
    decoder: BarcodeDecoder,
    onCodeRead: (String) -> Unit,
): Camera? {
    val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
    val analysis = ImageAnalysis.Builder()
        .setResolutionSelector(
            ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(ANALYSIS_WIDTH, ANALYSIS_HEIGHT),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                    ),
                )
                .build(),
        )
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .build()
        .also { it.setAnalyzer(analysisExecutor, QrImageAnalyzer(decoder, onCodeRead)) }
    return try {
        provider.unbindAll()
        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
    } catch (ignored: IllegalArgumentException) {
        // No back camera on this device: the viewfinder stays empty; the image scan remains available.
        null
    } catch (ignored: IllegalStateException) {
        // Camera in use by another app or cannot be opened.
        null
    }
}
