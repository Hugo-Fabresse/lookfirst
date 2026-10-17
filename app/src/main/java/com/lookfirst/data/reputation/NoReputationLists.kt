package com.lookfirst.data.reputation

import com.lookfirst.domain.analysis.ReputationLookup
import com.lookfirst.domain.analysis.ReputationResult
import com.lookfirst.domain.host.NormalizedHost
import javax.inject.Inject

/** Placeholder until the Lists epic: no list installed, so every lookup says so (and a URL is at least CAUTION). */
class NoReputationLists @Inject constructor() : ReputationLookup {
    override fun lookup(host: NormalizedHost): ReputationResult = ReputationResult.ListsMissing
}
