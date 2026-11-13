package com.example.fee.it

import com.example.fee.contract.FeeRequest
import com.example.fee.domain.Transaction
import com.example.fee.fee.FeeCalculator
import com.example.fee.fee.FeeQuote
import com.example.fee.fee.FeeRule
import com.example.fee.validation.TransactionValidator
import com.example.fee.validation.ValidationError
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Counts per `transaction_id`, for the closure counters below. */
class CallCounter {
    private val counts = ConcurrentHashMap<String, AtomicInteger>()

    fun increment(transactionId: String?) {
        counts.computeIfAbsent(transactionId.orEmpty()) { AtomicInteger() }.incrementAndGet()
    }

    fun count(transactionId: String): Int = counts[transactionId]?.get() ?: 0
}

/**
 * A validator that always passes and counts how often it ran, per `transaction_id`. It runs last, inside the VALIDATE
 * a journaled step, so its count is the number of VALIDATE executions of requests that passed every real check. Recovery tests
 * count these instead of hook calls, because a replay runs the handler again but not a journaled closure.
 */
@Order(Ordered.LOWEST_PRECEDENCE)
class CountingValidator : TransactionValidator {
    private val counter = CallCounter()

    override fun validate(request: FeeRequest): ValidationError? {
        counter.increment(request.transactionId)
        return null
    }

    /** How many times VALIDATE ran its closure through every check for [transactionId]. */
    fun count(transactionId: String): Int = counter.count(transactionId)
}

/**
 * The app's `FeeCalculator` that also counts [calculate] calls per `transaction_id`, i.e. CALCULATE closure executions.
 * Pricing is unchanged: the same rules, the same startup check.
 *
 * A test can hold the calculations of one `transaction_id` on a latch ([block] / [release]), to keep a workflow inside
 * its CALCULATE closure, which Restate hasn't journaled yet. Tests use their own transaction ids, so no reset is needed.
 */
class CountingFeeCalculator(
    rules: List<FeeRule>,
) : FeeCalculator(rules) {
    private val counter = CallCounter()
    private val finished = CallCounter()
    private val gates = ConcurrentHashMap<String, CountDownLatch>()

    override fun calculate(transaction: Transaction): FeeQuote {
        // counted before the gate, so a test sees a blocked call arrive
        counter.increment(transaction.transactionId)
        gates[transaction.transactionId]?.let { awaitRelease(transaction.transactionId, it) }
        return super.calculate(transaction).also { finished.increment(transaction.transactionId) }
    }

    /** How many times CALCULATE ran its closure for [transactionId]. */
    fun count(transactionId: String): Int = counter.count(transactionId)

    /** How many of those closure runs for [transactionId] have returned their quote. */
    fun finishedCount(transactionId: String): Int = finished.count(transactionId)

    /**
     * From now on every [calculate] for [transactionId] waits until [release]. Waiting calls already count in [count], so
     * a test polls that to know CALCULATE has started.
     */
    fun block(transactionId: String) {
        gates[transactionId] = CountDownLatch(1)
    }

    /** Lets every waiting and later [calculate] for [transactionId] go on. Safe to call when nothing is blocked. */
    fun release(transactionId: String) {
        gates.remove(transactionId)?.countDown()
    }

    // Bounded, so a test that never releases can't leave a thread waiting for the rest of the run. The plain exception
    // makes Restate retry the closure.
    private fun awaitRelease(transactionId: String, gate: CountDownLatch) {
        check(gate.await(MAX_BLOCK.toMillis(), TimeUnit.MILLISECONDS)) {
            "calculation for $transactionId was not released within $MAX_BLOCK"
        }
    }

    private companion object {
        val MAX_BLOCK: Duration = Duration.ofMinutes(2)
    }
}
