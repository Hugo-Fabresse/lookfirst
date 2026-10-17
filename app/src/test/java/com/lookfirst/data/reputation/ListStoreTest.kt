package com.lookfirst.data.reputation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class ListStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val root by lazy { File(folder.root, "reputation") }
    private var now = TEST_NOW
    private val first = testSource(id = SourceIds.CERT_PL, minEntries = 3, maxEntries = 500)
    private val second = testSource(id = OTHER_SOURCE_ID, minEntries = 3, maxEntries = 500)
    private val syncs = mutableListOf<File>()

    private fun store(vararg sources: ListSource, sync: DirectorySync = DirectorySync { syncs += it }) =
        ListStore(root, sources.toList().ifEmpty { listOf(first) }, { now }, sync)

    private fun names(count: Int, prefix: String = "site") = Array(count) { "$prefix$it.com" }

    private fun ListStore.put(source: ListSource, count: Int, prefix: String = "site", validator: Long? = null) =
        install(source, parsed(*names(count, prefix)), validator)

    @Test
    fun nothingInstalledMeansNoListsAndNoSnapshot() {
        val store = store()
        assertNull(store.current())
        assertNull(store.snapshot())
        assertNull(failureOf(root, first.id))
    }

    @Test
    fun anInstalledListIsUsedAndSurvivesARestart() {
        val store = store()
        val result = store.put(first, 100)
        assertEquals(100L, (result as ListInstallResult.Installed).entries)
        assertTrue(store.current()!!.sources.single().index.contains(Lfix.keyOf('d', "site5.com")))
        assertEquals(100L, store.snapshot()!!.entries)
        assertEquals(0, store.snapshot()!!.ageDays)
        assertTrue(syncs.isNotEmpty())

        val restarted = store()
        restarted.preload()
        assertEquals(100L, restarted.snapshot()!!.entries)
        assertTrue(restarted.current()!!.sources.single().index.contains(Lfix.keyOf('d', "site99.com")))
    }

    @Test
    fun theInstalledIndexIsAValidLfixFileOfTheRightSource() {
        val store = store()
        store.put(first, 10)
        val file = File(root, "indexes/${first.id}.lfix")
        val header = Lfix.validate(file)!!
        assertEquals(first.id, header.sourceId)
        assertEquals(10L, header.entryCount)
    }

    @Test
    fun tooFewAndTooManyEntriesAreRefusedAndTheOldListStays() {
        val store = store()
        store.put(first, 100)
        assertEquals(ListFailure.TOO_FEW, (store.put(first, 2) as ListInstallResult.Rejected).reason)
        assertEquals(ListFailure.TOO_MANY, (store.put(first, 501) as ListInstallResult.Rejected).reason)
        assertEquals(100L, store.snapshot()!!.entries)
        assertEquals(ListFailure.TOO_MANY, failureOf(root, first.id))
    }

    @Test
    fun aFreshListMayChangeByTenPercentButNotMore() {
        val store = store()
        store.put(first, 100)
        assertTrue(store.put(first, 109) is ListInstallResult.Installed)
        val refused = store.put(first, 109 + 30)
        assertEquals(ListFailure.VARIATION, (refused as ListInstallResult.Rejected).reason)
        assertEquals(109L, store.snapshot()!!.entries)
        assertTrue(store.current()!!.sources.single().index.contains(Lfix.keyOf('d', "site5.com")))
    }

    @Test
    fun aShrinkingListIsRefusedToo() {
        val store = store()
        store.put(first, 100)
        assertEquals(ListFailure.VARIATION, (store.put(first, 60) as ListInstallResult.Rejected).reason)
    }

    @Test
    fun theToleranceGrowsWithTheAgeOfTheInstalledList() {
        val store = store()
        store.put(first, 100)
        now += 5 * TEST_DAY
        // 10 % + 5 days x 2 % = 20 %
        assertEquals(ListFailure.VARIATION, (store.put(first, 125) as ListInstallResult.Rejected).reason)
        assertTrue(store.put(first, 119) is ListInstallResult.Installed)
        now += 46 * TEST_DAY
        assertTrue(store.put(first, 400) is ListInstallResult.Installed)
    }

    @Test
    fun aRefusalKeepsTheOldFileByteForByte() {
        val store = store()
        store.put(first, 100)
        val file = File(root, "indexes/${first.id}.lfix")
        val before = file.readBytes()
        store.put(first, 2)
        store.put(first, 300)
        assertTrue(before.contentEquals(file.readBytes()))
        assertEquals(emptyList<String>(), File(root, "tmp").list().orEmpty().toList())
    }

    @Test
    fun aStorageErrorWhileSyncingIsReportedAndNothingIsRemembered() {
        val failing = store(sync = { throw IOException("disk") })
        val result = failing.put(first, 10)
        assertEquals(ListFailure.STORAGE, (result as ListInstallResult.Rejected).reason)
    }

    @Test
    fun sourcesAreIndependent() {
        val store = store(first, second)
        store.put(first, 50, "one")
        store.put(second, 80, "two")
        assertEquals(ListFailure.VARIATION, (store.put(first, 10) as ListInstallResult.Rejected).reason)
        val lists = store.current()!!
        assertEquals(2, lists.sources.size)
        assertEquals(130L, store.snapshot()!!.entries)
        assertTrue(lists.sources.first { it.source.id == second.id }.index.contains(Lfix.keyOf('d', "two7.com")))
        store.put(second, 85, "two")
        assertEquals(50L, store.current()!!.sources.first { it.source.id == first.id }.index.entryCount)
    }

    @Test
    fun theAgeIsThatOfTheOldestSourceAndAClockSetBackDoesNotRejuvenate() {
        val store = store(first, second)
        store.put(first, 50)
        now += 3 * TEST_DAY
        store.put(second, 50, "two")
        assertEquals(3, store.snapshot()!!.ageDays)
        now -= 10 * TEST_DAY
        assertEquals(3, store.snapshot()!!.ageDays)
    }

    @Test
    fun markUpToDateResetsTheAge() {
        val store = store()
        store.put(first, 50)
        now += 9 * TEST_DAY
        assertEquals(9, store.snapshot()!!.ageDays)
        store.markUpToDate(first)
        assertEquals(0, store.snapshot()!!.ageDays)
        assertEquals(0, store(first).also { it.preload() }.snapshot()!!.ageDays)
    }

    @Test
    fun theValidatorIsAnInstantNotInTheFutureAndOnlyUsedWhileTheLastFullDownloadIsRecent() {
        val store = store()
        store.put(first, 50, validator = TEST_LAST_MODIFIED_MILLIS)
        assertEquals(TEST_LAST_MODIFIED_MILLIS, store.installedInfo(first)!!.validatorMillis)
        now += TEST_DAY
        store.put(first, 51, validator = TEST_NOW + 2 * TEST_DAY)
        assertNull("a date after the clock is dropped", store.installedInfo(first)!!.validatorMillis)
        store.put(first, 52, validator = TEST_LAST_MODIFIED_MILLIS)
        assertEquals(TEST_LAST_MODIFIED_MILLIS, store.installedInfo(first)!!.validatorMillis)
        now += 7 * TEST_DAY
        store.markUpToDate(first)
        assertNull("7 days after the last full download: unconditional", store.installedInfo(first)!!.validatorMillis)
    }

    @Test
    fun aClockAheadThenCorrectedKeepsTheAgeRightAndTheVariationLimitOn() {
        val store = store()
        store.put(first, 100)
        now = TEST_NOW + 60 * TEST_DAY
        store.markUpToDate(first)
        now = TEST_NOW + TEST_DAY
        // The clock is right again: the list was confirmed at the latest time seen, it is not 59 days old.
        store.markUpToDate(first)
        assertEquals(0, store.snapshot()!!.ageDays)
        assertEquals(0, store.installedInfo(first)!!.ageDays)
        val refused = store.put(first, 150)
        assertEquals(ListFailure.VARIATION, (refused as ListInstallResult.Rejected).reason)
        assertEquals(100L, store.snapshot()!!.entries)
        now = TEST_NOW + 62 * TEST_DAY
        assertEquals(2, store.snapshot()!!.ageDays)
    }

    @Test
    fun anInstallWithAClockAheadThenCorrectedIsNotAgedEither() {
        val store = store()
        now = TEST_NOW + 90 * TEST_DAY
        store.put(first, 100)
        now = TEST_NOW
        assertEquals(0, store.snapshot()!!.ageDays)
        assertEquals(ListFailure.VARIATION, (store.put(first, 150) as ListInstallResult.Rejected).reason)
    }

    @Test
    fun aDamagedOrForeignIndexFileIsDiscardedAtLoad() {
        val store = store()
        store.put(first, 50)
        val file = File(root, "indexes/${first.id}.lfix")
        file.writeBytes(file.readBytes().copyOf(file.length().toInt() - 3))
        val restarted = store()
        assertNull(restarted.current())
        assertFalse(file.exists())

        store().put(first, 50)
        val other = store(second, first)
        File(root, "indexes/${second.id}.lfix").writeBytes(File(root, "indexes/${first.id}.lfix").readBytes())
        other.preload()
        assertFalse(File(root, "indexes/${second.id}.lfix").exists())
        assertNotNull(other.current())
    }

    @Test
    fun leftoverTemporaryFilesFromACrashAreRemovedAtLoad() {
        val store = store()
        store.put(first, 50)
        File(root, "tmp/index-1").writeText("half written")
        File(root, "tmp/download-1").writeText("half downloaded")
        val restarted = store()
        restarted.preload()
        assertEquals(emptyList<String>(), File(root, "tmp").list().orEmpty().toList())
        assertEquals(50L, restarted.snapshot()!!.entries)
    }

    @Test
    fun aDownloadInProgressSurvivesTheInstallationOfAnotherSource() {
        val store = store(first, second)
        val candidate = store.newCandidate()
        candidate.writeText("partial")
        store.put(first, 10)
        assertTrue(candidate.exists())
        store.releaseCandidate(candidate)
        assertFalse(candidate.exists())
    }

    @Test
    fun releaseCandidateOnlyDeletesInsideTheStore() {
        val store = store()
        val outside = folder.newFile("keep.txt")
        store.releaseCandidate(outside)
        assertTrue(outside.exists())
    }

    @Test
    fun failuresAreRememberedPerSourceAndClearedByASuccess() {
        val store = store(first, second)
        store.recordFailure(first, ListFailure.HTTP)
        now += 1000
        store.recordFailure(second, ListFailure.NETWORK)
        assertEquals(ListFailure.HTTP, failureOf(root, first.id))
        assertEquals(ListFailure.NETWORK, failureOf(root, second.id))
        store.put(second, 10)
        assertNull(failureOf(root, second.id))
        assertEquals(ListFailure.HTTP, failureOf(root, first.id))
    }

    @Test
    fun aGarbageStateFileIsAnEmptyState() {
        root.mkdirs()
        File(root, "state").writeText("maxSeen=abc\n999.fetchedAt=\n=\nfoo\n" + "z".repeat(40_000))
        val store = store()
        store.preload()
        assertNull(store.current())
        assertTrue(store.put(first, 10) is ListInstallResult.Installed)
    }
}
