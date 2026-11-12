package com.example.fee.domain

import com.example.fee.contract.FeeRequest
import org.springframework.stereotype.Component

/**
 * Turns a validated [FeeRequest] into a [Transaction] (the workflow's MAP step). Pure and deterministic, so it runs
 * outside `ctx.run` and simply runs again on replay.
 *
 * Never throws. It runs only after validation passed, so every field is present and parseable; should that ever
 * not hold, it returns `null` instead of inventing a value.
 */
@Component
class TransactionMapper {

    fun toTransaction(request: FeeRequest): Transaction? {
        val transactionId = request.transactionId ?: return null
        val amount = request.amount ?: return null
        val createdAt = CreatedAtFormat.parseOrNull(request.createdAt) ?: return null
        return Transaction(
            transactionId = transactionId,
            amount = amount,
            asset = Asset.fromValue(request.asset),
            assetType = AssetType.fromValue(request.assetType),
            type = TransactionType.fromValue(request.type),
            state = TransactionState.fromValue(request.state),
            createdAt = createdAt,
        )
    }
}
