package com.example.fee.fee

import com.example.fee.domain.Asset
import java.math.BigDecimal

// Descriptions are built from the configured numbers and the transaction's asset (D23), so they never go stale.

/** A rate as a percentage without trailing zeros: `0.0015` → `0.15%`, `0.01` → `1%`. */
internal fun percent(rate: BigDecimal): String = "${rate.movePointRight(2).stripTrailingZeros().toPlainString()}%"

/** An amount at the asset's decimals, with the asset: `1` → `1.00 EUR`. */
internal fun money(amount: BigDecimal, asset: Asset): String = "${amount.roundedFor(asset).toPlainString()} ${asset.value}"
