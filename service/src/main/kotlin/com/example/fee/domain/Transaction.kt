package com.example.fee.domain

import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * A request that passed validation, with its fields parsed. Built by [TransactionMapper] in the workflow's MAP step.
 *
 * Never journaled on its own: MAP is pure and runs again on every replay, so this class is not part of the durable
 * contract and can change freely.
 */
data class Transaction(
    val transactionId: String,
    val amount: BigDecimal,
    val asset: Asset,
    val assetType: AssetType,
    val type: TransactionType,
    val state: TransactionState,
    val createdAt: LocalDateTime,
)

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

        /** Exact, case-sensitive match on [value]. Never throws: null or an unmatched string is [UNKNOWN]. */
        fun fromValue(raw: String?): TransactionType = raw?.let(byValue::get) ?: UNKNOWN
    }
}

/**
 * The transaction states the service understands. Only [SETTLED_PENDING_FEE] is processed; every other state
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

        /** Exact, case-sensitive match on [value]. Never throws: null or an unmatched string is [UNKNOWN]. */
        fun fromValue(raw: String?): TransactionState = raw?.let(byValue::get) ?: UNKNOWN
    }
}

/**
 * The supported assets. A new asset is a new value here, with its decimals and asset type.
 *
 * [value] is the exact string of the request's `asset` field. [decimals] is the scale amounts and fees are allowed
 * to have and are rounded to (`HALF_EVEN`).
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

        /** Exact, case-sensitive match on [value]. Never throws: null or an unmatched string is [UNKNOWN]. */
        fun fromValue(raw: String?): Asset = raw?.let(byValue::get) ?: UNKNOWN
    }
}

/**
 * The asset types the service understands. Only [FIAT] is supported.
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

        /** Exact, case-sensitive match on [value]. Never throws: null or an unmatched string is [UNKNOWN]. */
        fun fromValue(raw: String?): AssetType = raw?.let(byValue::get) ?: UNKNOWN
    }
}
