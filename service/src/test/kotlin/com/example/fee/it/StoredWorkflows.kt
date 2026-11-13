package com.example.fee.it

import com.example.fee.contract.FeeRequest
import com.example.fee.workflow.FeeOutcome
import com.example.fee.workflow.FeeWorkflow
import com.example.fee.workflow.WorkflowStep
import dev.restate.client.SendResponse
import dev.restate.client.kotlin.toWorkflow
import dev.restate.client.kotlin.workflow
import dev.restate.client.kotlin.workflowHandle
import kotlinx.coroutines.runBlocking

/**
 * Reads `FeeWorkflow` runs straight from the shared Restate's ingress, by the key the app derives from a request (its
 * `transaction_id`), so a test sees what the workflow itself returned.
 */
object StoredWorkflows {
    /** The workflow key `FeeService` uses for [request]. */
    fun keyOf(request: FeeRequest): String = checkNotNull(request.transactionId)

    /** The step the workflow of [request] is at, `DONE` once it returned, `null` if it never started. */
    fun step(request: FeeRequest): WorkflowStep? = runBlocking { TestIngress.client.workflow<FeeWorkflow>(keyOf(request)).status() }

    /** The outcome the workflow of [request] returned, or `null` while it has none yet. */
    fun outcome(request: FeeRequest): FeeOutcome? {
        val output =
            TestIngress.client
                .workflowHandle<FeeOutcome>("FeeWorkflow", keyOf(request))
                .getOutput()
                .response()
        return if (output.isReady) output.value else null
    }

    /** Submits the workflow of [request] past the app, as `FeeService` does; a known key is `PREVIOUSLY_ACCEPTED`. */
    fun submit(request: FeeRequest): SendResponse<FeeOutcome> =
        runBlocking {
            TestIngress.client
                .toWorkflow<FeeWorkflow>(keyOf(request))
                .request { run(request) }
                .send()
        }
}
