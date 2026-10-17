package com.lookfirst.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import javax.inject.Qualifier
import javax.inject.Singleton

/** CPU-bound work off the UI thread (parsing a scanned text). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

/** One dedicated thread for reading a picked image (a stuck read can then never starve anything else). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ImageReadDispatcher

@Module
@InstallIn(SingletonComponent::class)
object DispatchersModule {
    @Provides
    @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    @Singleton
    @ImageReadDispatcher
    fun provideImageReadDispatcher(): CoroutineDispatcher = Executors
        .newSingleThreadExecutor { task -> Thread(task, "image-qr-reader").apply { isDaemon = true } }
        .asCoroutineDispatcher()
}
