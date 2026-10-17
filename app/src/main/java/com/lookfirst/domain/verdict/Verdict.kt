package com.lookfirst.domain.verdict

import com.lookfirst.domain.model.Signal

/** What the user is told. There is deliberately no "safe": the lowest level only says no signal was found. */
enum class VerdictLevel { DANGEROUS, SUSPICIOUS, CAUTION, NO_RISK_SIGNAL }

/**
 * [reasons] are ordered (heaviest first) and unique by code. [payloadId] ties the verdict to the displayed content
 * (ux.md 4.1): a later check yields a new verdict for the same id.
 */
data class Verdict(val level: VerdictLevel, val reasons: List<Signal>, val payloadId: String)
