package com.example.fee.restate

import com.example.fee.config.RestateProperties
import com.example.fee.contract.FeeRequest
import com.example.fee.restate.serde.KotlinJacksonSerdeFactory
import com.example.fee.workflow.FeeOutcome
import com.example.fee.workflow.FeeWorkflow
import dev.restate.client.Client
import dev.restate.client.SendResponse
import dev.restate.client.kotlin.attachSuspend
import dev.restate.client.kotlin.getOutputSuspend
import dev.restate.client.kotlin.toWorkflow
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The app's side of the Restate ingress: one shared [Client] and the [FeeWorkflowClients] `FeeService` reaches the
 * workflow with. Creating the client opens no connection, so startup doesn't need Restate to be up.
 */
@Configuration(proxyBeanMethods = false)
class RestateConfig {
    // The serde factory the handlers declare, so the ingress and the endpoint read the same JSON.
    @Bean
    fun restateIngressClient(properties: RestateProperties): Client = Client.connect(properties.ingressUrl, KotlinJacksonSerdeFactory())

    @Bean
    fun feeWorkflowClients(restateIngressClient: Client): FeeWorkflowClients = IngressFeeWorkflowClients(restateIngressClient)
}

/**
 * How `FeeService` reaches `FeeWorkflow` through the ingress. The workflow key is the `transaction_id`. An interface,
 * so `FeeService` is tested with fakes.
 */
interface FeeWorkflowClients {
    /**
     * Submits `FeeWorkflow/{transactionId}` with [request] and returns once Restate acknowledged it. If that key was
     * submitted before, Restate answers `PREVIOUSLY_ACCEPTED`, ignores [request], and the returned run is the first one.
     */
    suspend fun submit(
        transactionId: String,
        request: FeeRequest,
    ): FeeWorkflowRun
}

/** A submitted run of `FeeWorkflow`. */
interface FeeWorkflowRun {
    /** Waits for the run's outcome. Every caller attached to the same run gets the same outcome. */
    suspend fun attach(): FeeOutcome

    /**
     * Asks Restate once, without waiting, for the run's outcome: `null` exactly when Restate answers 470 (not ready),
     * that is, when Restate itself says the run hasn't finished.
     */
    suspend fun output(): FeeOutcome?
}

/** [FeeWorkflowClients] over the ingress [client], through the Kotlin SDK's typed requests. */
private class IngressFeeWorkflowClients(
    private val client: Client,
) : FeeWorkflowClients {
    override suspend fun submit(
        transactionId: String,
        request: FeeRequest,
    ): FeeWorkflowRun {
        val sent = client.toWorkflow<FeeWorkflow>(transactionId).request { run(request) }.send()
        return IngressFeeWorkflowRun(transactionId, sent)
    }
}

/** The run Restate acknowledged with [sent]. The SDK reads Restate's 470 as `Output.notReady()`, every other error throws. */
private class IngressFeeWorkflowRun(
    private val transactionId: String,
    private val sent: SendResponse<FeeOutcome>,
) : FeeWorkflowRun {
    override suspend fun attach(): FeeOutcome = outcome(sent.attachSuspend().response())

    override suspend fun output(): FeeOutcome? {
        val output = sent.getOutputSuspend().response()
        return if (output.isReady) outcome(output.value) else null
    }

    private fun outcome(outcome: FeeOutcome?): FeeOutcome = checkNotNull(outcome) { "Workflow $transactionId returned no outcome" }
}
