package com.example.fee.fee

import com.example.fee.domain.Asset
import com.example.fee.domain.Transaction
import com.example.fee.domain.TransactionType
import java.math.BigDecimal

/**
 * Building block: `amount × rate + fixed` (fixed in the transaction's asset), rounded `HALF_EVEN` to the asset's
 * decimals. The sum is rounded once, so no rounding error adds up.
 *
 * [description] builds the response's description for the transaction's asset; the rule bean supplies the wording.
 */
class PercentagePlusFixedFee(
    override val type: TransactionType,
    val rate: BigDecimal,
    val fixed: BigDecimal,
    private val description: (Asset) -> String,
) : FeeRule {

    init {
        requireRate(rate)
        requireNonNegative("fixed", fixed)
    }

    override fun calculate(transaction: Transaction): FeeQuote =
        FeeQuote(
            fee = (transaction.amount * rate + fixed).roundedFor(transaction.asset),
            rate = rate,
            description = description(transaction.asset),
        )
}
