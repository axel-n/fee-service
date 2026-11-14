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
 * The fee records in memory, with the rules of `PostgresFeeRecordRepository`: a charge stores the response only for an
 * id without a record, and returns the charge the record then holds. [charges] maps each charged id to its response.
 * Every call is recorded in [calls]; a call whose name is in [failing] throws that exception instead, and one in
 * [hanging] never returns (a database that doesn't answer), both before they change anything.
 */
class InMemoryFeeRecordRepository(
    vararg stored: FeeResponse,
    private val failing: Map<String, Exception> = emptyMap(),
    private val hanging: Set<String> = emptySet(),
) : FeeRecordRepository {
    val charges = stored.associateBy { it.transactionId }.toMutableMap()
    val calls = mutableListOf<Call>()

    /** A call the code under test made: [name] is `charge` (with [response] and [transaction]) or `findCharged`. */
    data class Call(
        val name: String,
        val transactionId: String,
        val response: FeeResponse? = null,
        val transaction: Transaction? = null,
    )

    override suspend fun charge(transaction: Transaction, response: FeeResponse): FeeResponse {
        record(Call("charge", transaction.transactionId, response, transaction))
        return charges.getOrPut(transaction.transactionId) { response }
    }

    override suspend fun findCharged(transactionId: String): FeeResponse? {
        record(Call("findCharged", transactionId))
        return charges[transactionId]
    }

    private suspend fun record(call: Call) {
        calls += call
        failing[call.name]?.let { throw it }
        if (call.name in hanging) awaitCancellation()
    }
}
