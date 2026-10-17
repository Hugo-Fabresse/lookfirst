package com.lookfirst.di

import com.lookfirst.data.platform.AndroidAppSettingsOpener
import com.lookfirst.data.platform.AndroidCameraPermission
import com.lookfirst.data.platform.AppSettingsOpener
import com.lookfirst.data.platform.CameraPermission
import com.lookfirst.data.scan.camera.ZxingBarcodeDecoder
import com.lookfirst.domain.scan.BarcodeDecoder
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class PlatformModule {
    @Binds
    abstract fun bindCameraPermission(impl: AndroidCameraPermission): CameraPermission

    @Binds
    abstract fun bindAppSettingsOpener(impl: AndroidAppSettingsOpener): AppSettingsOpener

    @Binds
    abstract fun bindBarcodeDecoder(impl: ZxingBarcodeDecoder): BarcodeDecoder
}
