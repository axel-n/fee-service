package com.example.fee.fee

import com.example.fee.domain.Transaction
import com.example.fee.domain.TransactionType
import java.math.BigDecimal

/**
 * How the fee of one [TransactionType] is calculated. There is exactly one rule bean per type (D14); `FeeCalculator`
 * indexes them by [type] and fails startup on a missing or duplicate one.
 *
 * To add a transaction type, add a `@Component` that implements this interface by composing one of the building
 * blocks in `fee.formula` (`PercentageFee`, `PercentageWithMinimumFee`, `PercentagePlusFixedFee`, `FlatFee`) with
 * numbers from its own `@ConfigurationProperties` class in `config` (D23). Rule beans live in `fee.rule`. A new fee
 * formula is a new building block implementing this interface.
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

/**
 * The fee a [FeeRule] calculated for one transaction: the response's `fee`, `rate` and `description`.
 *
 * [fee] is already rounded `HALF_EVEN` to the asset's decimals (D3), so its scale is the asset's decimals (`1.50` for
 * USD). [rate] is the configured percentage as a fraction (`0.0015`), `0` for a flat fee.
 *
 * The workflow's CALCULATE step returns it from `ctx.run`, so it is journaled: renaming or removing a field is a
 * breaking change for in-flight workflows (D21, D35).
 */
data class FeeQuote(
    val fee: BigDecimal,
    val rate: BigDecimal,
    val description: String,
)
