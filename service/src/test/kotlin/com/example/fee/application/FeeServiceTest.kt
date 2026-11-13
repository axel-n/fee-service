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
import com.example.fee.restate.FeeWorkflowRun
import com.example.fee.validation.ValidationError
import com.example.fee.validation.rule.TransactionIdValidator
import com.example.fee.workflow.FeeOutcome
import dev.restate.client.IngressException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import java.math.BigDecimal
import java.net.ConnectException
import java.time.Duration
import kotlin.coroutines.cancellation.CancellationException

class FeeServiceTest {
    private val request =
        FeeRequest(
            transactionId = "txn_001",
            amount = BigDecimal("1000"),
            asset = "USD",
            assetType = "FIAT",
            type = "Mobile Top Up",
            state = "SETTLED - PENDING FEE",
            createdAt = "2023-08-30 15:42:17.610059",
        )

    private val response =
        FeeResponse(
            transactionId = "txn_001",
            amount = BigDecimal("1000"),
            asset = "USD",
            type = "Mobile Top Up",
            fee = BigDecimal("1.50"),
            rate = BigDecimal("0.0015"),
            description = "Standard fee rate of 0.15%",
        )

    private fun service(
        clients: FeeWorkflowClients,
        attachTimeout: Duration = Duration.ofSeconds(5),
    ) = FeeService(
        TransactionIdValidator(),
        clients,
        RestateProperties(
            ingressUrl = "http://localhost:8080",
            adminUrl = "http://localhost:9070",
            endpointPort = 9080,
            advertisedHost = "localhost",
            attachTimeout = attachTimeout,
        ),
    )

    private fun ingressDown(): IngressException =
        IngressException("Error when executing the request", "POST", "http://localhost:8080", -1, null, ConnectException())

    // Runs submit in a coroutine; a failure comes back as the exception, so assertThatThrownBy can inspect it
    private fun submitting(
        service: FeeService,
        request: FeeRequest,
    ) = ThrowingCallable { runBlocking { service.submit(request) } }

    @Test
    fun `a charged outcome completes with the workflow's response`() =
        runBlocking<Unit> {
            // given
            val clients = FakeClients(attach = { FeeOutcome.Charged(response) })

            // when
            val submission = service(clients).submit(request)

            // then
            assertThat(submission).isEqualTo(FeeStatusResponse.completed(response))
        }

    @Test
    fun `submits the request as sent to the workflow keyed by its transaction id`() =
        runBlocking<Unit> {
            // given
            val clients = FakeClients(attach = { FeeOutcome.Charged(response) })

            // when
            service(clients).submit(request)

            // then
            assertThat(clients.submitted).containsExactly("txn_001" to request)
        }

    @Test
    fun `a changed payload with the same transaction id goes to the same workflow`() =
        runBlocking<Unit> {
            // given
            val clients = FakeClients(attach = { FeeOutcome.Charged(response) })
            val service = service(clients)

            // when
            service.submit(request)
            service.submit(request.copy(amount = BigDecimal("2000")))

            // then
            assertThat(clients.submitted.map { it.first }).containsExactly("txn_001", "txn_001")
        }

    @Test
    fun `attaches to the run Restate returns, so a transaction id sent again gets the first run's result`() =
        runBlocking<Unit> {
            // given: Restate hands back the first run for a known key
            val clients = FakeClients(attach = { FeeOutcome.Charged(response) })

            // when
            val submission = service(clients).submit(request.copy(amount = BigDecimal("2000")))

            // then
            assertThat(submission).isEqualTo(FeeStatusResponse.completed(response))
            assertThat(clients.attaches).isEqualTo(1)
        }

    @Test
    fun `a malformed request rejected by the workflow is a 400 validation exception`() {
        // given
        val error = ValidationError("amount", "-5", "Amount -5 must be greater than 0", ValidationError.Kind.INVALID)
        val clients = FakeClients(attach = { FeeOutcome.Rejected(error) })

        // when / then
        assertThatThrownBy(submitting(service(clients), request))
            .isInstanceOfSatisfying(ValidationException::class.java) {
                assertThat(listOf(it.field, it.value, it.message)).containsExactly(error.field, error.value, error.message)
                assertThat(it.kind).isEqualTo(ValidationException.Kind.INVALID)
                assertThat(it.status).isEqualTo(HttpStatus.BAD_REQUEST.value())
            }
    }

