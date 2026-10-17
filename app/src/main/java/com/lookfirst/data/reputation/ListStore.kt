package com.lookfirst.data.reputation

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private const val BASE_VARIATION_PERCENT = 10L
private const val VARIATION_PERCENT_PER_DAY = 2L
private const val NO_LIMIT_AFTER_DAYS = 45L
private const val PERCENT = 100L
private const val DAY_MILLIS = 24L * 60 * 60 * 1000

/** A list is downloaded in full at least this often, whatever the server answers to a conditional request. */
private const val FULL_DOWNLOAD_MAX_AGE_DAYS = 7L

/** One installed source: its index mapped read-only and the moment it was last confirmed current. */
internal class LoadedSource(val source: ListSource, val index: MappedIndex, val checkedAtMillis: Long)

/** The indexes lookups use. Never empty: the store returns null when no source is installed. */
internal class LoadedLists(val sources: List<LoadedSource>)

/** Entries across all sources and age in whole days of the oldest one (settings screen). */
internal class ListsSummary(val entries: Long, val ageDays: Int)

/** Forces a directory entry (a rename) to storage. Android: `Os.open` + `Os.fsync`; tests pass a recorder. */
internal fun interface DirectorySync {
    @Throws(IOException::class)
    fun sync(directory: File)
}

internal sealed interface ListInstallResult {
    class Installed(val entries: Long) : ListInstallResult

    class Rejected(val reason: ListFailure) : ListInstallResult
}

/**
 * Allowed change of an index between two downloads, proportional to the age of the installed one (the table of
 * ADR-0008: 10 % when fresh, 2 points more per day, no limit after 45 days; the entry bounds still apply).
 */
internal object ListVariation {
    fun tolerancePercent(ageDays: Long): Long? =
        if (ageDays >= NO_LIMIT_AFTER_DAYS) null else BASE_VARIATION_PERCENT + VARIATION_PERCENT_PER_DAY * ageDays

    /**
     * True when more than [tolerancePercent] of the [oldCount] installed entries were removed, or when more than
     * that share of them was added (LF-32: a count alone lets a full swap through at equal size). No limit when
     * [tolerancePercent] is null or nothing is installed.
     */
    fun exceedsTolerance(oldCount: Long, difference: SetDifference, tolerancePercent: Long?): Boolean {
        if (tolerancePercent == null || oldCount <= 0) return false
        val limit = oldCount * tolerancePercent
        return difference.removed * PERCENT > limit || difference.added * PERCENT > limit
    }
}

/**
 * Indexes built on the phone from the lists of their publishers, in app-private storage:
 * `indexes/<source id>.lfix` (one file per source, replaced atomically), `tmp/` (downloads and index candidates),
 * `state`. A source is replaced only by a file that passed [DomainListParser], the entry bounds and the variation
 * limit; any failure leaves the installed index as it is and is remembered. Sources are independent.
 *
 * Nothing here logs: no host, no list line and no server text ever reaches a log.
 */
