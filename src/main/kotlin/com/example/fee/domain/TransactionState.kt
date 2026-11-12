package com.example.fee.domain

/**
 * The transaction states the service understands. Only [SETTLED_PENDING_FEE] is processed (D12); every other state
 * is [UNKNOWN] and rejected by validation.
 *
 * [value] is the exact string of the request's `state` field.
 */
enum class TransactionState(val value: String) {
    SETTLED_PENDING_FEE("SETTLED - PENDING FEE"),

    /** Anything that matches no other value. */
    UNKNOWN("UNKNOWN"),
    ;

    companion object {
        private val byValue = entries.filter { it != UNKNOWN }.associateBy { it.value }

        /** Exact, case-sensitive match on [value]. Never throws: null or an unmatched string is [UNKNOWN] (D10). */
        fun fromValue(raw: String?): TransactionState = raw?.let(byValue::get) ?: UNKNOWN
    }
}
