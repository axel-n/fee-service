package com.example.fee.fee.formula

import com.example.fee.domain.Asset
import com.example.fee.domain.Transaction
import com.example.fee.domain.TransactionType
import com.example.fee.fee.FeeQuote
import com.example.fee.fee.FeeRule
import com.example.fee.fee.requireNonNegative
import com.example.fee.fee.roundedFor
import java.math.BigDecimal

/**
 * Building block: always [fixed] (in the transaction's asset), whatever the amount, rounded `HALF_EVEN` to the
 * asset's decimals. The quoted rate is `0`.
 *
 * [description] builds the response's description for the transaction's asset; the rule bean supplies the wording.
 */
class FlatFee(
    override val type: TransactionType,
    val fixed: BigDecimal,
    private val description: (Asset) -> String,
) : FeeRule {
    init {
        requireNonNegative("fixed", fixed)
    }

    override fun calculate(transaction: Transaction): FeeQuote =
        FeeQuote(
            fee = fixed.roundedFor(transaction.asset),
            rate = BigDecimal.ZERO,
            description = description(transaction.asset),
        )
}
