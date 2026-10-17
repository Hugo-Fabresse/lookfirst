package com.lookfirst.data.reputation

import java.io.File
import java.io.IOException

private const val STATE_MAX_BYTES = 16 * 1024L
private const val MAX_STATE_KEYS = 8
private const val SHA256_HEX_LENGTH = 64

/**
 * What the store remembers besides the installed files (app-private, outside backups): the highest `(epoch,
 * version)` ever accepted (so removing a bad install cannot lower the floor), the latest validated `generatedAt`
 * (reference clock for list age), the accepted keyset, and the last failure with the rejected manifest hash.
 */
internal data class StoreState(
    val highest: BundleVersion? = null,
    val maxGeneratedAtMillis: Long = 0L,
    val keysetEpoch: Int = 0,
    val keysetKeys: List<ByteArray> = emptyList(),
    val lastSuccessMillis: Long? = null,
    val lastFailure: RejectReason? = null,
    val lastFailureMillis: Long? = null,
    val rejectedManifestSha: String? = null,
) {
    fun serialize(): String = buildString {
        highest?.let { appendLine("highest=${it.epoch}:${it.bundle}") }
        appendLine("maxGeneratedAt=$maxGeneratedAtMillis")
        appendLine("keysetEpoch=$keysetEpoch")
        keysetKeys.forEach { appendLine("keysetKey=${Hex.encode(it)}") }
        lastSuccessMillis?.let { appendLine("lastSuccess=$it") }
        if (lastFailure != null &&
            lastFailureMillis != null
        ) {
            appendLine("lastFailure=${lastFailure.name}:$lastFailureMillis")
        }
        rejectedManifestSha?.let { appendLine("rejectedSha=$it") }
    }

    companion object {
        /** Tolerant read: a missing or damaged file is an empty state (nothing is trusted from it beyond floors). */
        fun read(file: File): StoreState = try {
            if (!file.isFile || file.length() > STATE_MAX_BYTES) {
                StoreState()
            } else {
                parse(file.readText(Charsets.US_ASCII))
            }
        } catch (@Suppress("SwallowedException") ignored: IOException) {
            StoreState()
        }

        fun parse(text: String): StoreState {
            val values = text.lineSequence().mapNotNull { line ->
                val cut = line.indexOf('=')
                if (cut > 0) line.substring(0, cut) to line.substring(cut + 1) else null
            }.toList()
            fun first(key: String) = values.firstOrNull { it.first == key }?.second
            val failure = first("lastFailure")?.split(':')
            return StoreState(
                highest = first("highest")?.split(':')?.let(::versionOf),
                maxGeneratedAtMillis = first("maxGeneratedAt")?.toLongOrNull() ?: 0L,
                keysetEpoch = first("keysetEpoch")?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                keysetKeys = values.filter { it.first == "keysetKey" }.take(MAX_STATE_KEYS)
                    .mapNotNull { Hex.decodeLower(it.second) },
                lastSuccessMillis = first("lastSuccess")?.toLongOrNull(),
                lastFailure = failure?.getOrNull(0)?.let { name ->
                    RejectReason.entries.firstOrNull { it.name == name }
                },
                lastFailureMillis = failure?.getOrNull(1)?.toLongOrNull(),
                rejectedManifestSha = first("rejectedSha")?.takeIf { Hex.isLowerHex(it, SHA256_HEX_LENGTH) },
            )
        }

        private fun versionOf(parts: List<String>): BundleVersion? {
            val epoch = parts.getOrNull(0)?.toIntOrNull()
            val bundle = parts.getOrNull(1)?.toLongOrNull()
            return if (parts.size == 2 && epoch != null && bundle != null) BundleVersion(epoch, bundle) else null
        }
    }
}
