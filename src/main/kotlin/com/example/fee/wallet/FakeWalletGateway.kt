package com.example.fee.wallet

import com.example.fee.domain.Asset
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * An in-memory wallet that never declines. Idempotent by key: the first charge for a key is recorded, and every later
 * call with that key returns the same receipt, whatever amount it asks for.
 *
 * It waits `wallet.fake.delay` before it records a new charge (D24), so the demo can kill the app in the middle of
 * CHARGE: a kill during the wait leaves nothing charged. A key that is already charged returns at once.
 *
 * It logs every wait and every recorded charge (`Fake wallet: charged <key> …`), so the demo shows in the log that a
 * transaction was charged once, also across a killed process (D74).
 *
 * [pause] does the waiting; Spring finds no bean for it and uses the default, a real sleep. Unit tests pass their own.
 */
@Component
class FakeWalletGateway(
    properties: FakeWalletProperties,
    private val pause: Pause = Pause { Thread.sleep(it) },
) : WalletGateway {

    /** Waits for the given delay before a charge is recorded. */
    fun interface Pause {
        fun pause(delay: Duration)
    }

    private val delay: Duration = properties.delay
    private val receipts = ConcurrentHashMap<String, ChargeReceipt>()
    private val sequence = AtomicLong()

    override fun charge(idempotencyKey: String, amount: BigDecimal, asset: Asset): ChargeReceipt {
        receipts[idempotencyKey]?.let { return it }
        if (!delay.isZero) {
            log.info("Fake wallet: waiting {} before charging {} {} {}", delay, idempotencyKey, amount.toPlainString(), asset.value)
            pause.pause(delay)
        }
        return receipts.computeIfAbsent(idempotencyKey) {
            ChargeReceipt("fake-charge-${sequence.incrementAndGet()}", idempotencyKey, amount, asset).also { receipt ->
                log.info(
                    "Fake wallet: charged {} {} {} as {}",
                    idempotencyKey, amount.toPlainString(), asset.value, receipt.chargeId,
                )
            }
        }
    }

    /** How many charges were recorded for [idempotencyKey]: 1 once charged, never more (tests assert idempotency by it). */
    fun chargeCount(idempotencyKey: String): Int = if (receipts.containsKey(idempotencyKey)) 1 else 0

    private companion object {
        private val log = LoggerFactory.getLogger(FakeWalletGateway::class.java)
    }
}
