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

class ReputationStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private class Crash : RuntimeException()

    private var clock = NOW
    private val synced = mutableListOf<String>()
    private val root get() = File(folder.root, "reputation")

    private fun store(embedded: EmbeddedBundleSource? = null, crashAt: InstallStage? = null) = ReputationStore(
        root,
        embedded,
        { clock },
        directorySync = { synced += it.name },
        acceptTestKeys = true,
        onStage = { if (it == crashAt) throw Crash() },
    )

    private fun stage(store: ReputationStore, spec: BundleSpec): File =
        BundleBuilder.build(store.newCandidateDirectory(), spec).directory

    private fun install(store: ReputationStore, spec: BundleSpec): InstallResult = store.install(stage(store, spec))

    private fun versionOf(generatedAt: String) = BundleBuilder.version(generatedAt)

    private fun reasonOf(result: InstallResult) = (result as? InstallResult.Rejected)?.reason

    // Entry counts close enough for the variation check (10 % + 2 % per day of age).
    private val day10 =
        BundleSpec(generatedAt = "2026-10-10T03:12:00Z", phishingKeys = BundleBuilder.syntheticKeys(100, 1))
    private val day12 =
        BundleSpec(generatedAt = "2026-10-12T03:12:00Z", phishingKeys = BundleBuilder.syntheticKeys(110, 2))

    @Test
    fun nothingInstalledMeansNoBundle() {
        assertNull(store().current())
    }

    @Test
    fun validBundleIsInstalledAndSurvivesARestart() {
        val first = store()
        assertTrue(install(first, day10) is InstallResult.Installed)
        assertEquals(versionOf(day10.generatedAt), first.current()?.version?.bundle)
        val restarted = store()
        assertEquals(versionOf(day10.generatedAt), restarted.current()?.version?.bundle)
        assertEquals(listOf("0-${versionOf(day10.generatedAt)}"), File(root, "bundles").list()!!.toList())
        assertEquals(emptyList<String>(), File(root, "tmp").list()!!.toList())
    }

    @Test
    fun newerBundleReplacesOlderAndOldDirectoryIsRemoved() {
        val s = store()
        install(s, day10)
        assertTrue(install(s, day12) is InstallResult.Installed)
        assertEquals(listOf("0-${versionOf(day12.generatedAt)}"), File(root, "bundles").list()!!.toList())
        assertEquals(versionOf(day12.generatedAt), store().current()?.version?.bundle)
    }

    @Test
    fun sameVersionIsUpToDateAndOlderIsRollback() {
        val s = store()
        install(s, day12)
        assertEquals(InstallResult.UpToDate, install(s, day12))
        assertEquals(RejectReason.ROLLBACK, reasonOf(install(s, day10)))
        assertEquals(versionOf(day12.generatedAt), s.current()?.version?.bundle)
    }

    @Test
    fun rejectedBundleLeavesTheInstalledOneUntouchedAndIsRemembered() {
        val s = store()
        install(s, day10)
        val bad = BundleBuilder.build(s.newCandidateDirectory(), day12.copy(signer = TestKeys.other))
        assertEquals(RejectReason.BAD_SIGNATURE, reasonOf(s.install(bad.directory)))
        assertEquals(versionOf(day10.generatedAt), s.current()?.version?.bundle)
        assertEquals(RejectReason.BAD_SIGNATURE, s.lastFailure()?.first)
        assertFalse(bad.directory.exists())

        val hashBad = BundleBuilder.build(s.newCandidateDirectory(), day12)
        hashBad.file(BundleFile.PUBLIC_SUFFIX_LIST).appendBytes(byteArrayOf(1))
        val sha = Hex.encode(sha256(hashBad.manifestFile.readBytes()))
        assertFalse(s.isKnownRejected(sha))
        assertEquals(RejectReason.TOO_LARGE, reasonOf(s.install(hashBad.directory)))
        assertTrue(s.isKnownRejected(sha))
        assertEquals(RejectReason.TOO_LARGE, store().lastFailure()?.first)
        // A later success clears the failure record.
        install(s, day12)
        assertNull(s.lastFailure())
        assertFalse(s.isKnownRejected(sha))
    }

    @Test
    fun floorSurvivesRemovingTheInstalledBundle() {
        val s = store()
        install(s, day12)
        File(root, "bundles").deleteRecursively()
        File(root, "current").delete()
        val restarted = store()
        assertNull(restarted.current())
        assertEquals(RejectReason.ROLLBACK, reasonOf(install(restarted, day10)))
    }

    @Test
    fun crashAtAnyStageBeforeThePointerSwapKeepsTheOldBundle() {
        listOf(
            InstallStage.FILES_SYNCED,
            InstallStage.DIRECTORY_RENAMED,
            InstallStage.POINTER_WRITTEN,
        ).forEach { stageName ->
            File(folder.root, "reputation").deleteRecursively()
            install(store(), day10)
            val crashing = store(crashAt = stageName)
            try {
                install(crashing, day12)
                throw AssertionError("expected a simulated crash at $stageName")
            } catch (expected: Crash) {
                // The process "died" here.
            }
            val restarted = store()
            assertEquals(
                "after crash at $stageName",
                versionOf(day10.generatedAt),
                restarted.current()?.version?.bundle,
            )
            assertEquals(listOf("0-${versionOf(day10.generatedAt)}"), File(root, "bundles").list()!!.toList())
            assertTrue(File(root, "tmp").list()!!.isEmpty())
            // The failed update can be retried and then succeeds.
            assertTrue(install(restarted, day12) is InstallResult.Installed)
        }
    }

    @Test
    fun crashRightAfterThePointerSwapLeavesTheNewBundle() {
        install(store(), day10)
        try {
            install(store(crashAt = InstallStage.POINTER_SWAPPED), day12)
        } catch (expected: Crash) {
            // Simulated death after the swap, before the floor was written.
        }
        val restarted = store()
        assertEquals(versionOf(day12.generatedAt), restarted.current()?.version?.bundle)
        // The floor file was not written, yet the installed bundle is the floor: an older bundle is still refused.
        assertEquals(RejectReason.ROLLBACK, reasonOf(install(restarted, day10)))
    }

    @Test
    fun damagedInstalledBundleIsDiscardedAndListsAreMissing() {
        install(store(), day12)
        val bundleDir = File(root, "bundles").listFiles()!!.single()
        File(bundleDir, BundleFile.PHISHING_INDEX.path).writeBytes(ByteArray(10))
        val restarted = store()
        assertNull(restarted.current())
        assertFalse(File(root, "current").exists())
        assertTrue(File(root, "bundles").list()!!.isEmpty())
        // A damaged install does not lower the floor.
        assertEquals(RejectReason.ROLLBACK, reasonOf(install(restarted, day10)))
    }

    @Test
    fun forgedPointerIsDiscarded() {
        install(store(), day12)
        File(root, "current").writeText("0 ${versionOf(day12.generatedAt)} ${"ab".repeat(32)}\n")
        assertNull(store().current())
        File(root, "current").writeText("garbage")
        assertNull(store().current())
    }

    @Test
    fun candidateOutsideTheStoreIsRefusedAndNotDeleted() {
        val s = store()
        val outside = BundleBuilder.build(File(folder.root, "elsewhere"), day10)
        assertEquals(RejectReason.STORAGE, reasonOf(s.install(outside.directory)))
        assertTrue("a directory outside the store is never deleted", outside.directory.exists())
    }

    @Test
    fun unlistedFilesAreNotInstalled() {
        val s = store()
        val candidate = BundleBuilder.build(s.newCandidateDirectory(), day10)
        File(candidate.directory, "evil.sh").writeText("x")
        File(candidate.directory, "sub").mkdirs()
        File(candidate.directory, "sub/y").writeText("y")
        assertTrue(s.install(candidate.directory) is InstallResult.Installed)
        val installed = File(root, "bundles").listFiles()!!.single().list()!!.toSet()
        assertEquals(
            setOf(
                "manifest.json",
                "manifest.sig",
                "reputation-phishing-database.idx",
                "allowlist.idx",
                "public-suffix-list.txt",
            ),
            installed,
        )
    }

    @Test
    fun ageUsesGeneratedAtAndNeverGoesNegative() {
        val s = store()
        install(s, day10)
        val bundle = s.current()!!
        clock = NOW
        assertEquals(7, s.ageDays(bundle))
        clock = NOW + 20 * UtcTime.DAY
        assertEquals(27, s.ageDays(bundle))
        clock = UtcTime.parseMinuteStamp("2026-10-01T00:00:00Z")!!
        assertEquals("a clock set back cannot make lists younger", 0, s.ageDays(bundle))
    }

    @Test
    fun futureGeneratedAtWithin48HoursIsAgeZero() {
        val s = store()
        install(s, BundleSpec(generatedAt = "2026-10-18T20:00:00Z"))
        assertEquals(0, s.ageDays(s.current()!!))
    }

    @Test
    fun aClockFarBehindAfterARestartStillGivesAgeZeroNotNegative() {
        install(store(), day12)
        clock = UtcTime.parseMinuteStamp("2026-01-05T00:00:00Z")!!
        val restarted = store()
        assertEquals(0, restarted.ageDays(restarted.current()!!))
    }

    // Keysets --------------------------------------------------------------------------------------------------

    private fun keysetFiles(
        epoch: Int,
        spkiBase64: String,
        signer: TestSigner = TestKeys.recovery,
    ): Pair<ByteArray, ByteArray> {
        val json = """{"epoch":$epoch,"formatVersion":1,"keys":[{"spki":"$spkiBase64"}]}""".toByteArray()
        return json to signer.signLine(SignatureContext.KEYSET, json).toByteArray()
    }

    @Test
    fun keysetSignedByRecoveryKeyOpensANewEpochAndRevokesTheOldSigningKey() {
        val s = store()
        install(s, day10)
        val (json, sig) = keysetFiles(1, TestKeys.other.spkiBase64)
        assertTrue(s.applyKeyset(json, sig))
        // The old signing key S can no longer sign new manifests, even at epoch 1.
        assertEquals(RejectReason.BAD_SIGNATURE, reasonOf(install(s, day12.copy(epoch = 1, signer = TestKeys.signing))))
        // The new key signs epoch 1; the version part of the floor restarts.
        val earlier = day10.copy(generatedAt = "2026-10-05T03:12:00Z", epoch = 1, signer = TestKeys.other)
        assertTrue(install(s, earlier) is InstallResult.Installed)
        assertEquals(1, s.current()?.version?.epoch)
        // R still works.
        assertTrue(install(s, day12.copy(epoch = 1, signer = TestKeys.recovery)) is InstallResult.Installed)
        // Epoch 0 manifests are now rollbacks, whatever their date.
        assertEquals(
            RejectReason.ROLLBACK,
            reasonOf(install(s, BundleSpec(generatedAt = "2026-10-17T03:12:00Z", signer = TestKeys.recovery))),
        )
        // And it survives a restart.
        assertEquals(1, store().current()?.version?.epoch)
        assertEquals(
            RejectReason.ROLLBACK,
            reasonOf(install(store(), BundleSpec(generatedAt = "2026-10-17T03:12:00Z", signer = TestKeys.recovery))),
        )
    }

    @Test
    fun keysetThatIsNotFromRecoveryKeyOrNotNewerIsIgnored() {
        val s = store()
        val (json, sigS) = keysetFiles(1, TestKeys.other.spkiBase64, TestKeys.signing)
        assertFalse(s.applyKeyset(json, sigS))
        val (json2, sigR) = keysetFiles(1, TestKeys.other.spkiBase64)
        assertTrue(s.applyKeyset(json2, sigR))
        assertFalse("same epoch again", s.applyKeyset(json2, sigR))
        val (explicitJson, explicitSig) = keysetFiles(2, TestKeys.EXPLICIT_PARAMS_SPKI)
        assertFalse(s.applyKeyset(explicitJson, explicitSig))
    }

    @Test
    fun installedEpochZeroBundleStaysUsableAfterAKeyset() {
        val s = store()
        install(s, day12)
        val (json, sig) = keysetFiles(1, TestKeys.other.spkiBase64)
        assertTrue(s.applyKeyset(json, sig))
        assertEquals(versionOf(day12.generatedAt), store().current()?.version?.bundle)
    }

    // Embedded bundle ------------------------------------------------------------------------------------------

    private fun embeddedFrom(directory: File) = EmbeddedBundleSource { name ->
        File(directory, name).takeIf { it.isFile }?.inputStream()
    }

    private fun embeddedDir(spec: BundleSpec) =
        BundleBuilder.build(File(folder.root, "assets-${spec.generatedAt}"), spec).directory

    @Test
    fun embeddedBundleIsVerifiedAndInstalledWhenNothingIsInstalled() {
        val s = store(embedded = embeddedFrom(embeddedDir(day10)))
        assertEquals(versionOf(day10.generatedAt), s.current()?.version?.bundle)
        assertEquals(versionOf(day10.generatedAt), store().current()?.version?.bundle)
    }

    @Test
    fun embeddedBundleSignedWithAnUnpinnedKeyIsIgnored() {
        val s = store(embedded = embeddedFrom(embeddedDir(day10.copy(signer = TestKeys.other))))
        assertNull(s.current())
        assertTrue(File(root, "bundles").list()!!.isEmpty())
    }

    @Test
    fun missingEmbeddedFilesAreIgnored() {
        assertNull(store(embedded = EmbeddedBundleSource { null }).current())
    }

    @Test
    fun tamperedEmbeddedFileIsIgnored() {
        val dir = embeddedDir(day10)
        File(dir, BundleFile.ALLOWLIST.path).appendBytes(ByteArray(8))
        assertNull(store(embedded = embeddedFrom(dir)).current())
    }

    @Test
    fun newerEmbeddedBundleReplacesAnOlderInstalledOne() {
        install(store(), day10)
        val s = store(embedded = embeddedFrom(embeddedDir(day12)))
        assertEquals(versionOf(day12.generatedAt), s.current()?.version?.bundle)
    }

    @Test
    fun olderEmbeddedBundleDoesNotReplaceTheInstalledOne() {
        install(store(), day12)
        val s = store(embedded = embeddedFrom(embeddedDir(day10)))
        assertEquals(versionOf(day12.generatedAt), s.current()?.version?.bundle)
    }

    @Test
    fun embeddedBundleIsTheFloorForDownloads() {
        val s = store(embedded = embeddedFrom(embeddedDir(day12)))
        assertNotNull(s.current())
        assertEquals(RejectReason.ROLLBACK, reasonOf(install(s, day10)))
        assertEquals(InstallResult.UpToDate, install(s, day12))
    }

    @Test
    fun renamesAreMadeDurableByFsyncOfBundlesThenRoot() {
        val s = store()
        install(s, day10)
        assertEquals(listOf("bundles", "reputation"), synced)
    }

    @Test
    fun testKeysInANonDebugBuildTrustNothing() {
        assertFalse("this test assumes the test keys are pinned", PinnedKeys.KEYS_ARE_PRODUCTION)
        install(store(), day10)
        val release = ReputationStore(root, embeddedFrom(embeddedDir(day12)), { clock }, directorySync = {})
        assertNull("installed and embedded bundles are both ignored", release.current())
        val candidate = BundleBuilder.build(release.newCandidateDirectory(), day12).directory
        assertEquals(RejectReason.BAD_SIGNATURE, reasonOf(release.install(candidate)))
        assertNull(release.current())
        assertEquals(versionOf(day10.generatedAt), store().current()?.version?.bundle)
    }
}
