package com.example.fee.restate

import com.example.fee.config.RestateProperties
import com.example.fee.contract.FeeRequest
import com.example.fee.workflow.FeeOutcome
import dev.restate.client.Client
import dev.restate.client.RequestOptions
import dev.restate.client.Response
import dev.restate.client.ResponseHead
import dev.restate.client.SendResponse
import dev.restate.common.Request
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.isNull
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import java.time.Duration
import java.util.concurrent.CompletableFuture

class RestateConfigTest {

    private val config = RestateConfig()

    private val request = FeeRequest(transactionId = "txn_001")

    private fun properties(ingressUrl: String) = RestateProperties(
        ingressUrl = ingressUrl,
        endpointPort = 9080,
        attachTimeout = Duration.ofSeconds(10),
    )

    /** An ingress [Client] that acknowledges every send with [status], for a run that ends with [outcome]. */
    @Suppress("UNCHECKED_CAST")
    private fun ingressAnswering(status: SendResponse.SendStatus, outcome: FeeOutcome): Client {
        val run = mock(Client.InvocationHandle::class.java) as Client.InvocationHandle<FeeOutcome>
        `when`(run.attachAsync(any(RequestOptions::class.java)))
            .thenReturn(CompletableFuture.completedFuture(Response(200, mock(ResponseHead.Headers::class.java), outcome)))
        val sent = SendResponse(200, mock(ResponseHead.Headers::class.java), status, run)
        val client = mock(Client::class.java)
        `when`(client.sendAsync(any(Request::class.java) as Request<Any?, FeeOutcome>?, isNull()))
            .thenReturn(CompletableFuture.completedFuture(sent))
        return client
    }

    private fun sentRequest(client: Client): Request<*, *> {
        val captor = ArgumentCaptor.forClass(Request::class.java)
        verify(client).sendAsync(captor.capture(), isNull())
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
    fun `submits the request to the run handler of the FeeWorkflow keyed by the transaction id`() = runBlocking<Unit> {
        // given
        val client = ingressAnswering(SendResponse.SendStatus.ACCEPTED, FeeOutcome.Conflict("txn_001"))
        val clients = config.feeWorkflowClients(client)

        // when
        clients.submit("txn_001", request)

        // then
        val sent = sentRequest(client)
        assertThat(sent.target.service).isEqualTo("FeeWorkflow")
        assertThat(sent.target.key).isEqualTo("txn_001")
        assertThat(sent.target.handler).isEqualTo("run")
        assertThat(sent.request).isEqualTo(request)
    }

    @Test
    fun `attaching returns the outcome of the run Restate acknowledged`() = runBlocking<Unit> {
        // given
        val client = ingressAnswering(SendResponse.SendStatus.ACCEPTED, FeeOutcome.Conflict("txn_001"))
        val run = config.feeWorkflowClients(client).submit("txn_001", request)

        // when
        val outcome = run.attach()

        // then
        assertThat(outcome).isEqualTo(FeeOutcome.Conflict("txn_001"))
    }

    @Test
    fun `a transaction id submitted before attaches to the first run, whatever the new payload`() = runBlocking<Unit> {
        // given: Restate already has a run for the key, so it ignores the payload and names that run
        val client = ingressAnswering(SendResponse.SendStatus.PREVIOUSLY_ACCEPTED, FeeOutcome.Conflict("txn_001"))
        val run = config.feeWorkflowClients(client).submit("txn_001", request.copy(asset = "EUR"))

        // when
        val outcome = run.attach()

        // then
        assertThat(outcome).isEqualTo(FeeOutcome.Conflict("txn_001"))
    }
}
