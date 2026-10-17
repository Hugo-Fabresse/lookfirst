package com.lookfirst.ui.screens.scan

import android.net.Uri
import app.cash.turbine.test
import com.lookfirst.data.platform.AppSettingsOpener
import com.lookfirst.data.platform.CameraPermission
import com.lookfirst.data.scan.image.ImageFailure
import com.lookfirst.data.scan.image.ImageQrReader
import com.lookfirst.data.scan.image.ImageReadResult
import com.lookfirst.domain.model.Untrusted
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Robolectric only for the main looper behind `viewModelScope`. */
@RunWith(RobolectricTestRunner::class)
class ScanViewModelImageTest {
    private class FakePermission(var granted: Boolean) : CameraPermission {
        override fun isGranted() = granted
    }

    private object NoSettings : AppSettingsOpener {
        override fun open() = Unit
    }

    /** Answers with [result], once [gate] (if any) is completed. */
    private class FakeReader(var result: ImageReadResult, val gate: CompletableDeferred<Unit>? = null) : ImageQrReader {
        val reads = mutableListOf<Uri>()

        override suspend fun read(uri: Uri): ImageReadResult {
            reads += uri
            gate?.await()
            return result
        }
    }

    private val uri: Uri = Uri.parse("content://picker/1")

    private fun viewModel(permission: FakePermission, reader: FakeReader) =
        ScanViewModel(permission, NoSettings, reader)

    @Test
    fun aReadImageGoesToTheResultScreenWithTheMultipleFlag() = runBlocking {
        val vm = viewModel(FakePermission(true), FakeReader(ImageReadResult.Found("https://a.example/", true)))
        vm.events.test {
            vm.onImagePicked(uri)
            assertEquals(ScanEvent.CodeRead(Untrusted("https://a.example/"), multipleCodes = true), awaitItem())
        }
        assertEquals(ScanUiState.Captured, vm.state.value)
    }

    @Test
    fun aSingleCodeIsNotFlaggedAsMultiple() = runBlocking {
        val vm = viewModel(FakePermission(true), FakeReader(ImageReadResult.Found("tel:123", false)))
        vm.events.test {
            vm.onImagePicked(uri)
            assertEquals(false, (awaitItem() as ScanEvent.CodeRead).multipleCodes)
        }
    }

    @Test
    fun eachFailureLeadsToTheFailureState() {
        ImageFailure.entries.forEach { reason ->
            val vm = viewModel(FakePermission(true), FakeReader(ImageReadResult.Failed(reason)))
            vm.onImagePicked(uri)
            assertEquals(ScanUiState.ImageFailed(reason), vm.state.value)
        }
    }

    @Test
    fun aDismissedPickerChangesNothing() {
        val reader = FakeReader(ImageReadResult.Failed(ImageFailure.NO_QR))
        val vm = viewModel(FakePermission(true), reader)
        vm.onImagePicked(null)
        assertEquals(ScanUiState.Scanning(), vm.state.value)
        assertEquals(emptyList<Uri>(), reader.reads)
    }

    @Test
    fun theStateIsReadingWhileTheImageIsRead() {
        val gate = CompletableDeferred<Unit>()
        val reader = FakeReader(ImageReadResult.Failed(ImageFailure.NO_QR), gate)
        val vm = viewModel(FakePermission(true), reader)
        vm.onImagePicked(uri)
        assertEquals(ScanUiState.ReadingImage, vm.state.value)
        vm.onImagePicked(uri)
        assertEquals(1, reader.reads.size)
        gate.complete(Unit)
        assertEquals(ScanUiState.ImageFailed(ImageFailure.NO_QR), vm.state.value)
    }

    @Test
    fun backToCameraAfterAFailureResumesScanning() {
        val vm = viewModel(FakePermission(true), FakeReader(ImageReadResult.Failed(ImageFailure.NO_QR)))
        vm.onImagePicked(uri)
        vm.onBackToCamera()
        assertEquals(ScanUiState.Scanning(), vm.state.value)
    }

    @Test
    fun anotherImageCanBePickedFromTheFailureScreen() {
        val reader = FakeReader(ImageReadResult.Failed(ImageFailure.NO_QR))
        val vm = viewModel(FakePermission(true), reader)
        vm.onImagePicked(uri)
        reader.result = ImageReadResult.Failed(ImageFailure.CORRUPT)
        vm.onImagePicked(uri)
        assertEquals(ScanUiState.ImageFailed(ImageFailure.CORRUPT), vm.state.value)
        assertEquals(2, reader.reads.size)
    }

    @Test
    fun withoutCameraPermissionTheImageScanReturnsToThePermissionScreen() {
        val permission = FakePermission(false)
        val vm = viewModel(permission, FakeReader(ImageReadResult.Failed(ImageFailure.NO_QR)))
        vm.onPermissionResult(granted = false)
        vm.onImagePicked(uri)
        vm.onResume()
        assertEquals(ScanUiState.ImageFailed(ImageFailure.NO_QR), vm.state.value)
        vm.onBackToCamera()
        assertEquals(ScanUiState.PermissionDenied, vm.state.value)
    }

    @Test
    fun closingTheResultOfAnImageWithoutPermissionDoesNotStartTheCamera() = runBlocking {
        val vm = viewModel(FakePermission(false), FakeReader(ImageReadResult.Found("hello", false)))
        vm.onImagePicked(uri)
        vm.onResume()
        assertEquals(ScanUiState.Captured, vm.state.value)
        vm.onResultClosed()
        assertEquals(ScanUiState.PermissionRationale, vm.state.value)
    }

    @Test
    fun backWhileReadingCancelsTheReadAndResumes() {
        val gate = CompletableDeferred<Unit>()
        val vm = viewModel(FakePermission(true), FakeReader(ImageReadResult.Found("late", false), gate))
        vm.onImagePicked(uri)
        vm.onBackToCamera()
        gate.complete(Unit)
        assertEquals(ScanUiState.Scanning(), vm.state.value)
    }
}
