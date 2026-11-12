package com.example.fee.fee

import com.example.fee.domain.Transaction
import com.example.fee.domain.TransactionType

/**
 * How the fee of one [TransactionType] is calculated. There is exactly one rule bean per type (D14); `FeeCalculator`
 * indexes them by [type] and fails startup on a missing or duplicate one.
 *
 * To add a transaction type, add a `@Component` that implements this interface by composing one of the building
 * blocks ([PercentageFee], [PercentageWithMinimumFee], [PercentagePlusFixedFee], [FlatFee]) with numbers from its own
 * `@ConfigurationProperties` class (D23). A new fee formula is a new building block implementing this interface.
 *
 * Implementations must be pure and deterministic: they run inside the workflow's CALCULATE `ctx.run`, and money is
 * `BigDecimal`, rounded `HALF_EVEN` to the transaction asset's decimals.
 */
interface FeeRule {

    /** The transaction type this rule prices. */
    val type: TransactionType

    /**
     * The fee for [transaction], whose [Transaction.type] is [type] and which passed validation. Never throws for a
     * validated transaction.
     */
    fun calculate(transaction: Transaction): FeeQuote
}
