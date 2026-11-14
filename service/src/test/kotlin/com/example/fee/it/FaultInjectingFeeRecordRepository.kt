package com.example.fee.it

import com.example.fee.contract.FeeResponse
import com.example.fee.domain.Transaction
import com.example.fee.record.FeeRecord
import com.example.fee.record.FeeRecordRepository
import com.example.fee.record.PostgresFeeRecordRepository
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The fee records of the shared IT app (it replaces `PostgresFeeRecordRepository` as the injected repository, see
 * [IntegrationTestBeans]): every call goes to Postgres, and is counted per `transaction_id` ([callCount]). A test arms
 * it for one `transaction_id` with [crashAfterCommitOnce]; the first time that write commits, it throws [SimulatedCrash]
 * right after. After a CHARGE that is a crash between the commit and Restate's journal entry: Restate runs the step
 * again, and the write must find what it committed the first time. After a START it is the app dying between the insert
 * and the submit: the request fails, and the next one of the id must reuse the record. Unarmed, it only counts, so
 * other ITs are unaffected.
 */
class FaultInjectingFeeRecordRepository(
    private val postgres: PostgresFeeRecordRepository,
) : FeeRecordRepository {
    /** The writes a crash can follow. */
    enum class Write { START, CHARGE }

    private val armed = ConcurrentHashMap<String, Write>()
    private val crashes = ConcurrentHashMap<String, AtomicInteger>()
    private val calls = CallCounter()

    /** Makes the next [write] of [transactionId] crash once, right after it committed. */
    fun crashAfterCommitOnce(transactionId: String, write: Write) {
        armed[transactionId] = write
    }

    /** How many crashes were injected after the writes of [transactionId]. */
    fun crashCount(transactionId: String): Int = crashes[transactionId]?.get() ?: 0

    /** How many times the app asked Postgres about [transactionId]: lookups, starts and charges, failed ones too. */
    fun callCount(transactionId: String): Int = calls.count(transactionId)

    override suspend fun start(transaction: Transaction, workflowId: String): FeeRecord {
        calls.increment(transaction.transactionId)
        return postgres.start(transaction, workflowId).also { crashIfArmed(transaction.transactionId, Write.START) }
    }

    override suspend fun charge(transaction: Transaction, workflowId: String, response: FeeResponse) {
        val transactionId = transaction.transactionId
        calls.increment(transactionId)
        postgres.charge(transaction, workflowId, response)
        crashIfArmed(transactionId, Write.CHARGE)
    }

    override suspend fun findCharged(transactionId: String): FeeResponse? {
        calls.increment(transactionId)
        return postgres.findCharged(transactionId)
    }

    // remove(key, value) disarms atomically, so the crash happens exactly once
    private fun crashIfArmed(transactionId: String, write: Write) {
        if (armed.remove(transactionId, write)) {
            crashes.computeIfAbsent(transactionId) { AtomicInteger() }.incrementAndGet()
            throw SimulatedCrash("Simulated crash after the $write of $transactionId committed")
        }
    }

    /** The injected failure: a plain exception, which Restate retries. */
    class SimulatedCrash(
        message: String,
    ) : RuntimeException(message)
}
