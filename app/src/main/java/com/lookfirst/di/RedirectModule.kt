package com.lookfirst.di

import com.lookfirst.data.network.RedirectTracer
import com.lookfirst.data.network.SocketRdapClient
import com.lookfirst.data.network.SocketRedirectTracer
import com.lookfirst.data.network.SystemHostResolver
import com.lookfirst.data.network.TlsHopTransport
import com.lookfirst.data.platform.AndroidNetworkStatus
import com.lookfirst.data.platform.NetworkStatus
import com.lookfirst.domain.rdap.RdapClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.Executor
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * At most two optional requests (redirect trace or domain age, or abandoned blocked DNS calls) at a time; a third is
 * refused and reported as a network failure.
 */
private const val MAX_NETWORK_THREADS = 2
private const val IDLE_SECONDS = 30L

/** The small pool that runs the blocking work of the optional network actions (redirect trace, RDAP). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class NetworkExecutor

@Module
@InstallIn(SingletonComponent::class)
object RedirectModule {
    /**
     * Daemon threads, created on demand: a resolver call that never returns can only strand its own thread, and a
     * request the user abandoned never keeps the process alive.
     */
    @Provides
    @Singleton
    @NetworkExecutor
    fun provideNetworkExecutor(): Executor = ThreadPoolExecutor(
        0,
        MAX_NETWORK_THREADS,
        IDLE_SECONDS,
        TimeUnit.SECONDS,
        SynchronousQueue(),
    ) { task -> Thread(task, "optional-network").apply { isDaemon = true } }

    @Provides
    @Singleton
    fun provideRedirectTracer(@NetworkExecutor executor: Executor): RedirectTracer = SocketRedirectTracer(
        resolver = SystemHostResolver,
        transport = TlsHopTransport(),
        executor = executor,
    )

    @Provides
    @Singleton
    fun provideRdapClient(@NetworkExecutor executor: Executor): RdapClient = SocketRdapClient(
        resolver = SystemHostResolver,
        transport = TlsHopTransport(),
        executor = executor,
    )

    @Provides
    fun provideNetworkStatus(impl: AndroidNetworkStatus): NetworkStatus = impl
}
