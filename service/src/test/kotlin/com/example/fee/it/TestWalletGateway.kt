package com.example.fee.it

import com.example.fee.domain.Asset
import com.example.fee.wallet.ChargeDeclinedException
import com.example.fee.wallet.ChargeReceipt
import com.example.fee.wallet.WalletGateway
import java.math.BigDecimal
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The wallet of the shared IT app (it replaces `FakeWalletGateway`, see [IntegrationTestBeans]). Idempotent by key like
 * any `WalletGateway`, and it counts what reached it, so tests assert idempotency by charge count, not just by response.
 *
 * A test can hold the charges of one key on a latch ([blockCharges] / [releaseCharges]), to keep a workflow inside its
 * CHARGE closure, make the next calls of a key fail like a wallet outage ([failCharges]), or refuse every charge of a
 * key ([declineCharges]). Tests use their own transaction ids, so no reset is needed.
 */
class TestWalletGateway : WalletGateway {

    private val receipts = ConcurrentHashMap<String, ChargeReceipt>()
    private val calls = ConcurrentHashMap<String, AtomicInteger>()
    private val gates = ConcurrentHashMap<String, CountDownLatch>()
    private val failuresLeft = ConcurrentHashMap<String, AtomicInteger>()
    private val declines = ConcurrentHashMap<String, String>()
    private val sequence = AtomicLong()

    override fun charge(idempotencyKey: String, amount: BigDecimal, asset: Asset): ChargeReceipt {
        // counted before the gate, so a test sees a blocked call arrive
        calls.computeIfAbsent(idempotencyKey) { AtomicInteger() }.incrementAndGet()
        if (takeFailure(idempotencyKey)) {
            throw TransientWalletFailure("wallet unavailable for $idempotencyKey (simulated)")
        }
        declines[idempotencyKey]?.let { throw ChargeDeclinedException(idempotencyKey, it) }
        gates[idempotencyKey]?.let { awaitRelease(idempotencyKey, it) }
        return receipts.computeIfAbsent(idempotencyKey) {
            ChargeReceipt("test-charge-${sequence.incrementAndGet()}", idempotencyKey, amount, asset)
        }
    }

    /**
     * From now on every [charge] with [idempotencyKey] waits, before it records anything, until [releaseCharges]. Waiting
     * calls already count in [callCount], so a test polls that to know CHARGE has started.
     */
    fun blockCharges(idempotencyKey: String) {
        gates[idempotencyKey] = CountDownLatch(1)
    }

    /**
     * The next [times] calls of [charge] with [idempotencyKey] throw a plain [TransientWalletFailure] before they record
     * anything, as an unreachable wallet would; later calls charge normally. Failed calls count in [callCount].
     */
    fun failCharges(idempotencyKey: String, times: Int) {
        require(times >= 0) { "times must be >= 0, was $times" }
        failuresLeft[idempotencyKey] = AtomicInteger(times)
    }

    /**
     * From now on every [charge] with [idempotencyKey] throws [ChargeDeclinedException] with [reason], as a wallet that
     * refuses the charge would, and records nothing. Declined calls count in [callCount].
     */
    fun declineCharges(idempotencyKey: String, reason: String) {
        declines[idempotencyKey] = reason
    }

    /** How many injected failures of [idempotencyKey] are still to come. */
    fun failuresLeft(idempotencyKey: String): Int = failuresLeft[idempotencyKey]?.get() ?: 0

    /** Lets every waiting and later [charge] with [idempotencyKey] go on. Safe to call when nothing is blocked. */
    fun releaseCharges(idempotencyKey: String) {
        gates.remove(idempotencyKey)?.countDown()
    }

    /** How many charges were recorded for [idempotencyKey]: 0 or 1, never more. */
    fun chargeCount(idempotencyKey: String): Int = if (receipts.containsKey(idempotencyKey)) 1 else 0

    /** How many times [charge] was called with [idempotencyKey], repeats included (each is a CHARGE closure execution). */
    fun callCount(idempotencyKey: String): Int = calls[idempotencyKey]?.get() ?: 0

    /** The receipt of the charge recorded for [idempotencyKey], or `null` if none. */
    fun receipt(idempotencyKey: String): ChargeReceipt? = receipts[idempotencyKey]

    // Atomic per call, so concurrent calls of one key can't use up the same failure twice
    private fun takeFailure(idempotencyKey: String): Boolean =
        (failuresLeft[idempotencyKey]?.getAndUpdate { if (it > 0) it - 1 else 0 } ?: 0) > 0

    // Bounded, so a test that never releases can't leave a wallet thread waiting for the rest of the run. The plain
    // exception makes Restate retry the closure, as a wallet outage would.
    private fun awaitRelease(idempotencyKey: String, gate: CountDownLatch) {
        check(gate.await(MAX_BLOCK.toMillis(), TimeUnit.MILLISECONDS)) {
            "charge $idempotencyKey was not released within $MAX_BLOCK"
        }
    }

    private companion object {
        val MAX_BLOCK: Duration = Duration.ofMinutes(2)
    }
}

/** A transient wallet failure injected by [TestWalletGateway.failCharges]: a plain exception, so Restate retries it. */
class TransientWalletFailure(message: String) : RuntimeException(message)
