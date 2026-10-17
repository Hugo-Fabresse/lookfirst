package com.lookfirst.data.reputation

import com.lookfirst.domain.analysis.ReputationLookup
import com.lookfirst.domain.analysis.ReputationResult
import com.lookfirst.domain.host.NormalizedHost
import javax.inject.Inject

/** A lookup with no list: every host gets `ListsMissing`. Not bound in Hilt since LF-23; kept as a test stand-in. */
class NoReputationLists @Inject constructor() : ReputationLookup {
    override fun lookup(host: NormalizedHost): ReputationResult = ReputationResult.ListsMissing
}
