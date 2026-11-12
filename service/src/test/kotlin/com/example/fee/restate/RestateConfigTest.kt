@file:Suppress("DEPRECATION") // the ctx-based codegen API of SDK 2.9.3

package com.example.fee.restate

import com.example.fee.config.RestateProperties
import com.example.fee.contract.FeeRequest
import com.example.fee.workflow.FeeWorkflow
import com.example.fee.workflow.FeeWorkflowHandlers
import dev.restate.client.Client
import dev.restate.common.Request
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import java.time.Duration

class RestateConfigTest {

    private val config = RestateConfig()

    private fun properties(ingressUrl: String) = RestateProperties(
        ingressUrl = ingressUrl,
        endpointPort = 9080,
        attachTimeout = Duration.ofSeconds(10),
    )

    private fun sentRequest(client: Client): Request<*, *> {
        val captor = ArgumentCaptor.forClass(Request::class.java)
        verify(client).send(captor.capture())
        return captor.value
    }

    @Test
    fun `creates the ingress client without contacting Restate`() {
        // given: nothing listens on this port
        val properties = properties("http://127.0.0.1:1")

        // when
        val client = config.restateIngressClient(properties)

        // then
        assertThat(client).isNotNull()
    }

    @Test
    fun `workflow client factory addresses the FeeWorkflow keyed by the transaction id`() {
        // given
        val client = mock(Client::class.java)
        val factory = config.feeWorkflowClients(client)

        // when
        factory.forTransaction("txn_001").submit(FeeRequest(transactionId = "txn_001"))

        // then
        val target = sentRequest(client).target
        assertThat(target.service).isEqualTo(FeeWorkflowHandlers.Metadata.SERVICE_NAME)
        assertThat(target.key).isEqualTo("txn_001")
        assertThat(target.handler).isEqualTo("run")
    }

    @Test
    fun `workflow client factory creates a client per transaction`() {
        // given
        val client = mock(Client::class.java)
        val factory = config.feeWorkflowClients(client)

        // when
        val first = factory.forTransaction("txn_001")
        val second = factory.forTransaction("txn_002")

        // then
        assertThat(first).isNotSameAs(second)
    }
}
