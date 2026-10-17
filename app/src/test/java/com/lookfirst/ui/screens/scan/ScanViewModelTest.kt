package com.lookfirst.ui.screens.scan

import app.cash.turbine.test
import com.lookfirst.data.platform.AppSettingsOpener
import com.lookfirst.data.platform.CameraPermission
import com.lookfirst.data.scan.image.ImageQrReader
import com.lookfirst.data.scan.image.ImageReadResult
import com.lookfirst.domain.model.Untrusted
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ScanViewModelTest {
    private class FakePermission(var granted: Boolean) : CameraPermission {
        override fun isGranted() = granted
    }

    private class FakeSettings : AppSettingsOpener {
        var opened = 0

        override fun open() {
            opened++
        }
    }

    private object ImageNeverUsed : ImageQrReader {
        override suspend fun read(uri: android.net.Uri): ImageReadResult = error("no image in these tests")
    }

    private val settings = FakeSettings()

    private fun viewModel(permission: FakePermission) = ScanViewModel(permission, settings, ImageNeverUsed)

    @Test
    fun startsWithRationaleWhenPermissionIsMissing() {
        assertEquals(ScanUiState.PermissionRationale, viewModel(FakePermission(false)).state.value)
    }

    @Test
    fun startsScanningWhenPermissionIsGranted() {
        assertEquals(ScanUiState.Scanning(), viewModel(FakePermission(true)).state.value)
    }

    @Test
    fun refusedPermissionLeadsToDeniedState() {
        val vm = viewModel(FakePermission(false))
        vm.onPermissionResult(granted = false)
        assertEquals(ScanUiState.PermissionDenied, vm.state.value)
    }

    @Test
    fun grantedPermissionLeadsToScanning() {
        val vm = viewModel(FakePermission(false))
        vm.onPermissionResult(granted = true)
        assertEquals(ScanUiState.Scanning(), vm.state.value)
    }

    @Test
    fun comingBackFromSettingsWithPermissionResumesScanning() {
        val permission = FakePermission(false)
        val vm = viewModel(permission)
        vm.onPermissionResult(granted = false)
        permission.granted = true
        vm.onResume()
        assertEquals(ScanUiState.Scanning(), vm.state.value)
    }

    @Test
    fun revokedPermissionWhileScanningLeadsToDenied() {
        val permission = FakePermission(true)
        val vm = viewModel(permission)
        permission.granted = false
        vm.onResume()
        assertEquals(ScanUiState.PermissionDenied, vm.state.value)
    }

    @Test
    fun firstReadPausesTheCameraAndHandsTheContentOverOnce() = runBlocking {
        val vm = viewModel(FakePermission(true))
        vm.events.test {
            vm.onCodeRead("https://example.com")
            val event = awaitItem() as ScanEvent.CodeRead
            assertEquals(Untrusted("https://example.com"), event.content)
            assertEquals(ScanUiState.Captured, vm.state.value)
            vm.onCodeRead("another code")
            expectNoEvents()
            assertEquals(ScanUiState.Captured, vm.state.value)
        }
    }

    @Test
    fun aReadWhileNotScanningIsIgnored() = runBlocking {
        val vm = viewModel(FakePermission(false))
        vm.events.test {
            vm.onCodeRead("x")
            expectNoEvents()
        }
        assertEquals(ScanUiState.PermissionRationale, vm.state.value)
    }

    @Test
    fun closingTheResultResumesScanning() {
        val vm = viewModel(FakePermission(true))
        vm.onCodeRead("abc")
        vm.onResultClosed()
        assertEquals(ScanUiState.Scanning(), vm.state.value)
    }

    @Test
    fun closingTheResultWhileScanningChangesNothing() {
        val vm = viewModel(FakePermission(true))
        vm.onTorchAvailable(true)
        vm.onResultClosed()
        assertEquals(ScanUiState.Scanning(torchAvailable = true), vm.state.value)
    }

    @Test
    fun aCameraThatCannotOpenLeadsToAnErrorStateWithRetry() {
        val vm = viewModel(FakePermission(true))
        vm.onCameraUnavailable()
        assertEquals(ScanUiState.CameraUnavailable, vm.state.value)
        vm.onCameraRetry()
        assertEquals(ScanUiState.Scanning(), vm.state.value)
    }

    @Test
    fun cameraErrorIsIgnoredOutsideScanning() {
        val vm = viewModel(FakePermission(true))
        vm.onCodeRead("abc")
        vm.onCameraUnavailable()
        assertEquals(ScanUiState.Captured, vm.state.value)
    }

    @Test
    fun torchToggleNeedsAFlashUnit() {
        val vm = viewModel(FakePermission(true))
        vm.onTorchToggle()
        assertEquals(ScanUiState.Scanning(torchAvailable = false, torchOn = false), vm.state.value)
        vm.onTorchAvailable(true)
        vm.onTorchToggle()
        assertEquals(ScanUiState.Scanning(torchAvailable = true, torchOn = true), vm.state.value)
    }

    @Test
    fun openAndroidSettingsDelegatesToThePlatform() {
        viewModel(FakePermission(false)).onOpenAndroidSettings()
        assertEquals(1, settings.opened)
    }
}
