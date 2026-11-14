package com.example.fee.record

import com.example.fee.contract.FeeResponse
import com.example.fee.domain.Transaction
import kotlinx.coroutines.awaitCancellation
import java.math.BigDecimal

/** Test fixtures for fee records: the reference Mobile Top Up response. */
object FeeRecords {
    val response =
        FeeResponse(
            transactionId = "txn_001",
            amount = BigDecimal("1000"),
            asset = "USD",
            type = "Mobile Top Up",
            fee = BigDecimal("1.50"),
            rate = BigDecimal("0.0015"),
            description = "Standard fee rate of 0.15%",
        )
}

/**
 * The fee records in memory, with the rules of `PostgresFeeRecordRepository`: a start inserts only an id without a
 * record and otherwise returns the record that is there, and only the record's own workflow can charge it. Every call is
 * recorded in [calls]; a call whose name is in [failing] throws that exception instead, and one in [hanging] never
 * returns (a database that doesn't answer), both before they change anything.
 */
class InMemoryFeeRecordRepository(
    vararg stored: Pair<String, FeeRecord>,
    private val failing: Map<String, Exception> = emptyMap(),
    private val hanging: Set<String> = emptySet(),
) : FeeRecordRepository {
    val records = mutableMapOf(*stored)
    val calls = mutableListOf<Call>()

    /** A call the code under test made: [name] is `start` (with [transaction]), `charge` (with both) or `findCharged`. */
    data class Call(
        val name: String,
        val transactionId: String,
        val workflowId: String? = null,
        val response: FeeResponse? = null,
        val transaction: Transaction? = null,
    )

    override suspend fun start(transaction: Transaction, workflowId: String): FeeRecord {
        record(Call("start", transaction.transactionId, workflowId, transaction = transaction))
        return records.getOrPut(transaction.transactionId) { FeeRecord.pending(workflowId) }
    }

    override suspend fun charge(transaction: Transaction, workflowId: String, response: FeeResponse) {
        val transactionId = transaction.transactionId
        record(Call("charge", transactionId, workflowId, response, transaction))
        val record = checkNotNull(records[transactionId]?.takeIf { it.workflowId == workflowId }) { "not started for $workflowId" }
        records[transactionId] = record.charge(response)
    }

    override suspend fun findCharged(transactionId: String): FeeResponse? {
        record(Call("findCharged", transactionId))
        return records[transactionId]?.response
    }

    private suspend fun record(call: Call) {
        calls += call
        failing[call.name]?.let { throw it }
        if (call.name in hanging) awaitCancellation()
    }
}
