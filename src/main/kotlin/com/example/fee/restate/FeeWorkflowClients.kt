@file:Suppress("DEPRECATION") // the ctx-based codegen API of SDK 2.9.3, kept on purpose (D29)

package com.example.fee.restate

/**
 * Creates the generated ingress client of [FeeWorkflow] for one workflow key. Generated clients are bound to a key, so
 * `FeeService` gets this factory instead of a client, and tests replace it with a mock.
 */
fun interface FeeWorkflowClients {

    /** The ingress client of the workflow `FeeWorkflow/{workflowKey}`. */
    fun forKey(workflowKey: String): FeeWorkflowClient.IngressClient
}
