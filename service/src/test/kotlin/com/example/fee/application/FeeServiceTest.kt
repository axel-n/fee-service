@file:Suppress("DEPRECATION") // the ctx-based codegen API of SDK 2.9.3

package com.example.fee.application

import com.example.fee.config.RestateProperties
import com.example.fee.contract.FeeRequest
import com.example.fee.contract.FeeResponse
import com.example.fee.contract.FeeStatusResponse
import com.example.fee.contract.error.DuplicateTransactionException
import com.example.fee.contract.error.FeeException
import com.example.fee.contract.error.ValidationException
import com.example.fee.contract.error.WorkflowUnavailableException
import com.example.fee.restate.FeeWorkflowClients
import com.example.fee.validation.ValidationError
import com.example.fee.validation.rule.TransactionIdValidator
import com.example.fee.workflow.FeeOutcome
import com.example.fee.workflow.FeeWorkflowClient
import com.example.fee.workflow.FeeWorkflowInput
import dev.restate.client.Client
import dev.restate.client.IngressException
import dev.restate.client.RequestOptions
import dev.restate.client.Response
import dev.restate.client.ResponseHead
import dev.restate.client.SendResponse
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.http.HttpStatus
import java.math.BigDecimal
import java.net.ConnectException
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeoutException

class FeeServiceTest {

    private val request = FeeRequest(
        transactionId = "txn_001",
        amount = BigDecimal("1000"),
        asset = "USD",
        assetType = "FIAT",
        type = "Mobile Top Up",
        state = "SETTLED - PENDING FEE",
        createdAt = "2023-08-30 15:42:17.610059",
    )

    private val response = FeeResponse(
        transactionId = "txn_001",
        amount = BigDecimal("1000"),
        asset = "USD",
        type = "Mobile Top Up",
        fee = BigDecimal("1.50"),
        rate = BigDecimal("0.0015"),
        description = "Standard fee rate of 0.15%",
    )

    private val workflowClient = mock(FeeWorkflowClient.IngressClient::class.java)
    private val invocation = mock(Client.InvocationHandle::class.java)
    private val clients = mock(FeeWorkflowClients::class.java).also {
        `when`(it.forKey(ArgumentMatchers.anyString())).thenReturn(workflowClient)
    }


    private fun service(attachTimeout: Duration = Duration.ofSeconds(5)) = FeeService(
        TransactionIdValidator(),
        clients,
        RestateProperties(
            ingressUrl = "http://localhost:8080",
            endpointPort = 9080,
            attachTimeout = attachTimeout,
        ),
    )

    @Suppress("UNCHECKED_CAST")
    private fun sendResponse(status: SendResponse.SendStatus): SendResponse<FeeOutcome> =
        SendResponse(
            200,
            mock(ResponseHead.Headers::class.java),
            status,
            invocation as Client.InvocationHandle<FeeOutcome>,
        )

    @Suppress("UNCHECKED_CAST")
    private fun attachReturns(future: CompletableFuture<Response<FeeOutcome>>) {
        `when`(invocation.attachAsync(anyOptions())).thenReturn(future as CompletableFuture<Response<Any?>>)
    }

    private fun submitReturns(future: CompletableFuture<SendResponse<FeeOutcome>>) {
        `when`(workflowClient.submitAsync(ArgumentMatchers.any(FeeWorkflowInput::class.java)))
            .thenReturn(future)
    }

    private fun workflowAnswers(outcome: FeeOutcome, status: SendResponse.SendStatus = SendResponse.SendStatus.ACCEPTED) {
        submitReturns(CompletableFuture.completedFuture(sendResponse(status)))
        attachReturns(CompletableFuture.completedFuture(Response(200, mock(ResponseHead.Headers::class.java), outcome)))
    }

    private fun anyOptions(): RequestOptions? = ArgumentMatchers.any(RequestOptions::class.java)

    private fun ingressDown(): IngressException =
        IngressException("Error when executing the request", "POST", "http://localhost:8080", -1, null, ConnectException())

    @Test
    fun `a charged outcome completes with the workflow's response`() {
        // given
        workflowAnswers(FeeOutcome.Charged(response))

        // when
        val submission = service().submit(request)

        // then
        assertThat(submission).isEqualTo(FeeStatusResponse.completed(response))
    }

