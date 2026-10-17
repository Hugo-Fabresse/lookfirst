package com.lookfirst.data.reputation

import java.io.File
import java.io.IOException

private const val STATE_MAX_BYTES = 16 * 1024L
private const val MAX_SOURCES = 16
private const val VALIDATOR_MAX_LENGTH = 40

/** What the store remembers about one source besides its index file. */
internal data class SourceState(
    val fetchedAtMillis: Long? = null,
    /** The publisher's own `Last-Modified` date, sent back as `If-Modified-Since`. Never anything else. */
    val validator: String? = null,
    val failure: ListFailure? = null,
    val failureAtMillis: Long? = null,
)

/**
 * App-private bookkeeping (outside backups): per source the time of the last successful check, the validator and
 * the last failure, plus the latest time ever seen (a reference clock for list age that a clock set back cannot
 * rewind). A missing or damaged file is an empty state: nothing is trusted from it beyond dates.
 */
internal data class ListsState(val maxSeenMillis: Long = 0L, val sources: Map<Int, SourceState> = emptyMap()) {
    fun of(id: Int): SourceState = sources[id] ?: SourceState()

    fun with(id: Int, state: SourceState): ListsState = copy(sources = sources + (id to state))

    fun serialize(): String = buildString {
        appendLine("maxSeen=$maxSeenMillis")
        for ((id, state) in sources) {
            state.fetchedAtMillis?.let { appendLine("$id.fetchedAt=$it") }
            state.validator?.let { appendLine("$id.validator=$it") }
            if (state.failure != null && state.failureAtMillis != null) {
                appendLine("$id.failure=${state.failure.name}:${state.failureAtMillis}")
            }
        }
    }

    companion object {
        /**
         * A `Last-Modified` value is stored and sent back only if it is short and made of letters, digits, spaces,
         * commas and colons: a date, never free text that could carry an identifier.
         */
        fun isPlausibleValidator(text: String): Boolean = text.length in 1..VALIDATOR_MAX_LENGTH &&
            text.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == ' ' || it == ',' || it == ':' }

        fun read(file: File): ListsState = try {
            if (!file.isFile || file.length() > STATE_MAX_BYTES) {
                ListsState()
            } else {
                parse(file.readText(Charsets.US_ASCII))
            }
        } catch (@Suppress("SwallowedException") ignored: IOException) {
            ListsState()
        }

        fun parse(text: String): ListsState {
            var maxSeen = 0L
            val sources = LinkedHashMap<Int, SourceState>()
            for (line in text.lineSequence()) {
                val cut = line.indexOf('=')
                if (cut <= 0) continue
                val key = line.substring(0, cut)
                val value = line.substring(cut + 1)
                if (key == "maxSeen") {
                    maxSeen = value.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                } else {
                    remember(sources, key, value)
                }
            }
            return ListsState(maxSeen, sources)
        }

        private fun remember(sources: MutableMap<Int, SourceState>, key: String, value: String) {
            val id = key.substringBefore('.').toIntOrNull() ?: return
            if (sources.size >= MAX_SOURCES && id !in sources) return
            sources[id] = apply(sources[id] ?: SourceState(), key.substringAfter('.', ""), value)
        }

        private fun apply(state: SourceState, field: String, value: String): SourceState = when (field) {
            "fetchedAt" -> state.copy(fetchedAtMillis = value.toLongOrNull())
            "validator" -> state.copy(validator = value.takeIf { isPlausibleValidator(it) })
            "failure" -> {
                val parts = value.split(':', limit = 2)
                state.copy(
                    failure = ListFailure.entries.firstOrNull { it.name == parts[0] },
                    failureAtMillis = parts.getOrNull(1)?.toLongOrNull(),
                )
            }
            else -> state
        }
    }
}
