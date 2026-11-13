package com.example.fee.config

import com.example.fee.restate.FeeWorkflowClients
import com.example.fee.restate.IngressFeeWorkflowClients
import com.example.fee.restate.serde.KotlinJacksonSerdeFactory
import dev.restate.client.Client
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
