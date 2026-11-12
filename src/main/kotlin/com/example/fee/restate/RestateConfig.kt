@file:Suppress("DEPRECATION") // the ctx-based codegen API of SDK 2.9.3, kept on purpose (D29)

package com.example.fee.restate

import dev.restate.client.Client
import dev.restate.serde.jackson.JacksonSerdeFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * The app's side of the Restate ingress: one shared [Client] and the keyed factories `FeeService` uses to reach the
 * generated clients. Creating the client opens no connection, so startup doesn't need Restate to be up.
 *
 * `@EnableScheduling` makes Spring Boot provide its `TaskScheduler` bean, on which [RestateRegistrar] retries.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class RestateConfig {

    // The serde factory the generated code uses (D35), so the ingress and the endpoint read the same JSON.
    @Bean
    fun restateIngressClient(properties: RestateProperties): Client =
        Client.connect(properties.ingressUrl, JacksonSerdeFactory())

    @Bean
    fun feeWorkflowClients(restateIngressClient: Client): FeeWorkflowClients =
        FeeWorkflowClients { workflowKey -> FeeWorkflowClient.fromClient(restateIngressClient, workflowKey) }

    @Bean
    fun transactionFeeRecordClients(restateIngressClient: Client): TransactionFeeRecordClients =
        TransactionFeeRecordClients { transactionId -> TransactionFeeRecordClient.fromClient(restateIngressClient, transactionId) }
}
