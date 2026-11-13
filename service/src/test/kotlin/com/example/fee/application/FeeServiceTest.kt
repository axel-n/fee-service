package com.example.fee.application

import com.example.fee.config.FeeCacheProperties
import com.example.fee.config.RestateProperties
import com.example.fee.contract.FeeRequest
import com.example.fee.contract.FeeResponse
import com.example.fee.contract.error.DuplicateTransactionException
import com.example.fee.contract.error.ValidationException
import com.example.fee.contract.error.WorkflowTimeoutException
import com.example.fee.contract.error.WorkflowUnavailableException
import com.example.fee.record.FeeRecord
import com.example.fee.record.InMemoryFeeRecordRepository
import com.example.fee.restate.FeeWorkflowClients
import com.example.fee.restate.FeeWorkflowRun
import com.example.fee.validation.ValidationError
import com.example.fee.validation.rule.TransactionIdValidator
import com.example.fee.workflow.FeeOutcome
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import dev.restate.client.IngressException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
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

    // What the API answers for [response]: the fee without trailing zeros. Data-class equality compares BigDecimal
    // with equals, so it tells 1.5 from 1.50.
    private val answered = response.copy(fee = BigDecimal("1.5"))

    private val records = InMemoryFeeRecordRepository()

    private val cache: Cache<String, FeeResponse> = Caffeine.newBuilder().build()

    private fun service(clients: FeeWorkflowClients, attachTimeout: Duration = Duration.ofSeconds(5)) =
        FeeService(
            TransactionIdValidator(),
            ChargedFees(cache, records, cacheProperties()),
            clients,
            restateProperties(attachTimeout),
        )

    private fun restateProperties(attachTimeout: Duration = Duration.ofSeconds(5)) =
        RestateProperties(
            ingressUrl = "http://localhost:8080",
            adminUrl = "http://localhost:9070",
            endpointPort = 9_080,
            advertisedHost = "localhost",
            attachTimeout = attachTimeout,
        )

    private fun cacheProperties(lookupTimeout: Duration = Duration.ofSeconds(1)) =
        FeeCacheProperties(maximumSize = 1_000, expireAfterWrite = Duration.ofDays(1), lookupTimeout = lookupTimeout)

    private fun ingressDown(): IngressException =
        IngressException("Error when executing the request", "POST", "http://localhost:8080", -1, null, ConnectException())

    // Runs submit in a coroutine; a failure comes back as the exception, so assertThatThrownBy can inspect it
    private fun submitting(service: FeeService, request: FeeRequest) = ThrowingCallable { runBlocking { service.submit(request) } }

    @Test
    fun `a charged outcome answers the workflow's response with the fee as in the requirements' sample`() =
        runBlocking<Unit> {
            // given
            val clients = FakeClients(attach = { FeeOutcome.Charged(response) })

            // when
            val submission = service(clients).submit(request)

            // then
            assertThat(submission).isEqualTo(answered)
        }

    @ParameterizedTest(name = "stored {0} → answered {1}")
    @CsvSource("1.50, 1.5", "10.30, 10.3", "2.00, 2", "50.00, 50", "1.01, 1.01", "0.38, 0.38", "0.00, 0")
    fun `answers the charged fee without trailing zeros, at the same value`(stored: BigDecimal, expected: String) =
        runBlocking<Unit> {
            // given
            val clients = FakeClients(attach = { FeeOutcome.Charged(response.copy(fee = stored)) })

            // when
            val fee = service(clients).submit(request).fee

            // then
            assertThat(fee.toPlainString()).isEqualTo(expected)
            assertThat(fee).isEqualByComparingTo(stored)
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
    fun `a changed payload with the same transaction id that isn't charged goes to the same workflow`() {
        // given: the first run rejected it, so nothing is charged
        val error = ValidationError("state", "PENDING", "Unsupported state 'PENDING'", ValidationError.Kind.UNSUPPORTED)
        val clients = FakeClients(attach = { FeeOutcome.Rejected(error) })
        val service = service(clients)

        // when
        runCatching { runBlocking { service.submit(request.copy(state = "PENDING")) } }
        runCatching { runBlocking { service.submit(request) } }

        // then
        assertThat(clients.submitted.map { it.first }).containsExactly("txn_001", "txn_001")
    }

    @Test
    fun `a charged transaction id is answered from the cache without asking Postgres or Restate, whatever its payload`() =
        runBlocking<Unit> {
            // given
            cache.put("txn_001", response)
            val clients = FakeClients(attach = { error("Restate must not be called") })

            // when
            val submission = service(clients).submit(request.copy(amount = BigDecimal("2000")))

            // then
            assertThat(submission).isEqualTo(answered)
            assertThat(records.calls).isEmpty()
            assertThat(clients.submitted).isEmpty()
        }

    @Test
    fun `a charged transaction id missing from the cache is answered from Postgres without Restate, and cached`() =
        runBlocking<Unit> {
            // given: charged earlier, by this instance before a restart or by another one
            records.records["txn_001"] = FeeRecord.inProgress("earlier-run").complete(response)
            val clients = FakeClients(attach = { error("Restate must not be called") })

            // when
            val submission = service(clients).submit(request)

            // then
            assertThat(submission).isEqualTo(answered)
            assertThat(clients.submitted).isEmpty()
            assertThat(cache.getIfPresent("txn_001")).isEqualTo(response)
        }

    @Test
    fun `a transaction id claimed but not charged yet goes to Restate`() =
        runBlocking<Unit> {
            // given: a run holds the claim and is still going
            records.records["txn_001"] = FeeRecord.inProgress("running")
            val clients = FakeClients(attach = { FeeOutcome.Charged(response) })

            // when
            val submission = service(clients).submit(request)

            // then
            assertThat(submission).isEqualTo(answered)
            assertThat(clients.submitted).containsExactly("txn_001" to request)
        }

    @Test
    fun `a charge from Restate is remembered, so the same transaction id sent again doesn't reach Restate`() =
        runBlocking<Unit> {
            // given
            val clients = FakeClients(attach = { FeeOutcome.Charged(response) })
            val service = service(clients)
            service.submit(request)

            // when
            val again = service.submit(request.copy(amount = BigDecimal("2000")))

            // then
            assertThat(again).isEqualTo(answered)
            assertThat(clients.submitted).hasSize(1)
            assertThat(cache.getIfPresent("txn_001")).isEqualTo(response)
        }

    @Test
    fun `a conflict is not remembered`() {
        // given
        val clients = FakeClients(attach = { FeeOutcome.Conflict("txn_001") })

        // when
        runCatching { runBlocking { service(clients).submit(request) } }

        // then
        assertThat(cache.asMap()).isEmpty()
    }

    @Test
    fun `a run not finished in time is not remembered`() {
        // given
        val clients = FakeClients(attach = { awaitCancellation() }, output = { null })

        // when
        runCatching { runBlocking { service(clients, attachTimeout = Duration.ofMillis(50)).submit(request) } }

        // then
        assertThat(cache.asMap()).isEmpty()
    }

    @Test
    fun `a rejection is not remembered`() {
        // given
        val error = ValidationError("state", "PENDING", "Unsupported state 'PENDING'", ValidationError.Kind.UNSUPPORTED)
        val clients = FakeClients(attach = { FeeOutcome.Rejected(error) })

        // when
        runCatching { runBlocking { service(clients).submit(request) } }

        // then
        assertThat(cache.asMap()).isEmpty()
    }

    @Test
    fun `a failed Postgres lookup leaves the answer to Restate`() =
        runBlocking<Unit> {
            // given
            val failingRecords =
                InMemoryFeeRecordRepository(failing = mapOf("findCharged" to IllegalStateException("database unreachable")))
            val clients = FakeClients(attach = { FeeOutcome.Charged(response) })
            val service =
                FeeService(TransactionIdValidator(), ChargedFees(cache, failingRecords, cacheProperties()), clients, restateProperties())

            // when
            val submission = service.submit(request)

            // then
            assertThat(submission).isEqualTo(answered)
            assertThat(clients.submitted).containsExactly("txn_001" to request)
        }

    @Test
    @Timeout(10)
    fun `a Postgres lookup that hangs leaves the answer to Restate once the lookup timeout passed`() =
        runBlocking<Unit> {
            // given: a database that never answers
            val hangingRecords = InMemoryFeeRecordRepository(hanging = setOf("findCharged"))
            val clients = FakeClients(attach = { FeeOutcome.Charged(response) })
            val chargedFees = ChargedFees(cache, hangingRecords, cacheProperties(lookupTimeout = Duration.ofMillis(50)))
            val service = FeeService(TransactionIdValidator(), chargedFees, clients, restateProperties())

            // when
            val submission = service.submit(request)

            // then
            assertThat(submission).isEqualTo(answered)
            assertThat(clients.submitted).containsExactly("txn_001" to request)
        }

    @Test
    fun `attaches to the run Restate returns, so a transaction id sent again gets the first run's result`() =
        runBlocking<Unit> {
            // given: Restate hands back the first run for a known key
            val clients = FakeClients(attach = { FeeOutcome.Charged(response) })

            // when
            val submission = service(clients).submit(request.copy(amount = BigDecimal("2000")))

            // then
            assertThat(submission).isEqualTo(answered)
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
    fun `a run Restate reports as not ready after the attach timeout is a 504 naming the transaction id`() {
        // given: the workflow was accepted but never finishes, and Restate answers its output with 470
        val clients = FakeClients(attach = { awaitCancellation() }, output = { null })

        // when / then
        assertThatThrownBy(submitting(service(clients, attachTimeout = Duration.ofMillis(50)), request))
            .isInstanceOfSatisfying(WorkflowTimeoutException::class.java) {
                assertThat(it.transactionId).isEqualTo("txn_001")
                assertThat(it.status).isEqualTo(HttpStatus.GATEWAY_TIMEOUT.value())
            }
        assertThat(clients.outputs).isEqualTo(1)
    }

    @Test
    fun `a run that finished just after the attach timeout answers its outcome, not 504`() =
        runBlocking<Unit> {
            // given: the attach outlasts the timeout, but Restate already has the output when asked
            val clients = FakeClients(attach = { awaitCancellation() }, output = { FeeOutcome.Charged(response) })

            // when
            val submission = service(clients, attachTimeout = Duration.ofMillis(50)).submit(request)

            // then
            assertThat(submission).isEqualTo(answered)
        }

    @Test
    fun `an output request that fails after the attach timeout is a 503, not 504`() {
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

        override suspend fun submit(transactionId: String, request: FeeRequest): FeeWorkflowRun {
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
