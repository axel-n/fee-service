package com.example.fee.restate

import com.example.fee.contract.FeeRequest
import com.example.fee.contract.FeeResponse
import com.example.fee.workflow.FeeWorkflow
import dev.restate.client.Client
import dev.restate.client.SendResponse
import dev.restate.client.kotlin.attachSuspend
import dev.restate.client.kotlin.getOutputSuspend
import dev.restate.client.kotlin.toWorkflow

/**
 * How `FeeService` reaches `FeeWorkflow` through the ingress. The workflow key is the transaction's `workflow_id`, from
 * its fee record. An interface, so `FeeService` is tested with fakes.
 */
interface FeeWorkflowClients {
    /**
     * Submits `FeeWorkflow/{workflowId}` with [request] and returns once Restate acknowledged it. If that key was
     * submitted before, Restate answers `PREVIOUSLY_ACCEPTED`, ignores [request], and the returned run is the first one.
     */
    suspend fun submit(workflowId: String, request: FeeRequest): FeeWorkflowRun
}

/** A submitted run of `FeeWorkflow`. */
interface FeeWorkflowRun {
    /** Waits for the run's charged response. Every caller attached to the same run gets the same response. */
    suspend fun attach(): FeeResponse

    /**
     * Asks Restate once, without waiting, for the run's charged response: `null` exactly when Restate answers 470 (not
     * ready), that is, when Restate itself says the run hasn't finished.
     */
    suspend fun output(): FeeResponse?
}

/** [FeeWorkflowClients] over the ingress [client], through the Kotlin SDK's typed requests. */
class IngressFeeWorkflowClients(
    private val client: Client,
) : FeeWorkflowClients {
    override suspend fun submit(workflowId: String, request: FeeRequest): FeeWorkflowRun {
        val sent = client.toWorkflow<FeeWorkflow>(workflowId).request { run(request) }.send()
        return IngressFeeWorkflowRun(workflowId, sent)
    }
}

/** The run Restate acknowledged with [sent]. The SDK reads Restate's 470 as `Output.notReady()`, every other error throws. */
private class IngressFeeWorkflowRun(
    private val workflowId: String,
    private val sent: SendResponse<FeeResponse>,
) : FeeWorkflowRun {
    override suspend fun attach(): FeeResponse = response(sent.attachSuspend().response())

    override suspend fun output(): FeeResponse? {
        val output = sent.getOutputSuspend().response()
        return if (output.isReady) response(output.value) else null
    }

    private fun response(response: FeeResponse?): FeeResponse = checkNotNull(response) { "Workflow $workflowId returned no response" }
}
