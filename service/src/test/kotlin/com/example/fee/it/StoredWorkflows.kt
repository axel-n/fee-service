@file:Suppress("DEPRECATION") // the generated client of the ctx-based codegen API of SDK 2.9.3

package com.example.fee.it

import com.example.fee.contract.FeeRequest
import com.example.fee.workflow.FeeOutcome
import com.example.fee.workflow.FeeWorkflowClient
import com.example.fee.workflow.WorkflowStep

/**
 * Reads `FeeWorkflow` runs straight from the shared Restate's ingress, by the key the app derives from a request (its
 * `transaction_id`), so a test sees what the workflow itself returned.
 */
object StoredWorkflows {

    /** The workflow key `FeeService` uses for [request]. */
    fun keyOf(request: FeeRequest): String = checkNotNull(request.transactionId)

    /** The step the workflow of [request] is at, `DONE` once it returned, `null` if it never started. */
    fun step(request: FeeRequest): WorkflowStep? = FeeWorkflowClient.fromClient(TestIngress.client, keyOf(request)).status()

    /** The outcome the workflow of [request] returned, or `null` while it has none yet. */
    fun outcome(request: FeeRequest): FeeOutcome? {
        val output = FeeWorkflowClient.fromClient(TestIngress.client, keyOf(request)).workflowHandle().getOutput().response()
        return if (output.isReady) output.value else null
    }
}
