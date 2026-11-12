package com.example.fee.wallet

import com.example.fee.domain.Asset
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * An in-memory wallet that never declines. Idempotent by key: the first charge for a key is recorded, and every later
 * call with that key returns the same receipt, whatever amount it asks for.
 *
 * It logs every recorded charge (`Fake wallet: charged <key> …`), so the log shows that a transaction was charged once.
 */
@Component
class FakeWalletGateway : WalletGateway {

    private val receipts = ConcurrentHashMap<String, ChargeReceipt>()
    private val sequence = AtomicLong()

    override fun charge(idempotencyKey: String, amount: BigDecimal, asset: Asset): ChargeReceipt =
        receipts.computeIfAbsent(idempotencyKey) {
            ChargeReceipt("fake-charge-${sequence.incrementAndGet()}", idempotencyKey, amount, asset).also { receipt ->
                log.info(
                    "Fake wallet: charged {} {} {} as {}",
                    idempotencyKey, amount.toPlainString(), asset.value, receipt.chargeId,
                )
            }
        }

    /** How many charges were recorded for [idempotencyKey]: 1 once charged, never more (tests assert idempotency by it). */
    fun chargeCount(idempotencyKey: String): Int = if (receipts.containsKey(idempotencyKey)) 1 else 0

    private companion object {
        private val log = LoggerFactory.getLogger(FakeWalletGateway::class.java)
    }
}
