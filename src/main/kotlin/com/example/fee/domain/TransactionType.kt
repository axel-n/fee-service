package com.example.fee.domain

/**
 * The transaction types that have a fee rule. A new type is a new value here plus its `FeeRule` (see AGENTS.md "Extending").
 *
 * [value] is the exact string of the request's `type` field.
 */
enum class TransactionType(val value: String) {
    MOBILE_TOP_UP("Mobile Top Up"),
    BANK_TRANSFER("Bank Transfer"),
    MERCHANT_PAYMENT("Merchant Payment"),
    CASH_WITHDRAWAL("Cash Withdrawal"),

    /** Anything that matches no other value; validation rejects it, so it never reaches the fee rules. */
    UNKNOWN("UNKNOWN"),
    ;

    companion object {
        private val byValue = entries.filter { it != UNKNOWN }.associateBy { it.value }

        /** Exact, case-sensitive match on [value]. Never throws: null or an unmatched string is [UNKNOWN] (D10). */
        fun fromValue(raw: String?): TransactionType = raw?.let(byValue::get) ?: UNKNOWN
    }
}
