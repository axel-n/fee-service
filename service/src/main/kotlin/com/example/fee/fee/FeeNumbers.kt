package com.example.fee.fee

import com.example.fee.domain.Asset
import java.math.BigDecimal
import java.math.RoundingMode

// Internal helpers of the fee formulas and rules: rounding, the checks of a formula's numbers, and the wording of
// descriptions.

/** The one place fees are rounded: `HALF_EVEN` at the asset's decimals (D3). */
internal fun BigDecimal.roundedFor(asset: Asset): BigDecimal = setScale(asset.decimals, RoundingMode.HALF_EVEN)

internal fun requireRate(rate: BigDecimal) {
    require(rate.signum() >= 0 && rate < BigDecimal.ONE) { "Fee rate must be in [0, 1), was ${rate.toPlainString()}" }
}

internal fun requireNonNegative(name: String, amount: BigDecimal) {
    require(amount.signum() >= 0) { "Fee $name must not be negative, was ${amount.toPlainString()}" }
}

// Descriptions are built from the configured numbers and the transaction's asset (D23), so they never go stale.

/** A rate as a percentage without trailing zeros: `0.0015` → `0.15%`, `0.01` → `1%`. */
internal fun percent(rate: BigDecimal): String = "${rate.movePointRight(2).stripTrailingZeros().toPlainString()}%"

/** An amount at the asset's decimals, with the asset: `1` → `1.00 EUR`. */
internal fun money(amount: BigDecimal, asset: Asset): String = "${amount.roundedFor(asset).toPlainString()} ${asset.value}"
