package com.lookfirst.data.reputation

import java.io.File
import java.io.IOException
import java.security.MessageDigest

private const val HASH_CHUNK = 64 * 1024
private const val BASE_VARIATION_PERCENT = 10L
private const val VARIATION_PERCENT_PER_DAY = 2L
private const val NO_LIMIT_AFTER_DAYS = 45L
private const val PERCENT = 100L
private const val PRIVATE_MARKER = "#PRIVATE"

/** The installed bundle the candidate is compared with (variation checks, "up to date"). */
internal class Baseline(
    val version: BundleVersion,
    val generatedAtMillis: Long,
    val directory: File,
    val phishingEntries: Long,
    val allowlistEntries: Long,
)

internal sealed interface SignedManifest {
    class Ok(val manifest: BundleManifest, val sha256: String) : SignedManifest

    class Failed(val reason: RejectReason) : SignedManifest
}

internal sealed interface VerifyOutcome {
    class Accepted(val manifest: BundleManifest, val manifestSha256: String) : VerifyOutcome

    data object UpToDate : VerifyOutcome

    class Rejected(val reason: RejectReason, val manifestSha256: String?) : VerifyOutcome
}

/**
 * Verifies a bundle directory in the order of the design document (section 6): signature, manifest shape,
 * anti-rollback, per-file size and SHA-256, per-file format, variation and allow-list. Parsing never precedes the
 * signature. Pure functions of the files, the keys and the clock passed in.
 */
internal object BundleVerifier {
    /** Steps 1 to 3 without the clock: signature over the exact bytes, then the manifest's own shape. */
    fun readSigned(directory: File, ring: KeyRing): SignedManifest {
        val signature = readBounded(File(directory, BundleLimits.SIGNATURE_FILE), SIGNATURE_MAX)
        val manifestFile = File(directory, BundleLimits.MANIFEST_FILE)
        val bytes = readBounded(manifestFile, BundleLimits.MANIFEST_MAX_BYTES)
        return when {
            bytes is Bounded.TooLarge || signature is Bounded.TooLarge -> SignedManifest.Failed(RejectReason.TOO_LARGE)
            signature !is Bounded.Data || bytes !is Bounded.Data -> SignedManifest.Failed(RejectReason.BAD_SIGNATURE)
            !SignatureVerifier.verify(SignatureContext.MANIFEST, bytes.bytes, signature.bytes, ring.manifestKeys) ->
                SignedManifest.Failed(RejectReason.BAD_SIGNATURE)
            else -> when (val parsed = ManifestParser.parse(bytes.bytes)) {
                is ManifestParse.Bad -> SignedManifest.Failed(parsed.reason)
                is ManifestParse.Ok -> SignedManifest.Ok(parsed.manifest, Hex.encode(sha256(bytes.bytes)))
            }
        }
    }

    /**
     * Steps 1 to 4 of the design document: signature, manifest shape, clock rules, anti-rollback and "up to date".
     * No file is read. `Accepted` means the manifest may be acted on (its files can be fetched).
     */
    fun precheck(
        directory: File,
        ring: KeyRing,
        nowMillis: Long,
        floor: BundleVersion?,
        baseline: Baseline?,
    ): VerifyOutcome {
        val signed = readSigned(directory, ring)
        if (signed !is SignedManifest.Ok) return rejected((signed as SignedManifest.Failed).reason, null)
        val manifest = signed.manifest
        val reason = ruleViolation(manifest, ring, nowMillis)
        return when {
            reason != null -> rejected(reason, signed.sha256)
            baseline != null && manifest.version == baseline.version -> VerifyOutcome.UpToDate
            floor != null && manifest.version <= floor -> rejected(RejectReason.ROLLBACK, signed.sha256)
            else -> VerifyOutcome.Accepted(manifest, signed.sha256)
        }
    }

    /**
     * Full install-time verification of [directory]. [floor] is the highest accepted `(epoch, version)` (null for
     * the embedded bundle), [baseline] the installed bundle (null if none).
     */
    fun verify(
        directory: File,
        ring: KeyRing,
        nowMillis: Long,
        floor: BundleVersion?,
        baseline: Baseline?,
    ): VerifyOutcome {
        val outcome = precheck(directory, ring, nowMillis, floor, baseline)
        if (outcome !is VerifyOutcome.Accepted) return outcome
        val failure = FileChecks.check(directory, outcome.manifest, baseline, nowMillis)
        return if (failure == null) outcome else rejected(failure, outcome.manifestSha256)
    }

