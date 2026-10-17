package com.lookfirst.domain.analysis

import com.lookfirst.domain.host.NormalizedHost

/** Outcome of looking a host up in the local reputation lists. Never a network call. */
sealed interface ReputationResult {
    /** [source] is a fixed list name, never scanned text. */
    data class Listed(val source: String) : ReputationResult

    data object NotListed : ReputationResult

    /** No list is installed. */
    data object ListsMissing : ReputationResult

    /** Lists exist but are [ageDays] old. */
    data class ListsStale(val ageDays: Int) : ReputationResult
}

/** Port to the local reputation lists (epic Lists). Synchronous, offline. */
fun interface ReputationLookup {
    fun lookup(host: NormalizedHost): ReputationResult
}
