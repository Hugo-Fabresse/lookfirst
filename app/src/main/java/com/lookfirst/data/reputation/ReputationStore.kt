package com.lookfirst.data.reputation

import com.lookfirst.domain.host.PublicSuffixList
import java.io.File
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Points of [ReputationStore.install] where a test can simulate a crash. */
internal enum class InstallStage { FILES_SYNCED, DIRECTORY_RENAMED, POINTER_WRITTEN, POINTER_SWAPPED }

internal sealed interface InstallResult {
    class Installed(val version: BundleVersion) : InstallResult

    /** The candidate is the installed bundle: nothing to do. */
    data object UpToDate : InstallResult

    class Rejected(val reason: RejectReason) : InstallResult
}

/** Version number and age in whole days of the bundle lookups use (settings screen). */
internal class ListsSnapshot(val version: Long, val ageDays: Int)

/** Result of looking at a downloaded manifest before any other file is fetched. */
internal sealed interface ManifestInspection {
    /** Signature, shape and ordering are fine and the bundle is newer: fetch [needed], the rest is already in place. */
    class Proceed(val manifest: BundleManifest, val manifestSha256: String, val needed: List<ManifestFile>) :
        ManifestInspection

    data object UpToDate : ManifestInspection

    /** This very manifest was rejected before: its files are not downloaded again. */
    data object KnownRejected : ManifestInspection

    class Rejected(val reason: RejectReason) : ManifestInspection
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
@Suppress("TooManyFunctions") // one cohesive store: load, inspect, install, state queries
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
    internal val keysTrusted = PinnedKeys.KEYS_ARE_PRODUCTION || acceptTestKeys
    private val lock = ReentrantLock()

    /** Candidate directories handed to a downloader and not yet installed or released: clean-up leaves them alone. */
    private val activeCandidates = HashSet<String>()

    @Volatile private var loaded: LoadedBundle? = null

    @Volatile private var loadAttempted = false

    @Volatile private var seenGeneratedAtMillis = 0L

    /**
     * The bundle to look hosts up in. Null when nothing is usable **or when it is not loaded yet and the store is
     * busy** (an installation holds the lock): a lookup never waits for an installation, it says "lists missing".
     * The app preloads the bundle at start (off the main thread), so this only happens in the first moments.
     */
    internal fun current(): LoadedBundle? {
        if (!keysTrusted) return null
        loaded?.let { return it }
        if (!lock.tryLock()) return null
        return try {
            ensureLoaded()
        } finally {
            lock.unlock()
        }
    }

    /** Loads the installed (or embedded) bundle if that is not done yet; blocks while an installation runs. */
    internal fun preload() {
        if (keysTrusted) lock.withLock { ensureLoaded() }
    }

