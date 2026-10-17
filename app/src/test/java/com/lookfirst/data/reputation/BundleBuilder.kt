package com.lookfirst.data.reputation

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Fixed "now" of the tests: 2026-10-17T12:00:00Z. */
internal val NOW: Long = UtcTime.parseMinuteStamp("2026-10-17T12:00:00Z")!!

internal val DEFAULT_PSL = listOf("# ICANN", "com", "net", "uk", "co.uk", "#PRIVATE", "blogspot.com")

/**
 * What a test bundle contains. Names in [phishing] and [allowlist] are `h:host` or `d:domain`.
 * The overrides produce bundles that are validly signed but wrong in one way.
 */
internal data class BundleSpec(
    val generatedAt: String = "2026-10-17T03:12:00Z",
    val epoch: Int = 0,
    val phishing: List<String> = listOf("h:evil.example.com", "d:phish.net", "d:bad.blogspot.com"),
    val allowlist: List<String> = listOf("d:good.com", "d:example.com"),
    val pslLines: List<String> = DEFAULT_PSL,
    val signer: TestSigner = TestKeys.signing,
    val formatVersion: Int = 1,
    val bundleVersionOverride: Long? = null,
    val declaredSizeOverride: Map<String, Long> = emptyMap(),
    val pslShaInIndex: String? = null,
    val phishingSourceId: Int = SourceIds.PHISHING_DATABASE,
    val extraManifestFields: String = "",
    val phishingKeys: List<Long>? = null,
    val allowlistKeys: List<Long>? = null,
)

internal class BuiltBundle(val directory: File, val spec: BundleSpec) {
    val manifestFile get() = File(directory, "manifest.json")
    val signatureFile get() = File(directory, "manifest.sig")

    fun file(kind: BundleFile) = File(directory, kind.path)
}

internal object BundleBuilder {
    fun version(generatedAt: String): Long =
        (UtcTime.parseMinuteStamp(generatedAt)!! - BundleLimits.versionOrigin) / UtcTime.MINUTE

    fun build(directory: File, spec: BundleSpec = BundleSpec()): BuiltBundle {
        directory.mkdirs()
        val psl = (spec.pslLines.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8)
        val pslSha = Hex.encode(sha256(psl))
        val indexPslSha = spec.pslShaInIndex ?: pslSha
        val phishingKeys = spec.phishingKeys ?: keysOf(spec.phishing)
        val allowlistKeys = spec.allowlistKeys ?: keysOf(spec.allowlist)
        val phishing = indexOfKeys(spec.phishingSourceId, phishingKeys, indexPslSha)
        val allowlist = indexOfKeys(SourceIds.ALLOWLIST, allowlistKeys, indexPslSha)
        File(directory, BundleFile.PHISHING_INDEX.path).writeBytes(phishing)
        File(directory, BundleFile.ALLOWLIST.path).writeBytes(allowlist)
        File(directory, BundleFile.PUBLIC_SUFFIX_LIST.path).writeBytes(psl)
        val entries = mapOf(
            BundleFile.PHISHING_INDEX.path to Triple(phishing, phishingKeys.size, true),
            BundleFile.ALLOWLIST.path to Triple(allowlist, allowlistKeys.size, true),
            BundleFile.PUBLIC_SUFFIX_LIST.path to Triple(psl, 0, false),
        )
        val files = entries.entries.joinToString(",") { (path, value) ->
            val (bytes, count, hasEntries) = value
            val size = spec.declaredSizeOverride[path] ?: bytes.size.toLong()
            val extra = if (hasEntries) ""","entries":$count""" else ""
            """{"path":"$path","sha256":"${Hex.encode(sha256(bytes))}","size":$size$extra}"""
        }
        val version = spec.bundleVersionOverride ?: version(spec.generatedAt)
        // Sorted keys, no insignificant whitespace, like the pipeline.
        val manifest = """{"bundleVersion":$version,"files":[$files],"formatVersion":${spec.formatVersion},""" +
            """"generatedAt":"${spec.generatedAt}","keyEpoch":${spec.epoch}${spec.extraManifestFields}}"""
        File(directory, "manifest.json").writeBytes(manifest.toByteArray(Charsets.UTF_8))
        sign(directory, spec.signer)
        return BuiltBundle(directory, spec)
    }

    /** Re-signs whatever `manifest.json` currently contains. */
    fun sign(directory: File, signer: TestSigner) {
        val bytes = File(directory, "manifest.json").readBytes()
        File(directory, "manifest.sig").writeText(signer.signLine(SignatureContext.MANIFEST, bytes), Charsets.US_ASCII)
    }

    fun keysOf(names: List<String>): List<Long> =
        names.map { Lfix.keyOf(it[0], it.substring(2)) }.sortedWith { a, b -> java.lang.Long.compareUnsigned(a, b) }

    fun indexOfKeys(sourceId: Int, keys: List<Long>, pslSha: String): ByteArray {
        val out = ByteArrayOutputStream()
        val header = ByteBuffer.allocate(64).order(ByteOrder.BIG_ENDIAN)
        header.put("LFIX".toByteArray(Charsets.US_ASCII))
        header.putShort(1)
        header.putShort(sourceId.toShort())
        header.putInt(keys.size)
        header.position(32)
        header.put(Hex.decodeLower(pslSha)!!)
        out.write(header.array())
        val body = ByteBuffer.allocate(keys.size * 8).order(ByteOrder.BIG_ENDIAN)
        keys.forEach { body.putLong(it) }
        out.write(body.array())
        return out.toByteArray()
    }

    /** `count` sorted pseudo-random keys, for variation tests. */
    fun syntheticKeys(count: Int, seed: Int): List<Long> {
        val random = java.util.Random(seed.toLong())
        val unique = generateSequence { random.nextLong() }.distinct().take(count)
        return unique.sortedWith { a, b -> java.lang.Long.compareUnsigned(a, b) }.toList()
    }
}