    private fun rejected(reason: RejectReason, manifestSha: String?) = VerifyOutcome.Rejected(reason, manifestSha)

    /** Rules that need the key ring or the clock (design section 3). */
    private fun ruleViolation(manifest: BundleManifest, ring: KeyRing, nowMillis: Long): RejectReason? = when {
        manifest.keyEpoch < ring.epoch -> RejectReason.ROLLBACK
        manifest.keyEpoch > ring.epoch -> RejectReason.BAD_MANIFEST
        manifest.generatedAtMillis > nowMillis + BundleLimits.MAX_FUTURE_MILLIS -> RejectReason.FUTURE_DATE
        else -> declaredSizeViolation(manifest)
    }

    private fun declaredSizeViolation(manifest: BundleManifest): RejectReason? {
        if (BundleFile.entries.any { it.required && manifest.fileFor(it) == null }) return RejectReason.BAD_MANIFEST
        val known = manifest.files.mapNotNull { f -> BundleFile.forPath(f.path)?.let { it to f } }
        if (known.any { (kind, file) -> file.size > kind.maxSize }) return RejectReason.TOO_LARGE
        return if (known.sumOf { it.second.size } > BundleLimits.TOTAL_MAX_BYTES) RejectReason.TOO_LARGE else null
    }

    sealed interface Bounded {
        class Data(val bytes: ByteArray) : Bounded

        data object TooLarge : Bounded

        data object Missing : Bounded
    }

    private const val SIGNATURE_MAX = 256L

    fun readBounded(file: File, max: Long): Bounded = try {
        when {
            !file.isFile -> Bounded.Missing
            file.length() > max -> Bounded.TooLarge
            else -> Bounded.Data(file.readBytes())
        }
    } catch (@Suppress("SwallowedException") ignored: IOException) {
        Bounded.Missing
    }

    /** Lowercase SHA-256 of [file], streamed. Null if unreadable. */
    fun sha256Hex(file: File): String? = try {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(HASH_CHUNK)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        Hex.encode(digest.digest())
    } catch (@Suppress("SwallowedException") ignored: IOException) {
        null
    }

    /** ICANN-section rules of a PSL file: the lines before the `#PRIVATE` marker. */
    fun icannRules(lines: List<String>): Set<String> {
        val rules = HashSet<String>()
        for (raw in lines) {
            val line = raw.trim()
            if (line == PRIVATE_MARKER) break
            if (line.isNotEmpty() && line[0] != '#') rules += line
        }
        return rules
    }

    fun readPslLines(file: File): List<String>? {
        val data = readBounded(file, BundleFile.PUBLIC_SUFFIX_LIST.maxSize) as? Bounded.Data ?: return null
        if (data.bytes.any { it < 0 }) return null
        return String(data.bytes, Charsets.US_ASCII).split('\n')
    }
}

/** Allowed change of an index between two bundles, proportional to the age of the installed one (section 6, step 7). */
internal object Variation {
    /** Variation tolerance in percent for a bundle whose installed counterpart is [ageDays] old; null = no limit. */
    fun tolerancePercent(ageDays: Long): Long? =
        if (ageDays >= NO_LIMIT_AFTER_DAYS) null else BASE_VARIATION_PERCENT + VARIATION_PERCENT_PER_DAY * ageDays

    fun exceedsTolerance(oldCount: Long, newCount: Long, tolerancePercent: Long?): Boolean {
        if (tolerancePercent == null || oldCount <= 0) return false
        return Math.abs(newCount - oldCount) * PERCENT > oldCount * tolerancePercent
    }
}

/** Steps 5 to 7 of the design document: sizes and hashes, formats, variation. */
private object FileChecks {
    fun check(directory: File, manifest: BundleManifest, baseline: Baseline?, nowMillis: Long): RejectReason? {
        for (kind in BundleFile.entries) {
            val declared = manifest.fileFor(kind) ?: continue
            val failure = checkDigest(File(directory, kind.path), declared)
            if (failure != null) return failure
        }
        return checkFormats(directory, manifest) ?: checkVariation(directory, manifest, baseline, nowMillis)
    }

