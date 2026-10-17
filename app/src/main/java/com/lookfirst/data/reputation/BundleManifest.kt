package com.lookfirst.data.reputation

private const val KIB = 1024L
private const val MIB = KIB * KIB
private const val SUPPORTED_FORMAT_VERSION = 1L
private const val MAX_FILES = 32
private const val MAX_PATH_LENGTH = 64
private const val SHA256_HEX_LENGTH = 64
private const val MAX_EPOCH = 1_000_000L

/** Why a bundle (or keyset) was not adopted. One enum value is all that is remembered and shown. */
enum class RejectReason {
    NETWORK,
    HTTP,
    TOO_LARGE,
    BAD_SIGNATURE,
    BAD_MANIFEST,
    FUTURE_DATE,
    ROLLBACK,
    HASH_MISMATCH,
    BAD_FORMAT,
    VARIATION,
    APP_TOO_OLD,
    STORAGE,
}

/** The fixed file names of a bundle with their size caps (section 2 and 3 of the design document). */
internal enum class BundleFile(val path: String, val maxSize: Long, val required: Boolean) {
    PHISHING_INDEX("reputation-phishing-database.idx", 16 * MIB, true),
    ALLOWLIST("allowlist.idx", 2 * MIB, true),
    PUBLIC_SUFFIX_LIST("public-suffix-list.txt", 1 * MIB, true),
    RDAP("rdap-dns.json", 512 * KIB, false),
    BRANDS("brands.txt", 256 * KIB, false),
    NOTICE("NOTICE.txt", 16 * KIB, false),
    ;

    companion object {
        fun forPath(path: String): BundleFile? = entries.firstOrNull { it.path == path }
    }
}

internal object BundleLimits {
    const val MANIFEST_MAX_BYTES = 64 * KIB
    const val TOTAL_MAX_BYTES = 24 * MIB
    const val MANIFEST_FILE = "manifest.json"
    const val SIGNATURE_FILE = "manifest.sig"

    /** Whole minutes since 2026-01-01T00:00:00Z is the bundle version. */
    val versionOrigin: Long = UtcTime.midnight(2026, 1, 1)

    /** A `generatedAt` later than the clock plus this is refused. */
    const val MAX_FUTURE_MILLIS = 48 * UtcTime.HOUR
}

/** Position of a bundle in the anti-rollback order: key epoch first, then version. */
internal data class BundleVersion(val epoch: Int, val bundle: Long) : Comparable<BundleVersion> {
    override fun compareTo(other: BundleVersion): Int =
        compareValuesBy(this, other, BundleVersion::epoch, BundleVersion::bundle)

    val directoryName: String get() = "$epoch-$bundle"
}

internal class ManifestFile(val path: String, val size: Long, val sha256: String, val entries: Long?)

internal class BundleManifest(
    val keyEpoch: Int,
    val bundleVersion: Long,
    val generatedAtMillis: Long,
    val files: List<ManifestFile>,
) {
    val version: BundleVersion get() = BundleVersion(keyEpoch, bundleVersion)

    fun fileFor(file: BundleFile): ManifestFile? = files.firstOrNull { it.path == file.path }
}

internal sealed interface ManifestParse {
    class Ok(val manifest: BundleManifest) : ManifestParse

    class Bad(val reason: RejectReason) : ManifestParse
}

/**
 * Reads the manifest. Called only on bytes whose signature was verified first. Shape and field rules of section 3;
 * the clock-dependent rules (future date) and the comparison with the floor are the verifier's.
 */
@Suppress("ReturnCount", "ComplexCondition") // a flat list of independent shape checks
internal object ManifestParser {
    fun parse(bytes: ByteArray): ManifestParse {
        val root = StrictJson.parse(bytes) as? Json.Obj ?: return bad()
        val format = (root.fields["formatVersion"] as? Json.Num)?.value ?: return bad()
        if (format > SUPPORTED_FORMAT_VERSION) return ManifestParse.Bad(RejectReason.APP_TOO_OLD)
        if (format < SUPPORTED_FORMAT_VERSION) return bad()
        val epoch = (root.fields["keyEpoch"] as? Json.Num)?.value
        val version = (root.fields["bundleVersion"] as? Json.Num)?.value
        val stamp = (root.fields["generatedAt"] as? Json.Str)?.value?.let { UtcTime.parseMinuteStamp(it) }
        val files = parseFiles(root.fields["files"])
        if (epoch == null || epoch !in 0..MAX_EPOCH || version == null || stamp == null || files == null) return bad()
        val derived = (stamp - BundleLimits.versionOrigin) / UtcTime.MINUTE
        if (stamp < BundleLimits.versionOrigin || version != derived) return bad()
        return ManifestParse.Ok(BundleManifest(epoch.toInt(), version, stamp, files))
    }

    private fun bad() = ManifestParse.Bad(RejectReason.BAD_MANIFEST)

    private fun parseFiles(node: Json?): List<ManifestFile>? {
        val items = (node as? Json.Arr)?.items ?: return null
        if (items.isEmpty() || items.size > MAX_FILES) return null
        val files = items.map { parseFile(it) ?: return null }
        return if (files.map { it.path }.toSet().size == files.size) files else null
    }

    private fun parseFile(node: Json): ManifestFile? {
        val fields = (node as? Json.Obj)?.fields ?: return null
        val path = (fields["path"] as? Json.Str)?.value ?: return null
        val size = (fields["size"] as? Json.Num)?.value ?: return null
        val sha = (fields["sha256"] as? Json.Str)?.value ?: return null
        val entriesNode = fields["entries"]
        val entries = (entriesNode as? Json.Num)?.value
        val valid = isValidPath(path) &&
            size >= 0 &&
            Hex.isLowerHex(sha, SHA256_HEX_LENGTH) &&
            (entriesNode == null || (entries != null && entries >= 0))
        return if (valid) ManifestFile(path, size, sha, entries) else null
    }

    /**
     * `[a-zA-Z0-9._-]{1,64}`, and never `.` or `..`: no slash, no traversal. The design text says lowercase only,
     * but its own `NOTICE.txt` has capitals (DECISIONS.md, LF-23).
     */
    private fun isValidPath(path: String): Boolean = path.length in 1..MAX_PATH_LENGTH &&
        path != "." &&
        path != ".." &&
        path.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '.' || it == '_' || it == '-' }
}