@Suppress("TooManyFunctions") // one cohesive store: load, query, install, remember failures
class ListStore internal constructor(
    root: File,
    private val sources: List<ListSource>,
    private val clock: () -> Long,
    private val directorySync: DirectorySync,
) {
    private val indexes = File(root, "indexes")
    private val tmp = File(root, "tmp")
    private val stateFile = File(root, "state")
    private val lock = ReentrantLock()
    private val activeCandidates = HashSet<String>()

    @Volatile private var loaded: LoadedLists? = null

    @Volatile private var loadAttempted = false

    @Volatile private var maxSeenMillis = 0L

    /** Sources whose recorded time is in the future of the clock and of the publisher's `Date`; memory only. */
    private val unknownAgeSources = HashSet<Int>()

    /** The indexes to look hosts up in; null when none is installed or when a load/installation holds the lock. */
    internal fun current(): LoadedLists? {
        if (loadAttempted) return loaded
        if (!lock.tryLock()) return null
        return try {
            ensureLoaded()
        } finally {
            lock.unlock()
        }
    }

    /** Loads the installed indexes if that is not done yet; blocks while an installation runs. */
    internal fun preload() {
        lock.withLock { ensureLoaded() }
    }

    /** What the settings screen shows. Reads the already loaded indexes only: never waits, never loads. */
    internal fun snapshot(): ListsSummary? {
        val lists = loaded ?: return null
        return ListsSummary(lists.sources.sumOf { it.index.entryCount }, ageDays(lists))
    }

    /**
     * The age the lookup judges staleness by: -1 (unknown, shown as `LISTS_STALE`) while a source's recorded time
     * lies after both the device clock and the publisher's `Date` (recorded with a wrong clock); else [ageDays].
     */
    internal fun staleAgeDays(lists: LoadedLists): Int =
        if (lists.sources.any { it.source.id in unknownAgeSources }) -1 else ageDays(lists)

    /** Whole days since the oldest source was last confirmed, against `max(clock, latest time ever seen)`. */
    internal fun ageDays(lists: LoadedLists): Int {
        val oldest = lists.sources.minOf { it.checkedAtMillis }
        return daysBetween(oldest, referenceClock())
    }

    /**
     * Entry count, age and the validator of the installed index of [source], or null if it has none. The validator
     * is null (the next request is unconditional) when it is missing, in the future, or when the last full download
     * is [FULL_DOWNLOAD_MAX_AGE_DAYS] days old or more: a server that keeps answering `304` cannot freeze the list.
     */
    internal fun installedInfo(source: ListSource): InstalledInfo? = lock.withLock {
        val entry = ensureLoaded()?.sources?.firstOrNull { it.source.id == source.id } ?: return null
        val state = ListsState.read(stateFile).of(source.id)
        val now = referenceClock()
        val fullRecent =
            state.fullAtMillis != null && now - state.fullAtMillis < FULL_DOWNLOAD_MAX_AGE_DAYS * DAY_MILLIS
        val validator = state.validatorMillis?.takeIf { fullRecent && it <= now }
        InstalledInfo(entry.index.entryCount, daysBetween(entry.checkedAtMillis, now), validator)
    }

    /**
     * The time recorded for a successful contact: `max(clock, latest time seen)`, never below the device clock. The
     * publisher's `Date` is untrusted (the publisher chooses it): it may only bring a latest time seen that lies
     * more than a day after it back down, and never below the clock (LF-32). It is never used to lift a limit.
     */
    private fun referenceClock(serverTime: Long? = null): Long = maxOf(clock(), cappedMaxSeen(serverTime))

    private fun cappedMaxSeen(serverTime: Long?): Long =
        if (serverTime != null && maxSeenMillis > serverTime + DAY_MILLIS) {
            maxOf(clock(), serverTime + DAY_MILLIS)
        } else {
            maxSeenMillis
        }

    private fun daysBetween(from: Long, to: Long): Int = ((to - from).coerceAtLeast(0L) / DAY_MILLIS).toInt()

    private fun ensureLoaded(): LoadedLists? {
        if (!loadAttempted) {
            loadAttempted = true
            loaded = loadInstalled()
        }
        return loaded
    }

    private fun loadInstalled(): LoadedLists? {
        ensureDirectories()
        val state = ListsState.read(stateFile)
        maxSeenMillis = state.maxSeenMillis
        val found = sources.mapNotNull { source -> openInstalled(source, state) }
        found.forEach { maxSeenMillis = maxOf(maxSeenMillis, it.checkedAtMillis) }
        cleanTmp()
        return if (found.isEmpty()) null else LoadedLists(found)
    }

    private fun openInstalled(source: ListSource, state: ListsState): LoadedSource? {
        val file = indexFile(source)
        if (!file.isFile) return null
        val index = MappedIndex.open(file)
        if (index == null || index.header.sourceId != source.id) {
            file.delete()
            return null
        }
        val checkedAt = state.of(source.id).fetchedAtMillis ?: file.lastModified()
        return LoadedSource(source, index, checkedAt)
    }

    private fun indexFile(source: ListSource) = File(indexes, "${source.id}.lfix")

    private fun ensureDirectories(): Boolean = (indexes.isDirectory || indexes.mkdirs()) &&
        (tmp.isDirectory || tmp.mkdirs())

    private fun cleanTmp() {
        tmp.listFiles().orEmpty().filter { it.name !in activeCandidates }.forEach { it.deleteRecursively() }
    }

    /** A fresh empty file name inside the store (same file system as the final rename); the caller writes to it. */
    internal fun newCandidate(): File = lock.withLock {
        ensureLoaded()
        if (!ensureDirectories()) throw IOException("cannot create the store directories")
        File(tmp, "download-${System.nanoTime()}").also { activeCandidates += it.name }
    }

    /** Gives a candidate up (finished or failed): it is deleted and no longer protected from clean-up. */
    internal fun releaseCandidate(candidate: File) = lock.withLock {
        activeCandidates -= candidate.name
        if (candidate.canonicalFile.parentFile == tmp.canonicalFile) candidate.delete()
    }

    /** The server said "not modified": the installed index is current as of now. */
    internal fun markUpToDate(source: ListSource, serverTimeMillis: Long? = null) = lock.withLock {
        ensureLoaded()
        val now = referenceClock(serverTimeMillis)
        val seen = maxOf(cappedMaxSeen(serverTimeMillis), now)
        val state = ListsState.read(stateFile)
        val fresh = state.of(source.id).copy(fetchedAtMillis = now, failure = null, failureAtMillis = null)
        try {
            persist(state.with(source.id, fresh).copy(maxSeenMillis = seen))
        } catch (@Suppress("SwallowedException") ignored: IOException) {
            // Only the age is not refreshed: the installed index is still the right one.
        }
        maxSeenMillis = seen
        unknownAgeSources -= source.id
        loaded = loaded?.let { lists ->
            LoadedLists(
                lists.sources.map {
                    if (it.source.id ==
                        source.id
                    ) {
                        LoadedSource(it.source, it.index, now)
                    } else {
                        it
                    }
                },
            )
        }
    }

    /** Remembers why [source] was not updated; the installed index is untouched. */
    internal fun recordFailure(source: ListSource, reason: ListFailure) = lock.withLock {
        recordFailureLocked(source, reason)
    }

    /** Applies the checks of ADR-0008 to a parsed list and, if all pass, replaces the installed index atomically. */
    internal fun install(
        source: ListSource,
        parsed: ParseOutcome.Parsed,
        validatorMillis: Long?,
        serverTimeMillis: Long? = null,
    ): ListInstallResult = lock.withLock {
        noteUnknownAge(source, serverTimeMillis)
        val result = try {
            installLocked(source, parsed, validatorMillis, serverTimeMillis)
        } catch (@Suppress("SwallowedException") ignored: IOException) {
            ListInstallResult.Rejected(ListFailure.STORAGE)
        }
        if (result is ListInstallResult.Rejected) recordFailureLocked(source, result.reason)
        result
    }

    private fun installLocked(
        source: ListSource,
        parsed: ParseOutcome.Parsed,
        validatorMillis: Long?,
        serverTimeMillis: Long?,
    ): ListInstallResult {
        val entries = parsed.keys.size.toLong()
        val refusal = refusalFor(source, parsed) ?: writeIndex(source, parsed)
        return if (refusal != null) {
            ListInstallResult.Rejected(refusal)
        } else {
            adopt(source, entries, validatorMillis, serverTimeMillis)
        }
    }

    /** The entry bounds, then the variation limit against the installed index. Null when both pass. */
    private fun refusalFor(source: ListSource, parsed: ParseOutcome.Parsed): ListFailure? {
        val entries = parsed.keys.size.toLong()
        val installed = ensureLoaded()?.sources?.firstOrNull { it.source.id == source.id }
        return when {
            entries < source.minEntries -> ListFailure.TOO_FEW
            entries > source.maxEntries -> ListFailure.TOO_MANY
            installed != null && exceedsVariation(installed, parsed.keys) -> ListFailure.VARIATION
            else -> null
        }
    }

    /** Only ever makes the app more careful: flags a recorded time that neither the clock nor `Date` can explain. */
    private fun noteUnknownAge(source: ListSource, serverTime: Long?) {
        val installed = ensureLoaded()?.sources?.firstOrNull { it.source.id == source.id } ?: return
        val recorded = installed.checkedAtMillis
        if (serverTime != null && recorded > serverTime + DAY_MILLIS && recorded > clock() + DAY_MILLIS) {
            unknownAgeSources += source.id
        }
    }

    /**
     * Set difference between the installed index and the candidate (both strictly increasing, merged in one linear
     * pass over the mapped file: no copy, no hash set). An installed index that is not strictly increasing is
     * damaged and cannot serve as a baseline: it is replaced like a first install, the entry bounds still apply.
     * The tolerance depends on the local age only (recorded time against `max(clock, latest time seen)`): the
     * publisher's `Date` never relaxes it.
     */
    private fun exceedsVariation(installed: LoadedSource, keys: LongArray): Boolean {
        val age = daysBetween(installed.checkedAtMillis, referenceClock()).toLong()
        val tolerance = ListVariation.tolerancePercent(age) ?: return false
        val difference = installed.index.differenceTo(keys) ?: return false
        return ListVariation.exceedsTolerance(installed.index.entryCount, difference, tolerance)
    }

    /** Writes the index to a temporary file, validates it and renames it over the installed one. Null on success. */
    private fun writeIndex(source: ListSource, parsed: ParseOutcome.Parsed): ListFailure? {
        if (!ensureDirectories()) return ListFailure.STORAGE
        val candidate = File(tmp, "index-${System.nanoTime()}")
        activeCandidates += candidate.name
        try {
            IndexWriter.write(candidate, source.id, parsed.keys)
            val header = Lfix.validate(candidate)
            return when {
                header == null || header.entryCount != parsed.keys.size.toLong() -> ListFailure.BAD_FORMAT
                !candidate.renameTo(indexFile(source)) -> ListFailure.STORAGE
                else -> {
                    directorySync.sync(indexes)
                    null
                }
            }
        } finally {
            activeCandidates -= candidate.name
            candidate.delete()
        }
    }

    private fun adopt(
        source: ListSource,
        entries: Long,
        validatorMillis: Long?,
        serverTime: Long?,
    ): ListInstallResult {
        val now = referenceClock(serverTime)
        val seen = maxOf(cappedMaxSeen(serverTime), now)
        val state = ListsState.read(stateFile)
        val validator = validatorMillis?.takeIf { it in 1..now }
        persist(
            state.with(source.id, SourceState(fetchedAtMillis = now, fullAtMillis = now, validatorMillis = validator))
                .copy(maxSeenMillis = seen),
        )
        maxSeenMillis = seen
        unknownAgeSources -= source.id
        val index = MappedIndex.open(indexFile(source)) ?: return ListInstallResult.Rejected(ListFailure.STORAGE)
        val others = loaded?.sources.orEmpty().filter { it.source.id != source.id }
        loaded = LoadedLists(others + LoadedSource(source, index, now))
        loadAttempted = true
        return ListInstallResult.Installed(entries)
    }

    private fun recordFailureLocked(source: ListSource, reason: ListFailure) {
        val state = ListsState.read(stateFile)
        try {
            ensureDirectories()
            persist(state.with(source.id, state.of(source.id).copy(failure = reason, failureAtMillis = clock())))
        } catch (@Suppress("SwallowedException") ignored: IOException) {
            // Storage is failing: the installed index stays as it is and nothing more can be remembered.
        }
    }

    private fun persist(state: ListsState) {
        ensureDirectories()
        val temporary = File(stateFile.parentFile, "state.tmp")
        FileOutputStream(temporary).use { out ->
            out.write(state.serialize().toByteArray(Charsets.US_ASCII))
            out.fd.sync()
        }
        if (!temporary.renameTo(stateFile)) throw IOException("cannot replace the state file")
    }
}

/** What the downloader needs to know about an installed source. */
internal class InstalledInfo(val entries: Long, val ageDays: Int, val validatorMillis: Long?)
