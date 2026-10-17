package com.lookfirst.data.network

import com.lookfirst.data.platform.SdkVersion
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.cert.Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory

private const val ANDROID_CA_STORE = "AndroidCAStore"
private const val SYSTEM_ALIAS_PREFIX = "system:"
private const val FIRST_API_WITH_NETWORK_SECURITY_CONFIG = 24

/**
 * The TLS socket factory every network client of the app shares. `null` means "the platform default": from API 24 the
 * Network Security Config (`<certificates src="system"/>` only) already limits trust to the system authorities, and it
 * is not bypassed. On API 23 there is no Network Security Config, so user-installed (or MDM) authorities would be
 * trusted; a factory restricted to the system authorities is returned instead. When that factory cannot be built the
 * call throws: the connection is refused and the platform default is NEVER used as a fallback (closed failure).
 */
fun interface SystemTrust {
    @Throws(IOException::class)
    fun socketFactory(): SSLSocketFactory?
}

/** Keeps only the `system:` certificate entries of the platform authority store; `user:` entries are dropped. */
internal object SystemAnchors {
    @Throws(IOException::class)
    fun onlySystem(source: KeyStore): KeyStore {
        try {
            val filtered = KeyStore.getInstance(KeyStore.getDefaultType())
            filtered.load(null, null)
            val systemEntries: List<Pair<String, Certificate>> = source.aliases().toList()
                .filter { it.startsWith(SYSTEM_ALIAS_PREFIX) }
                .mapNotNull { alias -> source.getCertificate(alias)?.let { alias to it } }
            if (systemEntries.isEmpty()) throw IOException("no system authority")
            systemEntries.forEach { (alias, certificate) -> filtered.setCertificateEntry(alias, certificate) }
            return filtered
        } catch (failure: GeneralSecurityException) {
            throw IOException("system authorities unavailable", failure)
        }
    }

    @Throws(IOException::class)
    fun contextFor(anchors: KeyStore): SSLContext {
        try {
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(onlySystem(anchors))
            val context = SSLContext.getInstance("TLS")
            context.init(null, factory.trustManagers, null)
            return context
        } catch (failure: GeneralSecurityException) {
            throw IOException("system trust unavailable", failure)
        }
    }
}

/** Selects by API level; [loadAnchors] loads the platform store (`AndroidCAStore`) and is a seam for tests. */
internal class SelectingSystemTrust(private val sdk: SdkVersion, private val loadAnchors: () -> KeyStore) :
    SystemTrust {
    constructor(sdk: SdkVersion) : this(sdk, ::loadAndroidCaStore)

    private val lock = Any()
    private var cached: SSLSocketFactory? = null

    @Throws(IOException::class)
    override fun socketFactory(): SSLSocketFactory? {
        if (sdk.current() >= FIRST_API_WITH_NETWORK_SECURITY_CONFIG) return null
        synchronized(lock) {
            cached?.let { return it }
            val context = try {
                SystemAnchors.contextFor(loadAnchors())
            } catch (failure: RuntimeException) {
                throw IOException("system trust unavailable", failure)
            }
            return ModernTlsSocketFactory(context.socketFactory).also { cached = it }
        }
    }
}

@Throws(IOException::class)
private fun loadAndroidCaStore(): KeyStore {
    try {
        return KeyStore.getInstance(ANDROID_CA_STORE).also { it.load(null, null) }
    } catch (failure: GeneralSecurityException) {
        throw IOException("system authorities unavailable", failure)
    } catch (failure: RuntimeException) {
        throw IOException("system authorities unavailable", failure)
    }
}
