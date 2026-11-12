@file:Suppress("DEPRECATION") // the ctx-based codegen API of SDK 2.9.3, kept on purpose

package com.example.fee.restate

import com.example.fee.config.RestateProperties

import com.example.fee.workflow.FeeWorkflowClient
import dev.restate.client.Client
import dev.restate.serde.jackson.JacksonSerdeFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The app's side of the Restate ingress: one shared [Client] and the keyed factory `FeeService` uses to reach the
 * generated workflow client. Creating the client opens no connection, so startup doesn't need Restate to be up.
 */
@Configuration(proxyBeanMethods = false)
class RestateConfig {

    // The serde factory the generated code uses, so the ingress and the endpoint read the same JSON.
    @Bean
    fun restateIngressClient(properties: RestateProperties): Client =
        Client.connect(properties.ingressUrl, JacksonSerdeFactory())

    @Bean
    fun feeWorkflowClients(restateIngressClient: Client): FeeWorkflowClients =
        FeeWorkflowClients { transactionId -> FeeWorkflowClient.fromClient(restateIngressClient, transactionId) }

}

/**
 * Creates the generated ingress client of `FeeWorkflow` for one transaction: the workflow key is the `transaction_id`.
 * Generated clients are bound to a key, so `FeeService` gets this factory instead of a client, and tests replace it
 * with a mock.
 */
fun interface FeeWorkflowClients {

    /** The ingress client of the workflow `FeeWorkflow/{transactionId}`. */
    fun forTransaction(transactionId: String): FeeWorkflowClient.IngressClient
}