    private fun checkDigest(file: File, declared: ManifestFile): RejectReason? {
        if (!file.isFile) return RejectReason.HASH_MISMATCH
        val length = file.length()
        return when {
            length > declared.size -> RejectReason.TOO_LARGE
            length != declared.size -> RejectReason.HASH_MISMATCH
            BundleVerifier.sha256Hex(file) != declared.sha256 -> RejectReason.HASH_MISMATCH
            else -> null
        }
    }

    private fun checkFormats(directory: File, manifest: BundleManifest): RejectReason? {
        val psl = manifest.fileFor(BundleFile.PUBLIC_SUFFIX_LIST) ?: return RejectReason.BAD_MANIFEST
        val lines = BundleVerifier.readPslLines(File(directory, BundleFile.PUBLIC_SUFFIX_LIST.path))
        if (lines == null || BundleVerifier.icannRules(lines).isEmpty()) return RejectReason.BAD_FORMAT
        val phishing = indexFormat(directory, manifest, BundleFile.PHISHING_INDEX, SourceIds.PHISHING_DATABASE, psl)
        val allowlist = indexFormat(directory, manifest, BundleFile.ALLOWLIST, SourceIds.ALLOWLIST, psl)
        return phishing ?: allowlist ?: rdapFormat(directory, manifest)
    }

    /**
     * The optional RDAP bootstrap: strict JSON under its own bounds, every TLD and every server URL conforming
     * (`https`, no IP literal, no port, no userinfo). One bad URL refuses the bundle (section 6, step 6).
     */
    private fun rdapFormat(directory: File, manifest: BundleManifest): RejectReason? {
        if (manifest.fileFor(BundleFile.RDAP) == null) return null
        val data = BundleVerifier.readBounded(File(directory, BundleFile.RDAP.path), BundleFile.RDAP.maxSize)
        val parsed = (data as? BundleVerifier.Bounded.Data)?.let { RdapBootstrap.parseStrict(it.bytes) }
        return if (parsed == null) RejectReason.BAD_FORMAT else null
    }

    private fun indexFormat(
        directory: File,
        manifest: BundleManifest,
        kind: BundleFile,
        expectedSource: Int,
        psl: ManifestFile,
    ): RejectReason? {
        val declared = manifest.fileFor(kind) ?: return RejectReason.BAD_MANIFEST
        val header = Lfix.validate(File(directory, kind.path)) ?: return RejectReason.BAD_FORMAT
        val entriesOk = declared.entries == null || declared.entries == header.entryCount
        val ok = header.sourceId == expectedSource && header.pslSha256 == psl.sha256 && entriesOk
        return if (ok) null else RejectReason.BAD_FORMAT
    }

    private fun checkVariation(
        directory: File,
        manifest: BundleManifest,
        baseline: Baseline?,
        nowMillis: Long,
    ): RejectReason? {
        val phishingFile = File(directory, BundleFile.PHISHING_INDEX.path)
        val allowlistFile = File(directory, BundleFile.ALLOWLIST.path)
        if (Lfix.intersects(allowlistFile, phishingFile)) return RejectReason.VARIATION
        if (baseline == null) return null
        val ageDays = Math.max(
            0L,
            (Math.max(nowMillis, baseline.generatedAtMillis) - baseline.generatedAtMillis) / UtcTime.DAY,
        )
        val tolerance = Variation.tolerancePercent(ageDays)
        val phishingNew =
            manifest.fileFor(BundleFile.PHISHING_INDEX)?.entries ?: Lfix.readHeader(phishingFile)?.entryCount
        val allowlistNew = manifest.fileFor(BundleFile.ALLOWLIST)?.entries ?: Lfix.readHeader(allowlistFile)?.entryCount
        val tooBig = phishingNew == null ||
            allowlistNew == null ||
            Variation.exceedsTolerance(baseline.phishingEntries, phishingNew, tolerance) ||
            Variation.exceedsTolerance(baseline.allowlistEntries, allowlistNew, tolerance)
        return if (tooBig || pslLosesRules(directory, baseline)) RejectReason.VARIATION else null
    }

    /** No rule of the installed list's ICANN section may disappear. */
    private fun pslLosesRules(directory: File, baseline: Baseline): Boolean {
        val old =
            BundleVerifier.readPslLines(File(baseline.directory, BundleFile.PUBLIC_SUFFIX_LIST.path)) ?: return false
        val new = BundleVerifier.readPslLines(File(directory, BundleFile.PUBLIC_SUFFIX_LIST.path)) ?: return true
        return !BundleVerifier.icannRules(new).containsAll(BundleVerifier.icannRules(old))
    }
}