    @Test
    fun `submits to the workflow keyed by transaction id and payload hash with the request and its hash`() {
        // given
        workflowAnswers(FeeOutcome.Charged(response))
        val hash = PayloadHash.of(request)

        // when
        service().submit(request)

        // then
        verify(clients).forKey("txn_001:${hash.value}")
        val input = ArgumentCaptor.forClass(FeeWorkflowInput::class.java)
        verify(workflowClient).submitAsync(input.capture())
        assertThat(input.value).isEqualTo(FeeWorkflowInput(request, hash.value))
    }

    @Test
    fun `the same payload with the amount written differently goes to the same workflow`() {
        // given
        workflowAnswers(FeeOutcome.Charged(response))
        val service = service()

        // when
        service.submit(request)
        service.submit(request.copy(amount = BigDecimal("1000.00")))

        // then
        verify(clients, times(2)).forKey("txn_001:${PayloadHash.of(request).value}")
    }

    @Test
    fun `a previously accepted payload attaches to the first run and returns its result`() {
        // given
        workflowAnswers(FeeOutcome.Charged(response), SendResponse.SendStatus.PREVIOUSLY_ACCEPTED)

        // when
        val submission = service().submit(request)

        // then
        assertThat(submission).isEqualTo(FeeStatusResponse.completed(response))
        verify(invocation).attachAsync(anyOptions())
    }

    @Test
    fun `a malformed request rejected by the workflow is a 400 validation exception`() {
        // given
        val error = ValidationError("amount", "-5", "Amount -5 must be greater than 0", ValidationError.Kind.INVALID)
        workflowAnswers(FeeOutcome.Rejected(error))

        // when / then
        assertThatThrownBy { service().submit(request) }
            .isInstanceOfSatisfying(ValidationException::class.java) {
                assertThat(listOf(it.field, it.value, it.message)).containsExactly(error.field, error.value, error.message)
                assertThat(it.kind).isEqualTo(ValidationException.Kind.INVALID)
                assertThat(it.status).isEqualTo(HttpStatus.BAD_REQUEST.value())
            }
    }

