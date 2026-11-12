package com.example.fee.contract

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import java.math.BigDecimal

/**
 * The body of `POST /transaction/fee`, exactly as the client sent it.
 *
 * Every field is nullable and the enums and the date stay strings, so any well-formed JSON object reaches validation,
 * which then explains what is wrong instead of a generic parse error (D10, D11).
 *
 * Journaled as the workflow input, so this JSON shape is stored state (D21): renaming or removing a field breaks
 * in-flight workflows; adding a nullable field is safe.
 */
data class FeeRequest(
    @JsonProperty("transaction_id") val transactionId: String? = null,
    @JsonProperty("amount") @JsonSerialize(using = PlainBigDecimalSerializer::class) val amount: BigDecimal? = null,
    @JsonProperty("asset") val asset: String? = null,
    @JsonProperty("asset_type") val assetType: String? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("state") val state: String? = null,
    @JsonProperty("created_at") val createdAt: String? = null,
)
