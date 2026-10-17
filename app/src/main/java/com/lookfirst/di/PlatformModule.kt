package com.lookfirst.di

import com.lookfirst.data.platform.AndroidAppSettingsOpener
import com.lookfirst.data.platform.AndroidCameraPermission
import com.lookfirst.data.platform.AndroidClipboardWriter
import com.lookfirst.data.platform.AppSettingsOpener
import com.lookfirst.data.platform.CameraPermission
import com.lookfirst.data.platform.ClipboardWriter
import com.lookfirst.data.scan.camera.DefaultQrAnalyzerFactory
import com.lookfirst.data.scan.camera.QrAnalyzerFactory
import com.lookfirst.data.scan.camera.ZxingBarcodeDecoder
import com.lookfirst.data.scan.image.BoundedImageQrReader
import com.lookfirst.data.scan.image.ImageQrReader
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
    abstract fun bindClipboardWriter(impl: AndroidClipboardWriter): ClipboardWriter

    @Binds
    abstract fun bindBarcodeDecoder(impl: ZxingBarcodeDecoder): BarcodeDecoder

    @Binds
    abstract fun bindQrAnalyzerFactory(impl: DefaultQrAnalyzerFactory): QrAnalyzerFactory

    @Binds
    abstract fun bindImageQrReader(impl: BoundedImageQrReader): ImageQrReader
}
