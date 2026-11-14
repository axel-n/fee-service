package com.example.fee.it

import com.example.fee.contract.FeeResponse
import com.example.fee.domain.Transaction
import com.example.fee.record.FeeRecordRepository
import com.example.fee.record.PostgresFeeRecordRepository
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The fee records of the shared IT app (it replaces `PostgresFeeRecordRepository` as the injected repository, see
 * [IntegrationTestBeans]): every call goes to Postgres, and is counted per `transaction_id` ([callCount]). A test arms
 * it for one `transaction_id` with [crashAfterChargeCommittedOnce]; the first time that charge commits, it throws
 * [SimulatedCrash] right after: a crash between the commit and Restate's journal entry. Restate runs the step again, and
 * the insert must find what it committed the first time. Unarmed, it only counts, so other ITs are unaffected.
 */
class FaultInjectingFeeRecordRepository(
    private val postgres: PostgresFeeRecordRepository,
) : FeeRecordRepository {
    private val armed = ConcurrentHashMap.newKeySet<String>()
    private val crashes = ConcurrentHashMap<String, AtomicInteger>()
    private val calls = CallCounter()

    /** Makes the next charge of [transactionId] crash once, right after it committed. */
    fun crashAfterChargeCommittedOnce(transactionId: String) {
        armed += transactionId
    }

    /** How many crashes were injected after the charges of [transactionId]. */
    fun crashCount(transactionId: String): Int = crashes[transactionId]?.get() ?: 0

    /** How many times the app asked Postgres about [transactionId]: lookups and charges, failed ones too. */
    fun callCount(transactionId: String): Int = calls.count(transactionId)

    override suspend fun charge(transaction: Transaction, response: FeeResponse): FeeResponse {
        val transactionId = transaction.transactionId
        calls.increment(transactionId)
        return postgres.charge(transaction, response).also { crashIfArmed(transactionId) }
    }

    override suspend fun findCharged(transactionId: String): FeeResponse? {
        calls.increment(transactionId)
        return postgres.findCharged(transactionId)
    }

    // remove disarms atomically, so the crash happens exactly once
    private fun crashIfArmed(transactionId: String) {
        if (armed.remove(transactionId)) {
            crashes.computeIfAbsent(transactionId) { AtomicInteger() }.incrementAndGet()
            throw SimulatedCrash("Simulated crash after the charge of $transactionId committed")
        }
    }

    /** The injected failure: a plain exception, which Restate retries. */
    class SimulatedCrash(
        message: String,
    ) : RuntimeException(message)
}