    @Test
    fun `an unsupported request rejected by the workflow is a 422 validation exception`() {
        // given
        val error =
            ValidationError(
                "state",
                "PENDING",
                "Unsupported state 'PENDING'. Supported: 'SETTLED - PENDING FEE'",
                ValidationError.Kind.UNSUPPORTED,
            )
        val clients = FakeClients(attach = { FeeOutcome.Rejected(error) })

        // when / then
        assertThatThrownBy(submitting(service(clients), request))
            .isInstanceOfSatisfying(ValidationException::class.java) {
                assertThat(listOf(it.field, it.value, it.message)).containsExactly(error.field, error.value, error.message)
                assertThat(it.kind).isEqualTo(ValidationException.Kind.UNSUPPORTED)
                assertThat(it.status).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT.value())
            }
    }

    @Test
    fun `a conflict is a 409 duplicate transaction exception`() {
        // given
        val clients = FakeClients(attach = { FeeOutcome.Conflict("txn_001") })

        // when / then
        assertThatThrownBy(submitting(service(clients), request))
            .isInstanceOfSatisfying(DuplicateTransactionException::class.java) {
                assertThat(it.transactionId).isEqualTo("txn_001")
                assertThat(it.status).isEqualTo(HttpStatus.CONFLICT.value())
            }
    }

    @Test
    fun `a missing transaction id is a 400 before Restate is called`() {
        // given
        val clients = FakeClients(attach = { FeeOutcome.Charged(response) })

        // when / then
        assertThatThrownBy(submitting(service(clients), request.copy(transactionId = null)))
            .isInstanceOfSatisfying(ValidationException::class.java) {
                assertThat(it.field).isEqualTo("transaction_id")
                assertThat(it.status).isEqualTo(HttpStatus.BAD_REQUEST.value())
            }
        assertThat(clients.submitted).isEmpty()
    }

    @Test
    fun `a transaction id that can't be a workflow key is a 400 naming the value before Restate is called`() {
        // given
        val clients = FakeClients(attach = { FeeOutcome.Charged(response) })

        // when / then
        assertThatThrownBy(submitting(service(clients), request.copy(transactionId = "txn:001")))
            .isInstanceOfSatisfying(ValidationException::class.java) {
                assertThat(it.field).isEqualTo("transaction_id")
                assertThat(it.value).isEqualTo("txn:001")
                assertThat(it.status).isEqualTo(HttpStatus.BAD_REQUEST.value())
            }
        assertThat(clients.submitted).isEmpty()
    }

    @Test
    fun `an unreachable ingress on submit is a 503`() {
        // given
        val down = ingressDown()
        val clients = FakeClients(submit = { throw down }, attach = { FeeOutcome.Charged(response) })

        // when / then
        assertThatThrownBy(submitting(service(clients), request))
            .isInstanceOfSatisfying(WorkflowUnavailableException::class.java) {
                assertThat(it.status).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value())
                assertThat(it.cause).isSameAs(down)
            }
        assertThat(clients.attaches).isZero()
    }

    @Test
    fun `an attach that fails after the submit was accepted is a 503`() {
        // given
        val down = ingressDown()
        val clients = FakeClients(attach = { throw down })

        // when / then
        assertThatThrownBy(submitting(service(clients), request))
            .isInstanceOfSatisfying(WorkflowUnavailableException::class.java) {
                assertThat(it.cause).isSameAs(down)
            }
    }

    @Test
    fun `a run Restate reports as not ready after the attach timeout is an in progress exception with the transaction id`() {
        // given: the workflow was accepted but never finishes, and Restate answers its output with 470
        val clients = FakeClients(attach = { awaitCancellation() }, output = { null })

        // when / then
        assertThatThrownBy(submitting(service(clients, attachTimeout = Duration.ofMillis(50)), request))
            .isInstanceOfSatisfying(FeeInProgressException::class.java) {
                assertThat(it.transactionId).isEqualTo("txn_001")
            }
        assertThat(clients.outputs).isEqualTo(1)
    }

    @Test
    fun `a run that finished just after the attach timeout answers its outcome, not 202`() =
        runBlocking<Unit> {
            // given: the attach outlasts the timeout, but Restate already has the output when asked
            val clients = FakeClients(attach = { awaitCancellation() }, output = { FeeOutcome.Charged(response) })

            // when
            val submission = service(clients, attachTimeout = Duration.ofMillis(50)).submit(request)

            // then
            assertThat(submission).isEqualTo(FeeStatusResponse.completed(response))
        }

    @Test
    fun `an output request that fails after the attach timeout is a 503, not 202`() {
        // given
        val down = ingressDown()
        val clients = FakeClients(attach = { awaitCancellation() }, output = { throw down })

        // when / then
        assertThatThrownBy(submitting(service(clients, attachTimeout = Duration.ofMillis(50)), request))
            .isInstanceOfSatisfying(WorkflowUnavailableException::class.java) {
                assertThat(it.cause).isSameAs(down)
            }
    }

    @Test
    fun `a run that finishes within the attach timeout never asks Restate for its output`() =
        runBlocking<Unit> {
            // given
            val clients = FakeClients(attach = { FeeOutcome.Charged(response) })

            // when
            service(clients).submit(request)

            // then
            assertThat(clients.outputs).isZero()
        }

    @Test
    fun `a request cancelled while it waits stays a cancellation and is not reported as a 503`() =
        runBlocking<Unit> {
            // given: the workflow was accepted but never finishes, and the caller is waiting for it
            val clients = FakeClients(attach = { awaitCancellation() })
            val submission = async { service(clients).submit(request) }
            clients.attaching.await()

            // when
            submission.cancel()
            val failure = runCatching { submission.await() }.exceptionOrNull()

            // then
            assertThat(failure).isInstanceOf(CancellationException::class.java)
        }

    /**
     * The ingress in memory: [submit] runs on every submission, which the fake records, and [attach] answers the run's
     * outcome, or suspends until cancelled, as a run that never finishes does. [output] answers Restate's one-off
     * output request: `null` is Restate's 470 (not ready).
     */
    private class FakeClients(
        private val submit: suspend () -> Unit = {},
        private val attach: suspend () -> FeeOutcome,
        private val output: suspend () -> FeeOutcome? = { null },
    ) : FeeWorkflowClients {
        val submitted = mutableListOf<Pair<String, FeeRequest>>()
        var attaches = 0
            private set
        var outputs = 0
            private set

        /** Completed once an attach started waiting. */
        val attaching = CompletableDeferred<Unit>()

        override suspend fun submit(
            transactionId: String,
            request: FeeRequest,
        ): FeeWorkflowRun {
            submitted += transactionId to request
            submit()
            return object : FeeWorkflowRun {
                override suspend fun attach(): FeeOutcome {
                    attaches++
                    attaching.complete(Unit)
                    return this@FakeClients.attach()
                }

                override suspend fun output(): FeeOutcome? {
                    outputs++
                    return this@FakeClients.output()
                }
            }
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
