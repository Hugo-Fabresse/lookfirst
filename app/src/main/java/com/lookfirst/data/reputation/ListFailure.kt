package com.lookfirst.data.reputation

/** Why a list was not adopted or not fetched. One enum value is all that is remembered; no server text. */
enum class ListFailure {
    NETWORK,
    HTTP,
    TOO_LARGE,
    BAD_FORMAT,
    TOO_FEW,
    TOO_MANY,
    VARIATION,
    POISONED,
    STORAGE,
}
