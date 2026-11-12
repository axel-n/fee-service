@file:Suppress("DEPRECATION") // the ctx-based codegen API of SDK 2.9.3, kept on purpose (D29)

package com.example.fee.restate

/**
 * Creates the generated ingress client of [TransactionFeeRecord] for one `transaction_id`. Generated clients are bound
 * to a key, so `FeeService` gets this factory instead of a client, and tests replace it with a mock.
 */
fun interface TransactionFeeRecordClients {

    /** The ingress client of the virtual object `TransactionFeeRecord/{transactionId}`. */
    fun forKey(transactionId: String): TransactionFeeRecordClient.IngressClient
}
