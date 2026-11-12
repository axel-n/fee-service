package com.example.fee.domain

/**
 * The asset types the service understands. Only [FIAT] is supported (D13).
 *
 * [value] is the exact string of the request's `asset_type` field.
 */
enum class AssetType(val value: String) {
    FIAT("FIAT"),

    /** Anything that matches no other value. */
    UNKNOWN("UNKNOWN"),
    ;

    companion object {
        private val byValue = entries.filter { it != UNKNOWN }.associateBy { it.value }

        /** Exact, case-sensitive match on [value]. Never throws: null or an unmatched string is [UNKNOWN] (D10). */
        fun fromValue(raw: String?): AssetType = raw?.let(byValue::get) ?: UNKNOWN
    }
}
