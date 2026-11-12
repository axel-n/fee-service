package com.example.fee.fee

import com.example.fee.domain.Asset
import com.example.fee.domain.Transaction
import com.example.fee.domain.TransactionType
import java.math.BigDecimal

/**
 * Building block: `amount × rate`, rounded `HALF_EVEN` to the asset's decimals.
 *
 * [description] builds the response's description for the transaction's asset; the rule bean supplies the wording.
 */
class PercentageFee(
    override val type: TransactionType,
    val rate: BigDecimal,
    private val description: (Asset) -> String,
) : FeeRule {

    init {
        requireRate(rate)
    }

    override fun calculate(transaction: Transaction): FeeQuote =
        FeeQuote(
            fee = (transaction.amount * rate).roundedFor(transaction.asset),
            rate = rate,
            description = description(transaction.asset),
        )
}
