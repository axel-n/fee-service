package com.example.fee.wallet

import com.example.fee.domain.Asset
import com.fasterxml.jackson.annotation.JsonProperty
import java.math.BigDecimal

/**
 * The wallet's confirmation of a charge. It may be journaled as CHARGE's `ctx.run` result, so its JSON shape is
 * durable (D21, D35): renaming or removing a field is breaking, adding a nullable one is safe.
 */
data class ChargeReceipt(
    @JsonProperty("charge_id") val chargeId: String,
    @JsonProperty("idempotency_key") val idempotencyKey: String,
    @JsonProperty("amount") val amount: BigDecimal,
    @JsonProperty("asset") val asset: Asset,
)
