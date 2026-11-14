package com.example.fee.it

import com.example.fee.contract.FeeRequest
import com.example.fee.contract.FeeResponse
import com.example.fee.workflow.FeeWorkflow
import com.example.fee.workflow.WorkflowStep
import dev.restate.client.kotlin.workflow
import dev.restate.client.kotlin.workflowHandle
import kotlinx.coroutines.runBlocking

/**
 * Reads `FeeWorkflow` runs straight from the shared Restate's ingress, by the `workflow_id` `FeeService` stored on the
 * transaction's fee record, so a test sees what the workflow itself returned. The lookups by transaction read that
 * record in Postgres; while Postgres is down, a test reads by the workflow id it noted before.
 */
object StoredWorkflows {
    /** The workflow key of the transaction of [request]: its record's `workflow_id`, `null` while it has no record. */
    fun keyOf(request: FeeRequest): String? = keyOf(checkNotNull(request.transactionId))

    /** The workflow key of [transactionId]: its record's `workflow_id`, `null` while it has no record. */
    fun keyOf(transactionId: String): String? = StoredFeeRecords.of(transactionId)?.workflowId

    /** The step the workflow of [request] is at, `DONE` once it returned, `null` if it never started. */
    fun step(request: FeeRequest): WorkflowStep? = step(checkNotNull(request.transactionId))

    /** The step the workflow of [transactionId] is at, `DONE` once it returned, `null` if it never started. */
    fun step(transactionId: String): WorkflowStep? = keyOf(transactionId)?.let(::stepOf)

    /** The step of the workflow [workflowId], `DONE` once it returned, `null` if it never started. */
    fun stepOf(workflowId: String): WorkflowStep? = runBlocking { TestIngress.client.workflow<FeeWorkflow>(workflowId).status() }

    /** The response the workflow of [request] returned, or `null` while it has none yet. */
    fun response(request: FeeRequest): FeeResponse? = keyOf(request)?.let(::responseOf)

    /** The response the workflow [workflowId] returned, or `null` while it has none yet. */
    fun responseOf(workflowId: String): FeeResponse? {
        val output =
            TestIngress.client
                .workflowHandle<FeeResponse>("FeeWorkflow", workflowId)
                .getOutput()
                .response()
        return if (output.isReady) output.value else null
    }
}
