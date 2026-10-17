package com.lookfirst.data.reputation

import android.content.res.AssetManager
import java.io.IOException
import java.io.InputStream

/**
 * The initial bundle shipped in the APK under `assets/reputation/` (ADR-0005): the same files and the same signature
 * as a download, verified with the keys pinned in the APK. No bundle is embedded yet (none is signed with a real
 * key): every `open` returns null and the store simply has no embedded bundle.
 */
internal class AssetEmbeddedBundle(private val assets: AssetManager) : EmbeddedBundleSource {
    override fun open(name: String): InputStream? = try {
        assets.open("$DIRECTORY/$name")
    } catch (@Suppress("SwallowedException") ignored: IOException) {
        null
    }

    private companion object {
        const val DIRECTORY = "reputation"
    }
}
