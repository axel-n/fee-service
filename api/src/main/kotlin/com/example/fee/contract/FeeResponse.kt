package com.example.fee.contract

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import java.math.BigDecimal

/**
 * The fee charged for a transaction. HTTP clients get it inside a [FeeStatusResponse].
 *
 * [asset] and [type] are the contract strings (`"USD"`, `"Mobile Top Up"`), not the domain enums, so the stored shape
 * doesn't depend on enum names. [rate] is the configured rate that produced [fee] (`0` for a flat fee).
 *
 * Stored permanently in `TransactionFeeRecord`, so this JSON shape is stored state: renaming or removing a field
 * breaks existing records; adding a nullable field is safe.
 */
data class FeeResponse(
    @JsonProperty("transaction_id") val transactionId: String,
    @JsonProperty("amount") @JsonSerialize(using = PlainBigDecimalSerializer::class) val amount: BigDecimal,
    @JsonProperty("asset") val asset: String,
    @JsonProperty("type") val type: String,
    @JsonProperty("fee") @JsonSerialize(using = PlainBigDecimalSerializer::class) val fee: BigDecimal,
    @JsonProperty("rate") @JsonSerialize(using = PlainBigDecimalSerializer::class) val rate: BigDecimal,
    @JsonProperty("description") val description: String,
)
