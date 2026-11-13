package com.example.fee.restate

import com.example.fee.contract.FeeRequest
import com.example.fee.workflow.FeeOutcome
import dev.restate.client.Client
import dev.restate.client.RequestOptions
import dev.restate.client.Response
import dev.restate.client.ResponseHead
import dev.restate.client.SendResponse
import dev.restate.common.Output
import dev.restate.common.Request
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.isNull
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.concurrent.CompletableFuture

class FeeWorkflowClientsTest {
    private val request = FeeRequest(transactionId = "txn_001")

    /**
     * An ingress [Client] that acknowledges every send with [status], for a run that ends with [outcome]. Asked for the
     * run's output, it answers [output]: 470 when that isn't ready, as Restate does, else 200.
     */
    @Suppress("UNCHECKED_CAST")
    private fun ingressAnswering(
        status: SendResponse.SendStatus,
        outcome: FeeOutcome,
        output: Output<FeeOutcome> = Output.ready(outcome),
    ): Client {
        val run = mock(Client.InvocationHandle::class.java) as Client.InvocationHandle<FeeOutcome>
        `when`(run.attachAsync(any(RequestOptions::class.java)))
            .thenReturn(CompletableFuture.completedFuture(Response(200, mock(ResponseHead.Headers::class.java), outcome)))
        val outputStatus = if (output.isReady) 200 else 470
        `when`(run.getOutputAsync(any(RequestOptions::class.java)))
            .thenReturn(CompletableFuture.completedFuture(Response(outputStatus, mock(ResponseHead.Headers::class.java), output)))
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
    fun `submits the request to the run handler of the FeeWorkflow keyed by the transaction id`() =
        runBlocking<Unit> {
            // given
            val client = ingressAnswering(SendResponse.SendStatus.ACCEPTED, FeeOutcome.Conflict("txn_001"))
            val clients = IngressFeeWorkflowClients(client)

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
    fun `attaching returns the outcome of the run Restate acknowledged`() =
        runBlocking<Unit> {
            // given
            val client = ingressAnswering(SendResponse.SendStatus.ACCEPTED, FeeOutcome.Conflict("txn_001"))
            val run = IngressFeeWorkflowClients(client).submit("txn_001", request)

            // when
            val outcome = run.attach()

            // then
            assertThat(outcome).isEqualTo(FeeOutcome.Conflict("txn_001"))
        }

    @Test
    fun `a transaction id submitted before attaches to the first run, whatever the new payload`() =
        runBlocking<Unit> {
            // given: Restate already has a run for the key, so it ignores the payload and names that run
            val client = ingressAnswering(SendResponse.SendStatus.PREVIOUSLY_ACCEPTED, FeeOutcome.Conflict("txn_001"))
            val run = IngressFeeWorkflowClients(client).submit("txn_001", request.copy(asset = "EUR"))

            // when
            val outcome = run.attach()

            // then
            assertThat(outcome).isEqualTo(FeeOutcome.Conflict("txn_001"))
        }

    @Test
    fun `a run Restate answers 470 for, not ready, has no output yet`() =
        runBlocking<Unit> {
            // given
            val client = ingressAnswering(SendResponse.SendStatus.ACCEPTED, FeeOutcome.Conflict("txn_001"), Output.notReady())
            val run = IngressFeeWorkflowClients(client).submit("txn_001", request)

            // when
            val output = run.output()

            // then
            assertThat(output).isNull()
        }

    @Test
    fun `the output of a finished run is its outcome`() =
        runBlocking<Unit> {
            // given
            val client = ingressAnswering(SendResponse.SendStatus.PREVIOUSLY_ACCEPTED, FeeOutcome.Conflict("txn_001"))
            val run = IngressFeeWorkflowClients(client).submit("txn_001", request)

            // when
            val output = run.output()

            // then
            assertThat(output).isEqualTo(FeeOutcome.Conflict("txn_001"))
        }
}
