package com.example.fee.contract

import com.fasterxml.jackson.annotation.JsonProperty
import java.math.BigDecimal

/**
 * The fee charged for a transaction: the body of a `POST /transaction/fee` that charged it (200).
 *
 * [asset] and [type] are the contract strings (`"USD"`, `"Mobile Top Up"`), not the domain enums, so the stored shape
 * doesn't depend on enum names. [rate] is the configured rate that produced [fee] (`0` for a flat fee). The stored
 * [fee] has the asset's decimals (`1.50`); the API answers it without trailing zeros (`1.5`), as the challenge's sample does.
 *
 * Stored permanently in `TransactionFeeRecord`, so this JSON shape is stored state: renaming or removing a field
 * breaks existing records; adding a nullable field is safe.
 */
data class FeeResponse(
    @JsonProperty("transaction_id") val transactionId: String,
    @JsonProperty("amount") @PlainBigDecimal val amount: BigDecimal,
    @JsonProperty("asset") val asset: String,
    @JsonProperty("type") val type: String,
    @JsonProperty("fee") @PlainBigDecimal val fee: BigDecimal,
    @JsonProperty("rate") @PlainBigDecimal val rate: BigDecimal,
    @JsonProperty("description") val description: String,
)
