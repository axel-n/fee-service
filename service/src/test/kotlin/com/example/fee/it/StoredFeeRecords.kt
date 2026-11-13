package com.example.fee.it

import com.example.fee.record.ClaimResult
import com.example.fee.record.FeeRecord
import com.example.fee.record.TransactionFeeRecord
import dev.restate.client.kotlin.virtualObject
import kotlinx.coroutines.runBlocking

/**
 * Reads the permanent fee records straight from the shared Restate's ingress, past the app's own API, so a test sees what
 * Restate stored rather than what the app reports.
 */
object StoredFeeRecords {
    /** The record of `TransactionFeeRecord/{transactionId}`, or `null` if the id was never claimed. */
    fun of(transactionId: String): FeeRecord? = runBlocking { record(transactionId).get() }

    /** Calls `claim` on the record of [transactionId] directly, as a workflow at its CLAIM step does. */
    fun claim(transactionId: String): ClaimResult = runBlocking { record(transactionId).claim() }

    private fun record(transactionId: String): TransactionFeeRecord = TestIngress.client.virtualObject<TransactionFeeRecord>(transactionId)
}
