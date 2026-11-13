package com.example.fee.config

import com.example.fee.restate.IngressFeeWorkflowClients
import dev.restate.client.Client
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.time.Duration

class RestateConfigTest {
    private val config = RestateConfig()

    @Test
    fun `creates the ingress client without contacting Restate`() {
        // given: nothing listens on this port
        val properties =
            RestateProperties(
                ingressUrl = "http://127.0.0.1:1",
                adminUrl = "http://localhost:9070",
                endpointPort = 9_080,
                advertisedHost = "localhost",
                attachTimeout = Duration.ofSeconds(10),
            )

        // when
        val client = config.restateIngressClient(properties)

        // then
        assertThat(client).isNotNull()
    }

    @Test
    fun `FeeService reaches the workflow through the ingress client`() {
        // given
        val client = mock(Client::class.java)

        // when
        val clients = config.feeWorkflowClients(client)

        // then
        assertThat(clients).isInstanceOf(IngressFeeWorkflowClients::class.java)
    }
}
