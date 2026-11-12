package com.example.fee.fee.formula

import com.example.fee.domain.Asset
import com.example.fee.domain.Transaction
import com.example.fee.domain.TransactionType
import com.example.fee.fee.FeeQuote
import com.example.fee.fee.FeeRule
import com.example.fee.fee.requireNonNegative
import com.example.fee.fee.requireRate
import com.example.fee.fee.roundedFor
import java.math.BigDecimal

/**
 * Building block: `amount × rate`, but at least [min] (in the transaction's asset), rounded `HALF_EVEN` to the
 * asset's decimals. The quoted rate stays [rate] when the minimum applies.
 *
 * [description] builds the response's description for the transaction's asset; the rule bean supplies the wording.
 */
class PercentageWithMinimumFee(
    override val type: TransactionType,
    val rate: BigDecimal,
    val min: BigDecimal,
    private val description: (Asset) -> String,
) : FeeRule {

    init {
        requireRate(rate)
        requireNonNegative("min", min)
    }

    override fun calculate(transaction: Transaction): FeeQuote =
        FeeQuote(
            fee = (transaction.amount * rate).max(min).roundedFor(transaction.asset),
            rate = rate,
            description = description(transaction.asset),
        )
}
