package com.lookfirst.data.reputation

import com.lookfirst.domain.host.PublicSuffixList
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile

private const val COPY_CHUNK = 64 * 1024
private const val POINTER_FIELDS = 3
private const val POINTER_MAX_BYTES = 256L
private const val SHA256_HEX_LENGTH = 64

/** Where the embedded bundle (APK assets) is read from. Files are looked up by their fixed bundle names. */
internal fun interface EmbeddedBundleSource {
    /** An open stream for [name], or null if the file is not embedded. The caller closes it. */
    fun open(name: String): InputStream?
}

/** A bundle opened for lookups. Index files are mapped, the Public Suffix List is parsed once. */
internal class LoadedBundle(
    val version: BundleVersion,
    val generatedAtMillis: Long,
    val directory: File,
    val manifestSha256: String,
    val phishing: MappedIndex,
    val allowlist: MappedIndex,
    val publicSuffixList: PublicSuffixList,
)

internal class Pointer(val version: BundleVersion, val manifestSha256: String)

/** File-system layout and atomic operations of the store: `bundles/<epoch>-<version>`, `tmp/`, `current`, `state`. */
internal class BundleDisk(val root: File) {
    val bundles = File(root, "bundles")
    val tmp = File(root, "tmp")
    private val pointerFile = File(root, "current")
    val stateFile = File(root, "state")

    fun ensureDirectories(): Boolean = (bundles.isDirectory || bundles.mkdirs()) && (tmp.isDirectory || tmp.mkdirs())

    fun directoryOf(version: BundleVersion) = File(bundles, version.directoryName)

    fun newCandidate(): File {
        ensureDirectories()
        val dir = File(tmp, "candidate-${System.nanoTime()}")
        if (!dir.mkdirs()) throw IOException("cannot create candidate directory")
        return dir
    }

    fun isInsideTmp(directory: File): Boolean = directory.canonicalFile.parentFile == tmp.canonicalFile

    fun readPointer(): Pointer? = try {
        if (pointerFile.isFile && pointerFile.length() <= POINTER_MAX_BYTES) {
            parsePointer(pointerFile.readText(Charsets.US_ASCII).trim().split(' '))
        } else {
            null
        }
    } catch (@Suppress("SwallowedException") ignored: IOException) {
        null
    }

    private fun parsePointer(parts: List<String>): Pointer? {
        if (parts.size != POINTER_FIELDS) return null
        val epoch = parts[0].toIntOrNull()
        val bundle = parts[1].toLongOrNull()
        if (epoch == null || bundle == null || !Hex.isLowerHex(parts[2], SHA256_HEX_LENGTH)) return null
        return Pointer(BundleVersion(epoch, bundle), parts[2])
    }

    fun clearPointer() {
        pointerFile.delete()
    }

    /** Writes the pointer under a temporary name, syncs, then renames over the old one. */
    fun swapPointer(pointer: Pointer, onTemporaryWritten: () -> Unit) {
        val text = "${pointer.version.epoch} ${pointer.version.bundle} ${pointer.manifestSha256}\n"
        writeAtomically(pointerFile, text, onTemporaryWritten)
    }

    fun writeState(state: StoreState) = writeAtomically(stateFile, state.serialize()) {}

    private fun writeAtomically(target: File, text: String, onTemporaryWritten: () -> Unit) {
        val temporary = File(target.parentFile, "${target.name}.tmp")
        FileOutputStream(temporary).use { out ->
            out.write(text.toByteArray(Charsets.US_ASCII))
            out.fd.sync()
        }
        onTemporaryWritten()
        if (!temporary.renameTo(target)) throw IOException("cannot replace ${target.name}")
    }

    /** Forces the content of every file of [directory] to storage before the directory is renamed into place. */
    fun syncFiles(directory: File) {
        directory.listFiles().orEmpty().filter { it.isFile }.forEach { file ->
            RandomAccessFile(file, "rw").use { it.fd.sync() }
        }
    }

    /** Removes everything in the candidate that the manifest does not list (and any sub-directory). */
    fun pruneUnlisted(directory: File, manifest: BundleManifest) {
        val keep = setOf(BundleLimits.MANIFEST_FILE, BundleLimits.SIGNATURE_FILE) +
            manifest.files.filter { BundleFile.forPath(it.path) != null }.map { it.path }
        directory.listFiles().orEmpty().filter { it.name !in keep || !it.isFile }.forEach { it.deleteRecursively() }
    }

    /** Deletes bundle directories other than [keep] and every leftover temporary entry. */
    fun cleanUp(keep: String?) {
        bundles.listFiles().orEmpty().filter { it.name != keep }.forEach { it.deleteRecursively() }
        tmp.listFiles().orEmpty().forEach { it.deleteRecursively() }
    }

    /** Copies at most [limit] bytes of [input] to [target]; false if the stream is longer. */
    fun copyBounded(input: InputStream, target: File, limit: Long): Boolean {
        var total = 0L
        FileOutputStream(target).use { out ->
            val buffer = ByteArray(COPY_CHUNK)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                if (total > limit) return false
                out.write(buffer, 0, n)
            }
        }
        return true
    }
}
