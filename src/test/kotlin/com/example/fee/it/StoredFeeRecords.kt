@file:Suppress("DEPRECATION") // the generated client of the ctx-based codegen API of SDK 2.9.3 (D29)

package com.example.fee.it

import com.example.fee.record.FeeRecord
import com.example.fee.record.TransactionFeeRecordClient

/**
 * Reads the permanent fee records straight from the shared Restate's ingress, past the app's own API, so a test sees what
 * Restate stored rather than what the app reports.
 */
object StoredFeeRecords {

    /** The record of `TransactionFeeRecord/{transactionId}`, or `null` if the id was never claimed. */
    fun of(transactionId: String): FeeRecord? =
        TransactionFeeRecordClient.fromClient(TestIngress.client, transactionId).get()
}
