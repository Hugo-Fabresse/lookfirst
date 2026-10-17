package com.lookfirst.data.reputation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BundleVerifierTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val ring = PinnedKeys.epochZeroRing()
    private var counter = 0

    private fun build(spec: BundleSpec = BundleSpec()): BuiltBundle =
        BundleBuilder.build(File(folder.root, "b${counter++}"), spec)

    private fun verify(
        bundle: BuiltBundle,
        now: Long = NOW,
        floor: BundleVersion? = null,
        baseline: Baseline? = null,
    ) = BundleVerifier.verify(bundle.directory, ring, now, floor, baseline)

    private fun reasonOf(outcome: VerifyOutcome): RejectReason? = (outcome as? VerifyOutcome.Rejected)?.reason

    private fun baselineOf(
        bundle: BuiltBundle,
        phishing: Long,
        allowlist: Long,
        generatedAt: String = bundle.spec.generatedAt,
    ) = Baseline(
        BundleVersion(bundle.spec.epoch, BundleBuilder.version(generatedAt)),
        UtcTime.parseMinuteStamp(generatedAt)!!,
        bundle.directory,
        phishing,
        allowlist,
    )

    @Test
    fun validBundleIsAccepted() {
        val outcome = verify(build())
        assertTrue(outcome is VerifyOutcome.Accepted)
        outcome as VerifyOutcome.Accepted
        assertEquals(BundleBuilder.version("2026-10-17T03:12:00Z"), outcome.manifest.bundleVersion)
        assertEquals(64, outcome.manifestSha256.length)
    }

    @Test
    fun recoveryKeySignedBundleIsAccepted() {
        assertTrue(verify(build(BundleSpec(signer = TestKeys.recovery))) is VerifyOutcome.Accepted)
    }

    @Test
    fun wrongOrUnknownKeyIsBadSignature() {
        assertEquals(RejectReason.BAD_SIGNATURE, reasonOf(verify(build(BundleSpec(signer = TestKeys.other)))))
    }

    @Test
    fun manifestAlteredByOneByteIsBadSignature() {
        val bundle = build()
        val bytes = bundle.manifestFile.readBytes()
        bytes[bytes.size / 2] = (bytes[bytes.size / 2] + 1).toByte()
        bundle.manifestFile.writeBytes(bytes)
        assertEquals(RejectReason.BAD_SIGNATURE, reasonOf(verify(bundle)))
    }

    @Test
    fun missingSignatureOrManifestIsRejected() {
        val noSig = build().also { it.signatureFile.delete() }
        assertEquals(RejectReason.BAD_SIGNATURE, reasonOf(verify(noSig)))
        val noManifest = build().also { it.manifestFile.delete() }
        assertEquals(RejectReason.BAD_SIGNATURE, reasonOf(verify(noManifest)))
    }

    @Test
    fun oversizedManifestIsRejectedBeforeAnythingIsRead() {
        val bundle = build()
        bundle.manifestFile.writeBytes(ByteArray(70 * 1024) { ' '.code.toByte() })
        assertEquals(RejectReason.TOO_LARGE, reasonOf(verify(bundle)))
    }

    @Test
    fun validSignatureOverGarbageIsBadManifest() {
        val bundle = build()
        bundle.manifestFile.writeText("""{"a":1,"a":2}""")
        BundleBuilder.sign(bundle.directory, TestKeys.signing)
        assertEquals(RejectReason.BAD_MANIFEST, reasonOf(verify(bundle)))
    }

    @Test
    fun unsupportedFormatVersionMeansAppTooOld() {
        assertEquals(RejectReason.APP_TOO_OLD, reasonOf(verify(build(BundleSpec(formatVersion = 2)))))
        assertEquals(RejectReason.BAD_MANIFEST, reasonOf(verify(build(BundleSpec(formatVersion = 0)))))
    }

    @Test
    fun unknownExtraManifestFieldsAreIgnored() {
        val spec =
            BundleSpec(extraManifestFields = ""","sources":[{"id":"x","url":"http://insecure.example"}],"note":"hi"""")
        assertTrue(verify(build(spec)) is VerifyOutcome.Accepted)
    }

    @Test
    fun versionMustBeTheMinutesOfGeneratedAt() {
        val spec = BundleSpec(bundleVersionOverride = BundleBuilder.version("2026-10-17T03:12:00Z") + 1)
        assertEquals(RejectReason.BAD_MANIFEST, reasonOf(verify(build(spec))))
        assertEquals(
            RejectReason.BAD_MANIFEST,
            reasonOf(verify(build(BundleSpec(bundleVersionOverride = 1_000_000_000L)))),
        )
    }

    @Test
    fun generatedAtBeforeTheVersionOriginIsRejected() {
        assertEquals(
            RejectReason.BAD_MANIFEST,
            reasonOf(verify(build(BundleSpec(generatedAt = "2025-12-31T23:59:00Z")))),
        )
    }

    @Test
    fun futureDateBeyond48HoursIsRejected() {
        val inside = BundleSpec(generatedAt = "2026-10-19T11:59:00Z")
        val outside = BundleSpec(generatedAt = "2026-10-19T12:01:00Z")
        assertTrue(verify(build(inside)) is VerifyOutcome.Accepted)
        assertEquals(RejectReason.FUTURE_DATE, reasonOf(verify(build(outside))))
    }

    @Test
    fun floorIsStrictlyLexicographicOnEpochThenVersion() {
        val bundle = build()
        val v = BundleBuilder.version(bundle.spec.generatedAt)
        assertEquals(RejectReason.ROLLBACK, reasonOf(verify(bundle, floor = BundleVersion(0, v + 1))))
        assertEquals(RejectReason.ROLLBACK, reasonOf(verify(bundle, floor = BundleVersion(0, v))))
        assertTrue(verify(bundle, floor = BundleVersion(0, v - 1)) is VerifyOutcome.Accepted)
    }

    @Test
    fun equalToInstalledIsUpToDate() {
        val installed = build()
        val candidate = build()
        val baseline = baselineOf(installed, 3, 2)
        assertEquals(VerifyOutcome.UpToDate, verify(candidate, floor = baseline.version, baseline = baseline))
    }

    @Test
    fun epochBelowTheAcceptedOneIsRollbackAndAboveIsRejected() {
        val keyRing = KeyRing(1, listOf(TestKeys.signing.spki))
        val old = build(BundleSpec(epoch = 0))
        assertEquals(RejectReason.ROLLBACK, reasonOf(BundleVerifier.verify(old.directory, keyRing, NOW, null, null)))
        val future = build(BundleSpec(epoch = 2))
        assertEquals(
            RejectReason.BAD_MANIFEST,
            reasonOf(BundleVerifier.verify(future.directory, keyRing, NOW, null, null)),
        )
        val current = build(BundleSpec(epoch = 1))
        assertTrue(
            BundleVerifier.verify(
                current.directory,
                keyRing,
                NOW,
                BundleVersion(0, Long.MAX_VALUE / 2),
                null,
            ) is VerifyOutcome.Accepted,
        )
    }

    @Test
    fun truncatedFileIsAHashMismatch() {
        val bundle = build()
        val file = bundle.file(BundleFile.PHISHING_INDEX)
        file.writeBytes(file.readBytes().copyOf(file.length().toInt() - 8))
        assertEquals(RejectReason.HASH_MISMATCH, reasonOf(verify(bundle)))
    }

    @Test
    fun fileLongerThanDeclaredIsTooLarge() {
        val bundle = build()
        bundle.file(BundleFile.PHISHING_INDEX).appendBytes(ByteArray(8))
        assertEquals(RejectReason.TOO_LARGE, reasonOf(verify(bundle)))
    }

    @Test
    fun declaredSizeAboveTheCapIsTooLargeBeforeAnyHashing() {
        val spec = BundleSpec(declaredSizeOverride = mapOf(BundleFile.ALLOWLIST.path to 3L * 1024 * 1024))
        assertEquals(RejectReason.TOO_LARGE, reasonOf(verify(build(spec))))
    }

    @Test
    fun alteredFileWithSameSizeIsAHashMismatch() {
        val bundle = build()
        val file = bundle.file(BundleFile.PUBLIC_SUFFIX_LIST)
        val bytes = file.readBytes()
        bytes[0] = (bytes[0] + 1).toByte()
        file.writeBytes(bytes)
        assertEquals(RejectReason.HASH_MISMATCH, reasonOf(verify(bundle)))
    }

    @Test
    fun missingFileIsAHashMismatch() {
        val bundle = build()
        bundle.file(BundleFile.ALLOWLIST).delete()
        assertEquals(RejectReason.HASH_MISMATCH, reasonOf(verify(bundle)))
    }

    @Test
    fun filesMixedBetweenVersionsAreRejected() {
        val a = build(BundleSpec(generatedAt = "2026-10-16T03:12:00Z", phishing = listOf("h:one.example.com")))
        val b = build(BundleSpec(generatedAt = "2026-10-17T03:12:00Z", phishing = listOf("h:two.example.com")))
        a.file(BundleFile.PHISHING_INDEX).copyTo(b.file(BundleFile.PHISHING_INDEX), overwrite = true)
        assertEquals(RejectReason.HASH_MISMATCH, reasonOf(verify(b)))
    }

    @Test
    fun unorderedIndexIsBadFormat() {
        val keys = BundleBuilder.keysOf(listOf("h:a.example.com", "h:b.example.com", "h:c.example.com")).reversed()
        assertEquals(RejectReason.BAD_FORMAT, reasonOf(verify(build(BundleSpec(phishingKeys = keys)))))
    }

    @Test
    fun duplicateIndexEntryIsBadFormat() {
        val keys = BundleBuilder.keysOf(listOf("h:a.example.com", "h:b.example.com"))
        assertEquals(
            RejectReason.BAD_FORMAT,
            reasonOf(verify(build(BundleSpec(phishingKeys = listOf(keys[0], keys[0], keys[1]))))),
        )
    }

    @Test
    fun indexHeaderMustMatchManifestAndPsl() {
        val wrongPsl = "00".repeat(32)
        assertEquals(RejectReason.BAD_FORMAT, reasonOf(verify(build(BundleSpec(pslShaInIndex = wrongPsl)))))
        assertEquals(RejectReason.BAD_FORMAT, reasonOf(verify(build(BundleSpec(phishingSourceId = 7)))))
    }

    @Test
    fun indexWithBadMagicReservedBytesOrTrailingBytesIsBadFormat() {
        val dir = build()
        val file = dir.file(BundleFile.PHISHING_INDEX)
        val bytes = file.readBytes()
        val badMagic = bytes.copyOf().also { it[0] = 'X'.code.toByte() }
        val badReserved = bytes.copyOf().also { it[20] = 1 }
        listOf(badMagic, badReserved, bytes + byteArrayOf(1, 2, 3)).forEach { variant ->
            val tampered = build()
            tampered.file(BundleFile.PHISHING_INDEX).writeBytes(variant)
            rewriteManifestFor(tampered)
            assertEquals(RejectReason.BAD_FORMAT, reasonOf(verify(tampered)))
        }
    }

    /** Recomputes sizes and hashes in the manifest of a tampered bundle and re-signs it (a valid signer). */
    private fun rewriteManifestFor(bundle: BuiltBundle) {
        val files = listOf(
            BundleFile.PHISHING_INDEX,
            BundleFile.ALLOWLIST,
            BundleFile.PUBLIC_SUFFIX_LIST,
        ).joinToString(",") { kind ->
            val bytes = bundle.file(kind).readBytes()
            """{"path":"${kind.path}","sha256":"${Hex.encode(sha256(bytes))}","size":${bytes.size}}"""
        }
        val v = BundleBuilder.version(bundle.spec.generatedAt)
        bundle.manifestFile.writeText(
            """{"bundleVersion":$v,"files":[$files],"formatVersion":1,""" +
                """"generatedAt":"${bundle.spec.generatedAt}","keyEpoch":0}""",
        )
        BundleBuilder.sign(bundle.directory, TestKeys.signing)
    }

    @Test
    fun pathTraversalInTheManifestIsRejected() {
        val bundle = build()
        val text = bundle.manifestFile.readText().replace("allowlist.idx", "../allowlist.idx")
        bundle.manifestFile.writeText(text)
        BundleBuilder.sign(bundle.directory, TestKeys.signing)
        assertEquals(RejectReason.BAD_MANIFEST, reasonOf(verify(bundle)))
    }

    @Test
    fun duplicatePathsInTheManifestAreRejected() {
        val bundle = build()
        val text = bundle.manifestFile.readText().replace("public-suffix-list.txt", "allowlist.idx")
        bundle.manifestFile.writeText(text)
        BundleBuilder.sign(bundle.directory, TestKeys.signing)
        assertEquals(RejectReason.BAD_MANIFEST, reasonOf(verify(bundle)))
    }

    @Test
    fun allowlistedDomainInTheNewIndexIsRejected() {
        val spec = BundleSpec(phishing = listOf("d:good.com", "d:phish.net"), allowlist = listOf("d:good.com"))
        assertEquals(RejectReason.VARIATION, reasonOf(verify(build(spec))))
    }

    @Test
    fun allowlistedHostEntryIsNotACollision() {
        val spec = BundleSpec(phishing = listOf("h:sub.good.com"), allowlist = listOf("d:good.com"))
        assertTrue(verify(build(spec)) is VerifyOutcome.Accepted)
    }

    @Test
    fun indexVariationAboveTheDailyToleranceIsRejected() {
        val installed = build(BundleSpec(generatedAt = "2026-10-16T12:00:00Z"))
        val baseline = baselineOf(installed, 1_000, 100)
        fun candidate(count: Int) = build(
            BundleSpec(
                phishingKeys = BundleBuilder.syntheticKeys(count, 1),
                allowlistKeys = BundleBuilder.syntheticKeys(100, 2),
            ),
        )
        // Installed bundle is 1 day old: tolerance 12 %.
        assertTrue(verify(candidate(1_110), baseline = baseline) is VerifyOutcome.Accepted)
        assertTrue(verify(candidate(890), baseline = baseline) is VerifyOutcome.Accepted)
        assertEquals(RejectReason.VARIATION, reasonOf(verify(candidate(1_130), baseline = baseline)))
        assertEquals(RejectReason.VARIATION, reasonOf(verify(candidate(500), baseline = baseline)))
    }

    @Test
    fun toleranceGrowsWithTheAgeOfTheInstalledBundleAndEndsAfter45Days() {
        val old20 = build(BundleSpec(generatedAt = "2026-09-27T12:00:00Z"))
        val baseline20 = baselineOf(old20, 1_000, 100)
        fun candidate(count: Int) = build(
            BundleSpec(
                phishingKeys = BundleBuilder.syntheticKeys(count, 3),
                allowlistKeys = BundleBuilder.syntheticKeys(100, 4),
            ),
        )
        // 20 days old: 10 + 2 * 20 = 50 %.
        assertTrue(verify(candidate(1_490), baseline = baseline20) is VerifyOutcome.Accepted)
        assertEquals(RejectReason.VARIATION, reasonOf(verify(candidate(1_510), baseline = baseline20)))
        val old60 = build(BundleSpec(generatedAt = "2026-08-18T12:00:00Z"))
        val baseline60 = baselineOf(old60, 1_000, 100)
        assertTrue(verify(candidate(5_000), baseline = baseline60) is VerifyOutcome.Accepted)
        assertEquals(10L, Variation.tolerancePercent(0))
        assertEquals(null, Variation.tolerancePercent(45))
    }

    @Test
    fun allowlistShrinkingIsAlsoVaried() {
        val installed = build(BundleSpec(generatedAt = "2026-10-16T12:00:00Z"))
        val baseline = baselineOf(installed, 3, 1_000)
        val candidate = build(BundleSpec(allowlistKeys = BundleBuilder.syntheticKeys(100, 5)))
        assertEquals(RejectReason.VARIATION, reasonOf(verify(candidate, baseline = baseline)))
    }

    @Test
    fun publicSuffixListLosingAnIcannRuleIsRejected() {
        val installed = build(BundleSpec(generatedAt = "2026-10-16T12:00:00Z"))
        val baseline = baselineOf(installed, 3, 2)
        val fewer = build(BundleSpec(pslLines = listOf("com", "net", "uk", "#PRIVATE", "blogspot.com")))
        assertEquals(RejectReason.VARIATION, reasonOf(verify(fewer, baseline = baseline)))
        val more =
            build(
                BundleSpec(
                    pslLines = listOf("# ICANN", "com", "net", "uk", "co.uk", "org", "#PRIVATE", "blogspot.com"),
                ),
            )
        assertTrue(verify(more, baseline = baseline) is VerifyOutcome.Accepted)
    }

    @Test
    fun publicSuffixListWithoutRulesOrWithNonAsciiIsBadFormat() {
        assertEquals(
            RejectReason.BAD_FORMAT,
            reasonOf(verify(build(BundleSpec(pslLines = listOf("# nothing", "#PRIVATE", "x.com"))))),
        )
        assertEquals(RejectReason.BAD_FORMAT, reasonOf(verify(build(BundleSpec(pslLines = listOf("com", "café"))))))
    }
}
