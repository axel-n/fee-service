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
 * [IntegrationTestBeans]): every call goes to Postgres. A test arms it for one `transaction_id` with
 * [crashAfterCommitOnce]; the first time that write commits, it throws [SimulatedCrash] right after, before Restate can
 * journal the step's result. That is a crash between the commit and the journal entry: Restate runs the step again, and
 * the write must find what it committed the first time. Unarmed, it does nothing, so other ITs are unaffected.
 */
class FaultInjectingFeeRecordRepository(
    private val postgres: PostgresFeeRecordRepository,
) : FeeRecordRepository {
    /** The writes a crash can follow. */
    enum class Write { CLAIM, COMPLETE }

    private val armed = ConcurrentHashMap<String, Write>()
    private val crashes = ConcurrentHashMap<String, AtomicInteger>()

    /** Makes the next [write] of [transactionId] crash once, right after it committed. */
    fun crashAfterCommitOnce(transactionId: String, write: Write) {
        armed[transactionId] = write
    }

    /** How many crashes were injected after the writes of [transactionId]. */
    fun crashCount(transactionId: String): Int = crashes[transactionId]?.get() ?: 0

    override suspend fun claim(transaction: Transaction, claimToken: String): FeeRecord =
        postgres.claim(transaction, claimToken).also { crashIfArmed(transaction.transactionId, Write.CLAIM) }

    override suspend fun complete(transactionId: String, claimToken: String, response: FeeResponse) {
        postgres.complete(transactionId, claimToken, response)
        crashIfArmed(transactionId, Write.COMPLETE)
    }

    override suspend fun findCharged(transactionId: String): FeeResponse? = postgres.findCharged(transactionId)

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
