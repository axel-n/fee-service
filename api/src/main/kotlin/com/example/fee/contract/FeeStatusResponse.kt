package com.example.fee.contract

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import java.math.BigDecimal

/**
 * Where the fee for a transaction stands: the body of every `POST /transaction/fee` that isn't an error (200
 * `COMPLETED`, 202 `IN_PROGRESS`).
 *
 * The [FeeResponse] fields are present once [Status.COMPLETED]; absent fields are left out of the JSON. HTTP only and never stored, so its shape may change freely.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class FeeStatusResponse(
    @JsonProperty("transaction_id") val transactionId: String,
    @JsonProperty("status") val status: Status,
    @JsonProperty("amount") @PlainBigDecimal val amount: BigDecimal? = null,
    @JsonProperty("asset") val asset: String? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("fee") @PlainBigDecimal val fee: BigDecimal? = null,
    @JsonProperty("rate") @PlainBigDecimal val rate: BigDecimal? = null,
    @JsonProperty("description") val description: String? = null,
) {
    enum class Status { IN_PROGRESS, COMPLETED }

    companion object {
        fun inProgress(transactionId: String) = FeeStatusResponse(transactionId, Status.IN_PROGRESS)

        fun completed(response: FeeResponse) =
            FeeStatusResponse(
                transactionId = response.transactionId,
                status = Status.COMPLETED,
                amount = response.amount,
                asset = response.asset,
                type = response.type,
                fee = response.fee,
                rate = response.rate,
                description = response.description,
            )
    }
}