    /** What the settings screen shows. Reads the already loaded bundle only: never waits, never loads. */
    internal fun snapshot(): ListsSnapshot? {
        val bundle = loaded ?: return null
        return ListsSnapshot(bundle.version.bundle, ageDays(bundle))
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
    internal fun newCandidateDirectory(): File = lock.withLock {
        ensureLoaded()
        disk.newCandidate().also { activeCandidates += it.name }
    }

    /** Gives a candidate up (download failed or stopped): it is deleted and no longer protected from clean-up. */
    internal fun releaseCandidate(candidate: File) = lock.withLock {
        activeCandidates -= candidate.name
        if (disk.isInsideTmp(candidate)) candidate.deleteRecursively()
    }

    /** True if a bundle with this manifest hash was rejected before (do not download its files again). */
    internal fun isKnownRejected(manifestSha256: String): Boolean =
        lock.withLock { StoreState.read(disk.stateFile).rejectedManifestSha == manifestSha256 }

    /** Remembers a candidate refused outside [install] (for instance a file longer than declared). */
    internal fun rejectCandidate(manifestSha256: String, reason: RejectReason) =
        lock.withLock { recordFailure(reason, manifestSha256) }

    internal fun lastFailure(): Pair<RejectReason, Long>? = lock.withLock {
        val state = StoreState.read(disk.stateFile)
        val reason = state.lastFailure
        val time = state.lastFailureMillis
        if (reason != null && time != null) reason to time else null
    }

    /**
     * Steps 2 to 4 on the manifest and signature just downloaded into [candidate]: nothing is read before the
     * signature is verified. On `Proceed`, files of the installed bundle that the new manifest keeps unchanged
     * (same name, size and SHA-256) are copied into [candidate]; `needed` lists the files still to download.
     * A rejection is remembered (reason and manifest hash) exactly like an installation rejection.
     */
    internal fun inspectManifest(candidate: File): ManifestInspection = lock.withLock {
        if (!keysTrusted) return ManifestInspection.Rejected(RejectReason.BAD_SIGNATURE)
        if (!disk.ensureDirectories() || !disk.isInsideTmp(candidate)) {
            return ManifestInspection.Rejected(RejectReason.STORAGE)
        }
        val state = StoreState.read(disk.stateFile)
        val installed = ensureLoaded()
        val floor = listOfNotNull(installed?.version, state.highest).maxOrNull()
        val baseline = installed?.let {
            Baseline(it.version, it.generatedAtMillis, it.directory, it.phishing.entryCount, it.allowlist.entryCount)
        }
        val outcome = BundleVerifier.precheck(candidate, ringFor(state), clock(), floor, baseline)
        when {
            outcome is VerifyOutcome.Rejected -> {
                recordFailure(outcome.reason, outcome.manifestSha256)
                ManifestInspection.Rejected(outcome.reason)
            }
            outcome is VerifyOutcome.UpToDate -> {
                disk.writeState(state.copy(lastSuccessMillis = clock()))
                ManifestInspection.UpToDate
            }
            outcome is VerifyOutcome.Accepted && state.rejectedManifestSha == outcome.manifestSha256 ->
                ManifestInspection.KnownRejected
            outcome is VerifyOutcome.Accepted ->
                ManifestInspection.Proceed(
                    outcome.manifest,
                    outcome.manifestSha256,
                    CandidateFiles.reuseUnchanged(disk, outcome.manifest, installed, candidate),
                )
            else -> ManifestInspection.Rejected(RejectReason.BAD_MANIFEST)
        }
    }

    /** Verifies [candidate] and, if every check passes, installs it atomically. The candidate is consumed. */
    internal fun install(candidate: File): InstallResult = lock.withLock {
        val result = try {
            if (keysTrusted) installLocked(candidate) else InstallResult.Rejected(RejectReason.BAD_SIGNATURE)
        } catch (@Suppress("SwallowedException") ignored: IOException) {
            InstallResult.Rejected(RejectReason.STORAGE)
        }
        activeCandidates -= candidate.name
        if (result !is InstallResult.Installed && disk.isInsideTmp(candidate)) candidate.deleteRecursively()
        if (result is InstallResult.Rejected &&
            result.reason == RejectReason.STORAGE
        ) {
            recordFailure(RejectReason.STORAGE, null)
        }
        result
    }

    /** Adopts a keyset signed by R that opens a newer key epoch. False (nothing changes) on any deviation. */
    internal fun applyKeyset(json: ByteArray, signature: ByteArray): Boolean = lock.withLock {
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
        disk.cleanUp(manifest.version.directoryName, activeCandidates)
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
        disk.cleanUp(bundle?.version?.directoryName, activeCandidates)
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
            if (!CandidateFiles.copyEmbeddedManifest(disk, source, candidate)) return null
            val signed = BundleVerifier.readSigned(candidate, pinnedRing) as? SignedManifest.Ok ?: return null
            if (installed != null && signed.manifest.version <= installed.version) return null
            if (!CandidateFiles.copyEmbeddedFiles(disk, source, candidate, signed.manifest)) return null
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
}
