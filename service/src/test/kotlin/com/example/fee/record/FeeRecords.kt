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
 * The fee records in memory, with the rules of `PostgresFeeRecordRepository`: a claim inserts only an id without a
 * record, and only the claim token's holder can complete it. Every call is recorded in [calls]; a call whose name is in
 * [failing] throws that exception instead, and one in [hanging] never returns (a database that doesn't answer), both
 * before they change anything.
 */
class InMemoryFeeRecordRepository(
    vararg stored: Pair<String, FeeRecord>,
    private val failing: Map<String, Exception> = emptyMap(),
    private val hanging: Set<String> = emptySet(),
) : FeeRecordRepository {
    val records = mutableMapOf(*stored)
    val calls = mutableListOf<Call>()

    /** A call the code under test made: [name] is `claim` (with [transaction]), `complete` (with [response]) or `findCharged`. */
    data class Call(
        val name: String,
        val transactionId: String,
        val claimToken: String? = null,
        val response: FeeResponse? = null,
        val transaction: Transaction? = null,
    )

    override suspend fun claim(transaction: Transaction, claimToken: String): FeeRecord {
        record(Call("claim", transaction.transactionId, claimToken, transaction = transaction))
        return records.getOrPut(transaction.transactionId) { FeeRecord.inProgress(claimToken) }
    }

    override suspend fun complete(transactionId: String, claimToken: String, response: FeeResponse) {
        record(Call("complete", transactionId, claimToken, response))
        val record = checkNotNull(records[transactionId]?.takeIf { it.claimToken == claimToken }) { "not claimed by $claimToken" }
        records[transactionId] = record.complete(response)
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
