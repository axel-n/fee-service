package com.example.fee.it

import com.example.fee.contract.FeeRequest
import com.example.fee.contract.FeeResponse
import com.example.fee.workflow.FeeWorkflow
import com.example.fee.workflow.WorkflowStep
import dev.restate.client.kotlin.workflow
import dev.restate.client.kotlin.workflowHandle
import kotlinx.coroutines.runBlocking

/**
 * Reads `FeeWorkflow` runs straight from the shared Restate's ingress, by their key, the `transaction_id`, so a test
 * sees what the workflow itself returned. Needs no Postgres, so it works during a Postgres outage too.
 */
object StoredWorkflows {
    /** The step the workflow of [request] is at, `DONE` once it returned, `null` if it never started. */
    fun step(request: FeeRequest): WorkflowStep? = step(checkNotNull(request.transactionId))

    /** The step the workflow of [transactionId] is at, `DONE` once it returned, `null` if it never started. */
    fun step(transactionId: String): WorkflowStep? = runBlocking { TestIngress.client.workflow<FeeWorkflow>(transactionId).status() }

    /** The response the workflow of [request] returned, or `null` while it has none yet. */
    fun response(request: FeeRequest): FeeResponse? = response(checkNotNull(request.transactionId))

    /** The response the workflow of [transactionId] returned, or `null` while it has none yet. */
    fun response(transactionId: String): FeeResponse? {
        val output =
            TestIngress.client
                .workflowHandle<FeeResponse>("FeeWorkflow", transactionId)
                .getOutput()
                .response()
        return if (output.isReady) output.value else null
    }
}
