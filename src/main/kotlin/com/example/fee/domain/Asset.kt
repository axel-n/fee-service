package com.example.fee.domain

/**
 * The supported assets (D13). A new asset is a new value here, with its decimals and asset type.
 *
 * [value] is the exact string of the request's `asset` field. [decimals] is the scale amounts and fees are allowed
 * to have and are rounded to (`HALF_EVEN`, D3).
 */
enum class Asset(val value: String, val decimals: Int, val assetType: AssetType) {
    USD("USD", 2, AssetType.FIAT),
    EUR("EUR", 2, AssetType.FIAT),

    /**
     * Anything that matches no other value. Validation rejects it before any amount is checked or rounded, so its
     * decimals are never used.
     */
    UNKNOWN("UNKNOWN", 0, AssetType.UNKNOWN),
    ;

    companion object {
        private val byValue = entries.filter { it != UNKNOWN }.associateBy { it.value }

        /** Exact, case-sensitive match on [value]. Never throws: null or an unmatched string is [UNKNOWN] (D10). */
        fun fromValue(raw: String?): Asset = raw?.let(byValue::get) ?: UNKNOWN
    }
}
