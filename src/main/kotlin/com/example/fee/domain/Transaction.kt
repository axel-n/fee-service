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
