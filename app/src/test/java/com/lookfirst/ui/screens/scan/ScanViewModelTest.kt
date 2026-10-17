package com.lookfirst.ui.screens.scan

import app.cash.turbine.test
import com.lookfirst.data.platform.AppSettingsOpener
import com.lookfirst.data.platform.CameraPermission
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

    private val settings = FakeSettings()

    private fun viewModel(permission: FakePermission) = ScanViewModel(permission, settings)

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
    fun firstReadPausesTheCameraAndEmitsOneEvent() = runBlocking {
        val vm = viewModel(FakePermission(true))
        vm.events.test {
            vm.onCodeRead("https://example.com")
            assertEquals(ScanEvent.CodeRead, awaitItem())
            assertEquals(ScanUiState.Captured(contentLength = 19), vm.state.value)
            vm.onCodeRead("another code")
            expectNoEvents()
            assertEquals(ScanUiState.Captured(contentLength = 19), vm.state.value)
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
    fun cancelResumesScanning() {
        val vm = viewModel(FakePermission(true))
        vm.onCodeRead("abc")
        vm.onCancel()
        assertEquals(ScanUiState.Scanning(), vm.state.value)
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
