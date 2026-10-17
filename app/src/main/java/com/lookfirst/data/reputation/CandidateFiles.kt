package com.lookfirst.data.reputation

import java.io.File
import java.io.IOException

private const val SIGNATURE_MAX_BYTES = 256L

/** Fills a candidate directory from the places other than the network: the embedded bundle, the installed bundle. */
internal object CandidateFiles {
    /** Copies the files the installed bundle already has with the declared size and hash; returns the others. */
    fun reuseUnchanged(
        disk: BundleDisk,
        manifest: BundleManifest,
        installed: LoadedBundle?,
        candidate: File,
    ): List<ManifestFile> = manifest.files.filter { declared ->
        val kind = BundleFile.forPath(declared.path) ?: return@filter false
        val source = installed?.let { File(it.directory, declared.path) }
        val same = source != null &&
            source.isFile &&
            source.length() == declared.size &&
            BundleVerifier.sha256Hex(source) == declared.sha256
        !(same && source != null && copyFile(disk, source, File(candidate, kind.path)))
    }

    private fun copyFile(disk: BundleDisk, source: File, target: File): Boolean = try {
        source.inputStream().use { disk.copyBounded(it, target, source.length()) }
    } catch (@Suppress("SwallowedException") ignored: IOException) {
        false
    }

    fun copyEmbeddedManifest(disk: BundleDisk, source: EmbeddedBundleSource, into: File): Boolean {
        val pairs = listOf(
            BundleLimits.MANIFEST_FILE to BundleLimits.MANIFEST_MAX_BYTES,
            BundleLimits.SIGNATURE_FILE to SIGNATURE_MAX_BYTES,
        )
        return pairs.all { (name, limit) ->
            source.open(name)?.use { disk.copyBounded(it, File(into, name), limit) } ?: false
        }
    }

    fun copyEmbeddedFiles(
        disk: BundleDisk,
        source: EmbeddedBundleSource,
        into: File,
        manifest: BundleManifest,
    ): Boolean = manifest.files.all { declared ->
        val kind = BundleFile.forPath(declared.path)
        kind == null ||
            declared.size > kind.maxSize ||
            (
                source.open(declared.path)?.use { disk.copyBounded(it, File(into, declared.path), declared.size) }
                    ?: !kind.required
                )
    }
}
