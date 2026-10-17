package com.lookfirst.data.reputation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Random

/** LF-32: the variation limit counts entries added and removed, and the clock cannot freeze the age limit. */
class ListSetVariationTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val root by lazy { File(folder.root, "reputation") }
    private var now = TEST_NOW
    private val source = testSource(id = SourceIds.CERT_PL, minEntries = 3, maxEntries = 1_000_000)

    private fun store() = ListStore(root, listOf(source), { now }, DirectorySync { })

    private fun sites(from: Int, to: Int) = Array(to - from) { "site${from + it}.com" }

    private fun ListStore.put(from: Int, to: Int, serverTime: Long? = null) =
        install(source, parsed(*sites(from, to)), null, serverTime)

    private fun ListInstallResult.reason() = (this as ListInstallResult.Rejected).reason

    @Test
    fun aFullReplacementAtEqualCountIsRefused() {
        val store = store()
        store.put(0, 100)
        assertEquals(ListFailure.VARIATION, store.put(1000, 1100).reason())
        assertTrue(store.current()!!.sources.single().index.contains(Lfix.keyOf('d', "site5.com")))
    }

    @Test
    fun aMassiveAdditionIsRefused() {
        val store = store()
        store.put(0, 100)
        assertEquals(ListFailure.VARIATION, store.put(0, 130).reason())
    }

    @Test
    fun aMassiveRemovalIsRefused() {
        val store = store()
        store.put(0, 100)
        assertEquals(ListFailure.VARIATION, store.put(30, 100).reason())
    }

    @Test
    fun aSwapOfAThirdAtEqualCountIsRefused() {
        val store = store()
        store.put(0, 100)
        assertEquals(ListFailure.VARIATION, store.put(30, 130).reason())
    }

    @Test
    fun aNormalDailyRotationIsAccepted() {
        val store = store()
        store.put(0, 1000)
        // 3 % removed, 3 % added
        assertTrue(store.put(30, 1030) is ListInstallResult.Installed)
        assertEquals(1000L, store.snapshot()!!.entries)
    }

    @Test
    fun theToleranceGrowsWithTheAgeForRemovedAndAdded() {
        val store = store()
        store.put(0, 100)
        now += 5 * TEST_DAY // 20 %
        assertEquals(ListFailure.VARIATION, store.put(25, 125).reason())
        assertTrue(store.put(19, 119) is ListInstallResult.Installed)
        now += 46 * TEST_DAY
        assertTrue(store.put(5000, 5100) is ListInstallResult.Installed)
    }

    @Test
    fun aDamagedInstalledIndexIsReplacedLikeAFirstInstall() {
        val store = store()
        store.put(0, 100)
        val file = File(root, "indexes/${source.id}.lfix")
        val bytes = file.readBytes()
        // Swap two entries: size and header stay valid (so it still loads) but the order is broken.
        val swapped = bytes.copyOf()
        System.arraycopy(bytes, 64 + 8, swapped, 64 + 16, 8)
        System.arraycopy(bytes, 64 + 16, swapped, 64 + 8, 8)
        file.writeBytes(swapped)
        val restarted = store()
        restarted.preload()
        assertEquals(100L, restarted.snapshot()!!.entries)
        assertTrue(restarted.put(1000, 1100) is ListInstallResult.Installed)
        assertNotNull(Lfix.validate(file))
        // From now on the baseline is sound again.
        assertEquals(ListFailure.VARIATION, restarted.put(5000, 5100).reason())
    }

    @Test
    fun theFirstDownloadIsNotComparedToAnything() {
        assertTrue(store().put(0, 100) is ListInstallResult.Installed)
    }

    @Test
    fun differenceHandlesEqualEmptyAndDisjointSets() {
        val store = store()
        store.install(source, keyed("d:a.com", "d:b.com", "d:c.com", "d:d.com"), null)
        val index = store.current()!!.sources.single().index
        val same = index.differenceTo(keyed("d:a.com", "d:b.com", "d:c.com", "d:d.com").keys)!!
        assertEquals(0L, same.removed)
        assertEquals(0L, same.added)
        val none = index.differenceTo(LongArray(0))!!
        assertEquals(4L, none.removed)
        assertEquals(0L, none.added)
        val other = index.differenceTo(keyed("d:x.com", "d:y.com").keys)!!
        assertEquals(4L, other.removed)
        assertEquals(2L, other.added)
    }

    @Test
    fun theDifferenceOf125000KeysIsLinearAndCheap() {
        val random = Random(32)
        val old = unsignedSorted(LongArray(125_000) { random.nextLong() })
        val file = File(folder.root, "big.lfix")
        IndexWriter.write(file, source.id, old)
        val index = MappedIndex.open(file)!!
        val changed = old.copyOf()
        for (i in 0 until 3_000) changed[i * 40] = random.nextLong()
        val fresh = unsignedSorted(changed)
        val started = System.nanoTime()
        val difference = index.differenceTo(fresh)!!
        val millis = (System.nanoTime() - started) / 1_000_000
        println("LF-32 differenceTo(${old.size} vs ${fresh.size}): $millis ms, removed=${difference.removed}")
        assertTrue("took $millis ms", millis < 1_000)
        assertTrue(difference.removed in 1..3_000)
    }

    private fun unsignedSorted(keys: LongArray): LongArray =
        keys.map { it xor Long.MIN_VALUE }.sorted().distinct().map { it xor Long.MIN_VALUE }.toLongArray()

    @Test
    fun aClockAheadByAYearThenCorrectedKeepsTheLimitAndShowsStaleOnceTheDateContradictsIt() {
        val store = store()
        now = TEST_NOW + 365 * TEST_DAY
        store.put(0, 100) // first install with a wrong clock and no Date
        now = TEST_NOW + 60 * TEST_DAY
        assertEquals(0, store.snapshot()!!.ageDays) // nothing can tell yet (LF-28)
        // The publisher's Date never lifts the limit: a full replacement is refused...
        assertEquals(ListFailure.VARIATION, store.put(1000, 1100, serverTime = now).reason())
        // ...and the recorded time, in the future of both clock and Date, makes the lookup say "stale".
        assertEquals(-1, store.staleAgeDays(store.current()!!))
        // A 304 with the true Date brings the recorded time back near the clock: normal age again.
        store.markUpToDate(source, serverTimeMillis = now)
        assertEquals(0, store.staleAgeDays(store.current()!!))
        now += 3 * TEST_DAY
        assertEquals(2, store.snapshot()!!.ageDays) // recorded at Date + 1 day at most
    }

    @Test
    fun aPastDateWithTheRightClockNeverLiftsTheLimitNorMovesTheTimes() {
        val store = store()
        store.put(0, 100)
        val before = File(root, "state").readText()
        assertTrue(before.startsWith("maxSeen=$TEST_NOW"))
        for (date in listOf(946_684_800_000L, TEST_NOW - 2 * TEST_DAY)) {
            assertEquals(ListFailure.VARIATION, store.put(1000, 1100, serverTime = date).reason())
            assertEquals(0, store.staleAgeDays(store.current()!!))
        }
        now += 3_600_000L
        store.markUpToDate(source, serverTimeMillis = 946_684_800_000L)
        assertEquals(0, store.staleAgeDays(store.current()!!))
        assertEquals(ListFailure.VARIATION, store.put(1000, 1100).reason())
        assertTrue(File(root, "state").readText().startsWith("maxSeen=${TEST_NOW + 3_600_000L}"))
    }

    @Test
    fun aPastDateOnANotModifiedThenAFullDownloadWithoutDateKeepsTheLimit() {
        val store = store()
        store.put(0, 100)
        now += 2 * TEST_DAY
        store.markUpToDate(source, serverTimeMillis = TEST_NOW - 400 * TEST_DAY)
        assertEquals(ListFailure.VARIATION, store.put(1000, 1100).reason())
    }

    @Test
    fun aNotModifiedWithTheServerDateHealsAFutureTime() {
        val store = store()
        now = TEST_NOW + 365 * TEST_DAY
        store.put(0, 100)
        now = TEST_NOW
        store.markUpToDate(source, serverTimeMillis = TEST_NOW)
        now = TEST_NOW + 10 * TEST_DAY
        assertEquals(9, store.snapshot()!!.ageDays) // recorded at Date + 1 day
    }

    @Test
    fun aClockAFewHoursAheadIsTheNormalCase() {
        val store = store()
        now = TEST_NOW + 5 * 3_600_000L
        store.put(0, 100, serverTime = TEST_NOW)
        now = TEST_NOW + 2 * TEST_DAY
        assertEquals(1, store.snapshot()!!.ageDays)
        assertEquals(ListFailure.VARIATION, store.put(1000, 1100, serverTime = now).reason())
    }
}
