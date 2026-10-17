package com.lookfirst.data.reputation

import com.lookfirst.domain.host.PublicSuffixList
import java.io.File
import java.io.IOException

/** Points of [ReputationStore.install] where a test can simulate a crash. */
internal enum class InstallStage { FILES_SYNCED, DIRECTORY_RENAMED, POINTER_WRITTEN, POINTER_SWAPPED }

internal sealed interface InstallResult {
    class Installed(val version: BundleVersion) : InstallResult

    /** The candidate is the installed bundle: nothing to do. */
    data object UpToDate : InstallResult

    class Rejected(val reason: RejectReason) : InstallResult
}

/**
 * Verified, atomically installed reputation bundles in app-private storage (design document, sections 4 and 6).
 *
 * - [install] takes a directory created by [newCandidateDirectory] (the downloader of LF-24 fills it), verifies it
 *   and swaps it in; any failure leaves the installed bundle untouched.
 * - [current] returns the bundle lookups use, or null (then every lookup says "lists missing").
 * - The embedded bundle (APK assets, none yet) is verified with the keys pinned in the APK and used when it is
 *   newer than the installed one or when the installed one cannot be loaded.
 *
 * Nothing here logs: file names, hashes and reasons are enough for the caller, and no scanned content passes here.
 */
class ReputationStore internal constructor(
    root: File,
    private val embedded: EmbeddedBundleSource?,
    private val clock: () -> Long,
    private val pinnedRing: KeyRing = PinnedKeys.epochZeroRing(),
    private val recoveryKey: ByteArray = PinnedKeys.recoveryKeyR,
    directorySync: DirectorySync,
    private val acceptTestKeys: Boolean = false,
    private val onStage: (InstallStage) -> Unit = {},
) {
    private val disk = BundleDisk(root, directorySync)

    /**
     * Fail closed: while the pinned keys are the test keys (anyone can sign with them) a non-debug build trusts no
     * bundle at all, installed, embedded or downloaded; lookups then say "lists missing".
     */
    private val keysTrusted = PinnedKeys.KEYS_ARE_PRODUCTION || acceptTestKeys
    private val lock = Any()

    @Volatile private var loaded: LoadedBundle? = null

    @Volatile private var loadAttempted = false

    @Volatile private var seenGeneratedAtMillis = 0L

    /** The bundle to look hosts up in, loading it on first use. Null when nothing usable is installed. */
    internal fun current(): LoadedBundle? {
        if (!keysTrusted) return null
        loaded?.let { return it }
        return synchronized(lock) { ensureLoaded() }
    }

    private fun ensureLoaded(): LoadedBundle? {
        if (!keysTrusted) return null
        if (!loadAttempted) {
            loadAttempted = true
            loaded = loadInstalledOrEmbedded()
        }
        return loaded
    }

    /**
     * Whole days between `generatedAt` and the reference clock: `max(device clock, every validated generatedAt the
     * app has seen)`. A clock set back cannot make lists younger than that; a `generatedAt` up to 48 h ahead is 0.
     */
    internal fun ageDays(bundle: LoadedBundle): Int {
        val reference = maxOf(clock(), seenGeneratedAtMillis, bundle.generatedAtMillis)
        return ((reference - bundle.generatedAtMillis) / UtcTime.DAY).toInt()
    }

    /**
     * A fresh empty directory (inside the store, so the final rename stays on one file system). The first load,
     * which clears leftovers of a crashed update, happens before the directory exists.
     */
    internal fun newCandidateDirectory(): File = synchronized(lock) {
        ensureLoaded()
        disk.newCandidate()
    }

    /** True if a bundle with this manifest hash was rejected before (do not download its files again). */
    internal fun isKnownRejected(manifestSha256: String): Boolean =
        synchronized(lock) { StoreState.read(disk.stateFile).rejectedManifestSha == manifestSha256 }

    internal fun lastFailure(): Pair<RejectReason, Long>? = synchronized(lock) {
        val state = StoreState.read(disk.stateFile)
        val reason = state.lastFailure
        val time = state.lastFailureMillis
        if (reason != null && time != null) reason to time else null
    }

    /** Verifies [candidate] and, if every check passes, installs it atomically. The candidate is consumed. */
    internal fun install(candidate: File): InstallResult = synchronized(lock) {
        val result = try {
            if (keysTrusted) installLocked(candidate) else InstallResult.Rejected(RejectReason.BAD_SIGNATURE)
        } catch (@Suppress("SwallowedException") ignored: IOException) {
            InstallResult.Rejected(RejectReason.STORAGE)
        }
        if (result !is InstallResult.Installed && disk.isInsideTmp(candidate)) candidate.deleteRecursively()
        if (result is InstallResult.Rejected &&
            result.reason == RejectReason.STORAGE
        ) {
            recordFailure(RejectReason.STORAGE, null)
        }
        result
    }

    /** Adopts a keyset signed by R that opens a newer key epoch. False (nothing changes) on any deviation. */
    internal fun applyKeyset(json: ByteArray, signature: ByteArray): Boolean = synchronized(lock) {
        val state = StoreState.read(disk.stateFile)
        val keyset = KeysetVerifier.verify(json, signature, recoveryKey, state.keysetEpoch) ?: return false
        try {
            disk.ensureDirectories()
            disk.writeState(state.copy(keysetEpoch = keyset.epoch, keysetKeys = keyset.keys))
            true
        } catch (@Suppress("SwallowedException") ignored: IOException) {
            false
        }
    }

    private fun installLocked(candidate: File): InstallResult {
        if (!disk.ensureDirectories() ||
            !disk.isInsideTmp(candidate)
        ) {
            return InstallResult.Rejected(RejectReason.STORAGE)
        }
        val state = StoreState.read(disk.stateFile)
        val installed = ensureLoaded()
        val floor = listOfNotNull(installed?.version, state.highest).maxOrNull()
        val baseline = installed?.let {
            Baseline(it.version, it.generatedAtMillis, it.directory, it.phishing.entryCount, it.allowlist.entryCount)
        }
        return when (val outcome = BundleVerifier.verify(candidate, ringFor(state), clock(), floor, baseline)) {
            is VerifyOutcome.Rejected -> {
                recordFailure(outcome.reason, outcome.manifestSha256)
                InstallResult.Rejected(outcome.reason)
            }
            VerifyOutcome.UpToDate -> {
                disk.writeState(state.copy(lastSuccessMillis = clock()))
                InstallResult.UpToDate
            }
            is VerifyOutcome.Accepted -> commit(candidate, outcome.manifest, outcome.manifestSha256)
        }
    }

    /** Steps 8 and 9: sync, rename the directory, swap the pointer, persist the floor, clean up. */
    private fun commit(candidate: File, manifest: BundleManifest, manifestSha: String): InstallResult {
        disk.pruneUnlisted(candidate, manifest)
        disk.syncFiles(candidate)
        onStage(InstallStage.FILES_SYNCED)
        val target = disk.directoryOf(manifest.version)
        target.deleteRecursively()
        if (!candidate.renameTo(target)) return InstallResult.Rejected(RejectReason.STORAGE)
        disk.syncBundlesDirectory()
        onStage(InstallStage.DIRECTORY_RENAMED)
        disk.swapPointer(Pointer(manifest.version, manifestSha)) { onStage(InstallStage.POINTER_WRITTEN) }
        onStage(InstallStage.POINTER_SWAPPED)
        val state = StoreState.read(disk.stateFile)
        disk.writeState(
            state.copy(
                highest = listOfNotNull(state.highest, manifest.version).max(),
                maxGeneratedAtMillis = maxOf(state.maxGeneratedAtMillis, manifest.generatedAtMillis),
                lastSuccessMillis = clock(),
                lastFailure = null,
                lastFailureMillis = null,
                rejectedManifestSha = null,
            ),
        )
        seenGeneratedAtMillis = maxOf(seenGeneratedAtMillis, manifest.generatedAtMillis)
        disk.cleanUp(manifest.version.directoryName)
        loaded = open(target, manifest.version, manifestSha, manifest)
        loadAttempted = true
        return InstallResult.Installed(manifest.version)
    }

    private fun recordFailure(reason: RejectReason, manifestSha: String?) {
        try {
            disk.ensureDirectories()
            val state = StoreState.read(disk.stateFile)
            disk.writeState(
                state.copy(
                    lastFailure = reason,
                    lastFailureMillis = clock(),
                    rejectedManifestSha = manifestSha ?: state.rejectedManifestSha,
                ),
            )
        } catch (@Suppress("SwallowedException") ignored: IOException) {
            // Storage is failing: the previous bundle stays as it is and nothing more can be remembered.
        }
    }

    private fun ringFor(state: StoreState): KeyRing =
        if (state.keysetEpoch > pinnedRing.epoch && state.keysetKeys.isNotEmpty()) {
            KeyRing(
                state.keysetEpoch,
                state.keysetKeys + pinnedRing.manifestKeys.filter { it.contentEquals(recoveryKey) },
            )
        } else {
            pinnedRing
        }

    private fun loadInstalledOrEmbedded(): LoadedBundle? {
        disk.ensureDirectories()
        val installed = loadInstalled()
        val bundle = adoptEmbeddedIfNewer(installed) ?: installed
        bundle?.let { seenGeneratedAtMillis = maxOf(seenGeneratedAtMillis, it.generatedAtMillis) }
        disk.cleanUp(bundle?.version?.directoryName)
        return bundle
    }

    /** Reads the pointer, re-verifies the signature and sizes, maps the indices. Any failure discards the install. */
    private fun loadInstalled(): LoadedBundle? {
        val state = StoreState.read(disk.stateFile)
        seenGeneratedAtMillis = maxOf(seenGeneratedAtMillis, state.maxGeneratedAtMillis)
        val pointer = disk.readPointer() ?: return null
        val directory = disk.directoryOf(pointer.version)
        val ring = if (pointer.version.epoch == pinnedRing.epoch) pinnedRing else ringFor(state)
        val signed = if (pointer.version.epoch == ring.epoch) BundleVerifier.readSigned(directory, ring) else null
        val bundle = (signed as? SignedManifest.Ok)
            ?.takeIf { it.sha256 == pointer.manifestSha256 && it.manifest.version == pointer.version }
            ?.let { open(directory, it.manifest.version, it.sha256, it.manifest) }
        if (bundle == null) {
            directory.deleteRecursively()
            disk.clearPointer()
            recordFailure(RejectReason.BAD_FORMAT, null)
        }
        return bundle
    }

    /** Bounds only: sizes against the manifest, index headers against the manifest's PSL hash. */
    @Suppress("ComplexCondition")
    private fun open(directory: File, version: BundleVersion, sha: String, manifest: BundleManifest): LoadedBundle? {
        val psl = manifest.fileFor(BundleFile.PUBLIC_SUFFIX_LIST) ?: return null
        val sizesOk = BundleFile.entries.all { kind ->
            val declared = manifest.fileFor(kind)
            if (declared == null) !kind.required else File(directory, kind.path).length() == declared.size
        }
        val phishing = MappedIndex.open(File(directory, BundleFile.PHISHING_INDEX.path))
        val allowlist = MappedIndex.open(File(directory, BundleFile.ALLOWLIST.path))
        val lines = BundleVerifier.readPslLines(File(directory, BundleFile.PUBLIC_SUFFIX_LIST.path))
        val consistent = sizesOk &&
            phishing != null &&
            allowlist != null &&
            lines != null &&
            phishing.header.sourceId == SourceIds.PHISHING_DATABASE &&
            allowlist.header.sourceId == SourceIds.ALLOWLIST &&
            phishing.header.pslSha256 == psl.sha256 &&
            allowlist.header.pslSha256 == psl.sha256
        if (!consistent || phishing == null || allowlist == null || lines == null) return null
        return LoadedBundle(
            version,
            manifest.generatedAtMillis,
            directory,
            sha,
            phishing,
            allowlist,
            PublicSuffixList.parse(lines),
        )
    }

    /** The embedded bundle replaces the installed one only if it verifies with the APK keys and is newer. */
    @Suppress("ReturnCount") // every step that fails simply means "no embedded bundle"
    private fun adoptEmbeddedIfNewer(installed: LoadedBundle?): LoadedBundle? {
        val source = embedded ?: return null
        val candidate = disk.newCandidate()
        try {
            if (!copyEmbeddedManifest(source, candidate)) return null
            val signed = BundleVerifier.readSigned(candidate, pinnedRing) as? SignedManifest.Ok ?: return null
            if (installed != null && signed.manifest.version <= installed.version) return null
            if (!copyEmbeddedFiles(source, candidate, signed.manifest)) return null
            // No floor and no baseline: the embedded bundle is the floor itself (ADR-0005).
            val outcome = BundleVerifier.verify(candidate, pinnedRing, clock(), null, null)
            if (outcome is VerifyOutcome.Accepted &&
                commit(candidate, outcome.manifest, outcome.manifestSha256) is InstallResult.Installed
            ) {
                return loaded
            }
            recordFailure((outcome as? VerifyOutcome.Rejected)?.reason ?: RejectReason.STORAGE, null)
            return null
        } catch (@Suppress("SwallowedException") ignored: IOException) {
            return null
        } finally {
            candidate.deleteRecursively()
        }
    }

    private fun copyEmbeddedManifest(source: EmbeddedBundleSource, into: File): Boolean {
        val pairs = listOf(
            BundleLimits.MANIFEST_FILE to BundleLimits.MANIFEST_MAX_BYTES,
            BundleLimits.SIGNATURE_FILE to SIGNATURE_MAX_BYTES,
        )
        return pairs.all { (name, limit) ->
            source.open(name)?.use { disk.copyBounded(it, File(into, name), limit) } ?: false
        }
    }

    private fun copyEmbeddedFiles(source: EmbeddedBundleSource, into: File, manifest: BundleManifest): Boolean =
        manifest.files.all { declared ->
            val kind = BundleFile.forPath(declared.path)
            kind == null ||
                declared.size > kind.maxSize ||
                (
                    source.open(declared.path)?.use { disk.copyBounded(it, File(into, declared.path), declared.size) }
                        ?: !kind.required
                    )
        }

    private companion object {
        const val SIGNATURE_MAX_BYTES = 256L
    }
}