    @Test
    fun `an unsupported request rejected by the workflow is a 422 validation exception`() {
        // given
        val error = ValidationError(
            "state",
            "PENDING",
            "Unsupported state 'PENDING'. Supported: 'SETTLED - PENDING FEE'",
            ValidationError.Kind.UNSUPPORTED,
        )
        workflowAnswers(FeeOutcome.Rejected(error))

        // when / then
        assertThatThrownBy { service().submit(request) }
            .isInstanceOfSatisfying(ValidationException::class.java) {
                assertThat(listOf(it.field, it.value, it.message)).containsExactly(error.field, error.value, error.message)
                assertThat(it.kind).isEqualTo(ValidationException.Kind.UNSUPPORTED)
                assertThat(it.status).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY.value())
            }
    }

    @Test
    fun `a conflict is a 409 duplicate transaction exception`() {
        // given
        workflowAnswers(FeeOutcome.Conflict("txn_001"))

        // when / then
        assertThatThrownBy { service().submit(request) }
            .isInstanceOfSatisfying(DuplicateTransactionException::class.java) {
                assertThat(it.transactionId).isEqualTo("txn_001")
                assertThat(it.status).isEqualTo(HttpStatus.CONFLICT.value())
            }
    }

    @Test
    fun `a missing transaction id is a 400 before Restate is called`() {
        // when / then
        assertThatThrownBy { service().submit(request.copy(transactionId = null)) }
            .isInstanceOfSatisfying(ValidationException::class.java) {
                assertThat(it.field).isEqualTo("transaction_id")
                assertThat(it.status).isEqualTo(HttpStatus.BAD_REQUEST.value())
            }
        verifyNoInteractions(clients)
    }

    @Test
    fun `a transaction id that can't go into the key is a 400 naming the value before Restate is called`() {
        // when / then
        assertThatThrownBy { service().submit(request.copy(transactionId = "txn:001")) }
            .isInstanceOfSatisfying(ValidationException::class.java) {
                assertThat(it.field).isEqualTo("transaction_id")
                assertThat(it.value).isEqualTo("txn:001")
                assertThat(it.status).isEqualTo(HttpStatus.BAD_REQUEST.value())
            }
        verifyNoInteractions(clients)
    }

    @Test
    fun `an unreachable ingress on submit is a 503`() {
        // given
        val down = ingressDown()
        submitReturns(CompletableFuture.failedFuture(CompletionException(down)))

        // when / then
        assertThatThrownBy { service().submit(request) }
            .isInstanceOfSatisfying(WorkflowUnavailableException::class.java) {
                assertThat(it.status).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value())
                assertThat(it.cause).isSameAs(down)
            }
        verify(invocation, never()).attachAsync(anyOptions())
    }

    @Test
    fun `a submit that throws right away is a 503`() {
        // given
        val down = ingressDown()
        `when`(workflowClient.submitAsync(ArgumentMatchers.any(FeeWorkflowInput::class.java)))
            .thenThrow(down)

        // when / then
        assertThatThrownBy { service().submit(request) }
            .isInstanceOfSatisfying(WorkflowUnavailableException::class.java) {
                assertThat(it.cause).isSameAs(down)
            }
    }

    @Test
    fun `an attach that fails after the submit was accepted is a 503`() {
        // given
        val down = ingressDown()
        submitReturns(CompletableFuture.completedFuture(sendResponse(SendResponse.SendStatus.ACCEPTED)))
        attachReturns(CompletableFuture.failedFuture(down))

        // when / then
        assertThatThrownBy { service().submit(request) }
            .isInstanceOfSatisfying(WorkflowUnavailableException::class.java) {
                assertThat(it.cause).isSameAs(down)
            }
    }

    @Test
    fun `no result within the attach timeout is an in progress exception with the transaction id`() {
        // given: the workflow was accepted but never finishes
        submitReturns(CompletableFuture.completedFuture(sendResponse(SendResponse.SendStatus.ACCEPTED)))
        attachReturns(CompletableFuture())

        // when / then
        assertThatThrownBy { service(attachTimeout = Duration.ofMillis(50)).submit(request) }
            .isInstanceOfSatisfying(FeeInProgressException::class.java) {
                assertThat(it.transactionId).isEqualTo("txn_001")
            }
    }

    @Test
    fun `a submit Restate doesn't acknowledge within the attach timeout is a 503`() {
        // given: a frozen Restate accepts the connection but never answers
        submitReturns(CompletableFuture())

        // when / then
        assertThatThrownBy { service(attachTimeout = Duration.ofMillis(50)).submit(request) }
            .isInstanceOfSatisfying(WorkflowUnavailableException::class.java) {
                assertThat(it.cause).isInstanceOf(TimeoutException::class.java)
            }
        verify(invocation, never()).attachAsync(anyOptions())
    }

    @Test
    fun `an interrupted wait is a 503 and keeps the thread's interrupt flag`() {
        // given
        submitReturns(CompletableFuture())
        Thread.currentThread().interrupt()

        // when / then
        try {
            assertThatThrownBy { service().submit(request) }
                .isInstanceOf(WorkflowUnavailableException::class.java)
            assertThat(Thread.currentThread().isInterrupted).isTrue()
        } finally {
            Thread.interrupted()
        }
    }


}

class FeeInProgressExceptionTest {

    @Test
    fun `carries the transaction id of the run still in progress`() {
        // given
        val exception = FeeInProgressException("txn_001")

        // when
        val transactionId = exception.transactionId

        // then
        assertThat(transactionId).isEqualTo("txn_001")
    }

    @Test
    fun `names the transaction in a readable message`() {
        // given
        val exception = FeeInProgressException("txn_001")

        // when
        val message = exception.message

        // then
        assertThat(message).isEqualTo("The fee for transaction 'txn_001' is still being processed")
    }

    @Test
    fun `is not a fee exception, so it never becomes a problem body`() {
        // given
        val exception: RuntimeException = FeeInProgressException("txn_001")

        // when
        val isFailure = exception is FeeException

        // then
        assertThat(isFailure).isFalse()
    }

    @Test
    fun `has no stack trace because a slow run is expected, not a bug`() {
        // given
        val exception = FeeInProgressException("txn_001")

        // when
        val stackTrace = exception.stackTrace

        // then
        assertThat(stackTrace).isEmpty()
    }
}
