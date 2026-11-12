package com.example.fee.wallet

/**
 * The wallet refused the charge: a business failure, not an outage. CHARGE rethrows it as a `TerminalException`
 * inside its `ctx.run`, so Restate doesn't retry it and the workflow ends with `FeeOutcome.Failed`.
 */
class ChargeDeclinedException(
    val idempotencyKey: String,
    val reason: String,
) : RuntimeException("Charge '$idempotencyKey' declined: $reason")
