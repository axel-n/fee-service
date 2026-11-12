package com.example.fee.fee

import java.math.BigDecimal

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
