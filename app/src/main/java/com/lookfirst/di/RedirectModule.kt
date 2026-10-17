package com.lookfirst.di

import com.lookfirst.data.network.RedirectTracer
import com.lookfirst.data.network.SocketRedirectTracer
import com.lookfirst.data.network.SystemHostResolver
import com.lookfirst.data.network.TlsHopTransport
import com.lookfirst.data.platform.AndroidNetworkStatus
import com.lookfirst.data.platform.NetworkStatus
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.Executors
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RedirectModule {
    /**
     * Daemon threads, created on demand: a resolver call that never returns can only strand its own thread, and a
     * trace the user abandoned never keeps the process alive.
     */
    @Provides
    @Singleton
    fun provideRedirectTracer(): RedirectTracer = SocketRedirectTracer(
        resolver = SystemHostResolver,
        transport = TlsHopTransport(),
        executor = Executors.newCachedThreadPool { task ->
            Thread(task, "redirect-trace").apply { isDaemon = true }
        },
    )

    @Provides
    fun provideNetworkStatus(impl: AndroidNetworkStatus): NetworkStatus = impl
}
