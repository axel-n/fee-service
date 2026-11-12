package com.example.fee.wallet

import com.example.fee.domain.Asset
import com.fasterxml.jackson.annotation.JsonProperty
import java.math.BigDecimal

/**
 * Charges a fee to the customer's wallet. The workflow calls it inside CHARGE's `ctx.run`, so a real implementation
 * replaces [FakeWalletGateway] without touching the workflow.
 *
 * Implementations must be **idempotent by [idempotencyKey]**: an interrupted `ctx.run` runs again after a restart
 *, and the second call must not charge twice. A repeated key returns the receipt of the first charge.
 */
interface WalletGateway {

    /**
     * Charges [amount] in [asset] once per [idempotencyKey].
     *
     * @throws ChargeDeclinedException when the wallet refuses the charge (a business result, not retried).
     * Any other exception is an infrastructure failure, and Restate retries it.
     */
    fun charge(idempotencyKey: String, amount: BigDecimal, asset: Asset): ChargeReceipt
}

/**
 * The wallet's confirmation of a charge. It may be journaled as CHARGE's `ctx.run` result, so its JSON shape is
 * durable: renaming or removing a field is breaking, adding a nullable one is safe.
 */
data class ChargeReceipt(
    @JsonProperty("charge_id") val chargeId: String,
    @JsonProperty("idempotency_key") val idempotencyKey: String,
    @JsonProperty("amount") val amount: BigDecimal,
    @JsonProperty("asset") val asset: Asset,
)

/**
 * The wallet refused the charge: a business failure, not an outage. CHARGE rethrows it as a `TerminalException`
 * inside its `ctx.run`, so Restate doesn't retry it and the workflow ends with `FeeOutcome.Failed`.
 */
class ChargeDeclinedException(
    val idempotencyKey: String,
    val reason: String,
) : RuntimeException("Charge '$idempotencyKey' declined: $reason")
