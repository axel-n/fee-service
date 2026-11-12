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
import java.util.concurrent.ConcurrentHashMap
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
 * `ctx.run`, so its count is the number of VALIDATE executions of requests that passed every real check. Recovery tests
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
 */
class CountingFeeCalculator(rules: List<FeeRule>) : FeeCalculator(rules) {
    private val counter = CallCounter()

    override fun calculate(transaction: Transaction): FeeQuote {
        counter.increment(transaction.transactionId)
        return super.calculate(transaction)
    }

    /** How many times CALCULATE ran its closure for [transactionId]. */
    fun count(transactionId: String): Int = counter.count(transactionId)
}
