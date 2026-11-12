package com.example.fee.wallet

import com.example.fee.domain.Asset
import java.math.BigDecimal

/**
 * Charges a fee to the customer's wallet. The workflow calls it inside CHARGE's `ctx.run`, so a real implementation
 * replaces [FakeWalletGateway] without touching the workflow.
 *
 * Implementations must be **idempotent by [idempotencyKey]**: an interrupted `ctx.run` runs again after a restart
 * (D32), and the second call must not charge twice. A repeated key returns the receipt of the first charge.
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
