package com.example.fee.fee

import com.example.fee.domain.Asset
import java.math.BigDecimal
import java.math.RoundingMode

/** The one place fees are rounded: `HALF_EVEN` at the asset's decimals (D3). */
internal fun BigDecimal.roundedFor(asset: Asset): BigDecimal = setScale(asset.decimals, RoundingMode.HALF_EVEN)

internal fun requireRate(rate: BigDecimal) {
    require(rate.signum() >= 0 && rate < BigDecimal.ONE) { "Fee rate must be in [0, 1), was ${rate.toPlainString()}" }
}

internal fun requireNonNegative(name: String, amount: BigDecimal) {
    require(amount.signum() >= 0) { "Fee $name must not be negative, was ${amount.toPlainString()}" }
}
