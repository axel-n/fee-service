package com.example.fee.application

import com.example.fee.config.FeeCacheProperties
import com.example.fee.config.RestateProperties
import com.example.fee.contract.FeeRequest
import com.example.fee.contract.FeeResponse
import com.example.fee.contract.error.ValidationException
import com.example.fee.contract.error.WorkflowTimeoutException
import com.example.fee.contract.error.WorkflowUnavailableException
import com.example.fee.record.InMemoryFeeRecordRepository
import com.example.fee.restate.FeeWorkflowClients
import com.example.fee.restate.FeeWorkflowRun
import com.example.fee.validation.allChecksRequestValidator
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import dev.restate.client.IngressException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
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
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
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

    // Every production check, against a clock after the request's created_at
    private val requestValidator = allChecksRequestValidator(Clock.fixed(Instant.parse("2023-08-30T16:00:00Z"), ZoneOffset.UTC))

    private fun service(
        clients: FeeWorkflowClients,
        attachTimeout: Duration = Duration.ofSeconds(5),
        feeRecords: InMemoryFeeRecordRepository = records,
        lookupTimeout: Duration = Duration.ofSeconds(1),
    ): FeeService = FeeService(
        requestValidator,
        ChargedFees(cache, feeRecords, cacheProperties(lookupTimeout)),
        clients,
        restateProperties(attachTimeout),
    )

    private fun restateProperties(attachTimeout: Duration) = RestateProperties(
        ingressUrl = "http://localhost:8080",
        adminUrl = "http://localhost:9070",
        endpointPort = 9_080,
        advertisedHost = "localhost",
        attachTimeout = attachTimeout,
    )

    private fun cacheProperties(lookupTimeout: Duration) =
        FeeCacheProperties(maximumSize = 1_000, expireAfterWrite = Duration.ofDays(1), lookupTimeout = lookupTimeout)

    private fun ingressDown(): IngressException =
        IngressException("Error when executing the request", "POST", "http://localhost:8080", -1, null, ConnectException())

    // Runs submit in a coroutine; a failure comes back as the exception, so assertThatThrownBy can inspect it
    private fun submitting(service: FeeService, request: FeeRequest) = ThrowingCallable { runBlocking { service.submit(request) } }

    @Test
    fun `a charged response answers the workflow's response with the fee as in the requirements' sample`() = runBlocking<Unit> {
        // given
        val clients = FakeClients(attach = { response })

        // when
        val submission = service(clients).submit(request)

        // then
        assertThat(submission).isEqualTo(answered)
    }

    @ParameterizedTest(name = "stored {0} → answered {1}")
    @CsvSource("1.50, 1.5", "10.30, 10.3", "2.00, 2", "50.00, 50", "1.01, 1.01", "0.38, 0.38", "0.00, 0")
    fun `answers the charged fee without trailing zeros, at the same value`(stored: BigDecimal, expected: String) = runBlocking<Unit> {
        // given
        val clients = FakeClients(attach = { response.copy(fee = stored) })

        // when
        val fee = service(clients).submit(request).fee

        // then
        assertThat(fee.toPlainString()).isEqualTo(expected)
        assertThat(fee).isEqualByComparingTo(stored)
    }

    @Test
    fun `a new transaction id goes to its workflow under the transaction id, and the gateway writes nothing to Postgres`() =
        runBlocking<Unit> {
            // given
            val clients = FakeClients(attach = { response })

            // when
            service(clients).submit(request)

            // then: one lookup, then the workflow, which charges the record itself
            assertThat(records.calls.map { it.name }).containsExactly("findCharged")
            assertThat(clients.submitted).containsExactly("txn_001" to request)
        }

    @Test
    fun `a transaction id sent again with another payload after a 504 goes to the same workflow`() = runBlocking<Unit> {
        // given: the id's run had not finished when the first request gave up
        var finished = false
        val clients = FakeClients(attach = { if (finished) response else awaitCancellation() }, output = { null })
        val service = service(clients, attachTimeout = Duration.ofMillis(50))
        val timedOut = runCatching { service.submit(request) }.exceptionOrNull()
        assertThat(timedOut).isInstanceOf(WorkflowTimeoutException::class.java)
        finished = true
        val changed = request.copy(amount = BigDecimal("2000"))

        // when
        val submission = service.submit(changed)

        // then: both under the id's key, so Restate joins them to its one run and its response
        assertThat(submission).isEqualTo(answered)
        assertThat(clients.submitted).containsExactly("txn_001" to request, "txn_001" to changed)
    }

    @Test
    fun `a malformed request is a 400 naming the field before Postgres or Restate is asked`() {
        // given
        val clients = FakeClients(attach = { error("Restate must not be called") })

        // when / then
        assertThatThrownBy(submitting(service(clients), request.copy(amount = BigDecimal("-5"))))
            .isInstanceOfSatisfying(ValidationException::class.java) {
                assertThat(listOf(it.field, it.value)).containsExactly("amount", "-5")
                assertThat(it.message).isEqualTo("Invalid amount '-5'. Allowed: a number greater than 0")
                assertThat(it.kind).isEqualTo(ValidationException.Kind.INVALID)
                assertThat(it.status).isEqualTo(HttpStatus.BAD_REQUEST.value())
            }
        assertThat(records.calls).isEmpty()
        assertThat(clients.submitted).isEmpty()
    }

    @ParameterizedTest(name = "{0} = {1}")
    @CsvSource("asset, BTC", "asset_type, CRYPTO", "type, Refund", "state, PENDING")
    fun `an unsupported request is a 422 naming the field before Postgres or Restate is asked`(field: String, value: String) {
        // given
        val clients = FakeClients(attach = { error("Restate must not be called") })
        val unsupported =
            when (field) {
                "asset" -> request.copy(asset = value)
                "asset_type" -> request.copy(assetType = value)
                "type" -> request.copy(type = value)
                else -> request.copy(state = value)
            }

        // when / then
        assertThatThrownBy(submitting(service(clients), unsupported))
            .isInstanceOfSatisfying(ValidationException::class.java) {
                assertThat(listOf(it.field, it.value)).containsExactly(field, value)
                assertThat(it.message).startsWith("Unsupported $field '$value'. Supported: ")
                assertThat(it.kind).isEqualTo(ValidationException.Kind.UNSUPPORTED)
                assertThat(it.status).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT.value())
            }
        assertThat(records.calls).isEmpty()
        assertThat(clients.submitted).isEmpty()
    }

    @Test
    fun `a charged transaction id sent with an invalid payload is rejected, not answered with its charge`() {
        // given
        cache.put("txn_001", response)
        val clients = FakeClients(attach = { error("Restate must not be called") })

        // when / then
        assertThatThrownBy(submitting(service(clients), request.copy(state = "PENDING")))
            .isInstanceOfSatisfying(ValidationException::class.java) {
                assertThat(it.field).isEqualTo("state")
                assertThat(it.status).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT.value())
            }
        assertThat(records.calls).isEmpty()
        assertThat(clients.submitted).isEmpty()
        assertThat(cache.getIfPresent("txn_001")).isEqualTo(response)
    }

    @Test
    fun `a charged transaction id is answered from the cache without asking Postgres or Restate, whatever its valid payload`() =
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
    fun `a charged transaction id missing from the cache is answered from Postgres without Restate, and cached`() = runBlocking<Unit> {
        // given: charged earlier, by this instance before a restart or by another one
        records.charges["txn_001"] = response
        val clients = FakeClients(attach = { error("Restate must not be called") })

        // when
        val submission = service(clients).submit(request)

        // then
        assertThat(submission).isEqualTo(answered)
        assertThat(records.calls.map { it.name }).containsExactly("findCharged")
        assertThat(clients.submitted).isEmpty()
        assertThat(cache.getIfPresent("txn_001")).isEqualTo(response)
    }

    @Test
    fun `a charge from Restate is remembered, so the same transaction id sent again doesn't reach Restate`() = runBlocking<Unit> {
        // given
        val clients = FakeClients(attach = { response })
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
    fun `a run not finished in time is not remembered`() {
        // given
        val clients = FakeClients(attach = { awaitCancellation() }, output = { null })

        // when / then: a 504, and nothing cached
        assertThatThrownBy(submitting(service(clients, attachTimeout = Duration.ofMillis(50)), request))
            .isInstanceOf(WorkflowTimeoutException::class.java)
        assertThat(cache.asMap()).isEmpty()
    }

    @Test
    fun `a failed Postgres lookup is a 503, and Restate is not asked`() {
        // given
        val outage = IllegalStateException("database unreachable")
        val failing = InMemoryFeeRecordRepository(failing = mapOf("findCharged" to outage))
        val clients = FakeClients(attach = { error("Restate must not be called") })

        // when / then: without the lookup the gateway can't tell whether the id is charged already
        assertThatThrownBy(submitting(service(clients, feeRecords = failing), request))
            .isInstanceOfSatisfying(WorkflowUnavailableException::class.java) {
                assertThat(it.status).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value())
                // the same failure; coroutines may hand over a copy with the stack trace across the timeout
                assertThat(it.cause).isInstanceOf(IllegalStateException::class.java).hasMessage(outage.message)
            }
        assertThat(clients.submitted).isEmpty()
    }

    @Test
    @Timeout(10)
    fun `a Postgres that hangs is a 503 once the lookup timeout passed, and Restate is not asked`() {
        // given: a database that never answers
        val hanging = InMemoryFeeRecordRepository(hanging = setOf("findCharged"))
        val clients = FakeClients(attach = { error("Restate must not be called") })

        // when / then
        assertThatThrownBy(submitting(service(clients, feeRecords = hanging, lookupTimeout = Duration.ofMillis(50)), request))
            .isInstanceOf(WorkflowUnavailableException::class.java)
        assertThat(hanging.calls.map { it.name }).containsExactly("findCharged")
        assertThat(clients.submitted).isEmpty()
    }

    @Test
    fun `a missing transaction id is a 400 before Postgres or Restate is asked`() {
        // given
        val clients = FakeClients(attach = { response })

        // when / then
        assertThatThrownBy(submitting(service(clients), request.copy(transactionId = null)))
            .isInstanceOfSatisfying(ValidationException::class.java) {
                assertThat(it.field).isEqualTo("transaction_id")
                assertThat(it.status).isEqualTo(HttpStatus.BAD_REQUEST.value())
            }
        assertThat(records.calls).isEmpty()
        assertThat(clients.submitted).isEmpty()
    }

    @Test
    fun `a transaction id that is not a plain identifier is a 400 naming the value before Postgres or Restate is asked`() {
        // given
        val clients = FakeClients(attach = { response })

        // when / then
        assertThatThrownBy(submitting(service(clients), request.copy(transactionId = "txn:001")))
            .isInstanceOfSatisfying(ValidationException::class.java) {
                assertThat(it.field).isEqualTo("transaction_id")
                assertThat(it.value).isEqualTo("txn:001")
                assertThat(it.status).isEqualTo(HttpStatus.BAD_REQUEST.value())
            }
        assertThat(records.calls).isEmpty()
        assertThat(clients.submitted).isEmpty()
    }

    @Test
    fun `an unreachable ingress on submit is a 503`() {
        // given
        val down = ingressDown()
        val clients = FakeClients(submit = { throw down }, attach = { response })

        // when / then
        assertThatThrownBy(submitting(service(clients), request))
            .isInstanceOfSatisfying(WorkflowUnavailableException::class.java) {
                assertThat(it.status).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value())
                assertThat(it.cause).isSameAs(down)
            }
        assertThat(clients.attaches).isZero()
        assertThat(cache.asMap()).isEmpty()
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
    fun `a run that finished just after the attach timeout answers its response, not 504`() = runBlocking<Unit> {
        // given: the attach outlasts the timeout, but Restate already has the output when asked
        val clients = FakeClients(attach = { awaitCancellation() }, output = { response })

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
    fun `a run that finishes within the attach timeout never asks Restate for its output`() = runBlocking<Unit> {
        // given
        val clients = FakeClients(attach = { response })

        // when
        service(clients).submit(request)

        // then
        assertThat(clients.outputs).isZero()
    }

    @Test
    @Timeout(10)
    fun `a request cancelled during its Postgres lookup stays a cancellation and is not reported as a 503`() = runBlocking<Unit> {
        // given: a database that never answers, a lookup timeout far away, and the caller waiting for the lookup
        val hanging = InMemoryFeeRecordRepository(hanging = setOf("findCharged"))
        val clients = FakeClients(attach = { error("Restate must not be called") })
        val service = service(clients, feeRecords = hanging, lookupTimeout = Duration.ofMinutes(1))
        val submission = async { service.submit(request) }
        while (hanging.calls.isEmpty()) yield()

        // when
        submission.cancel()
        val failure = runCatching { submission.await() }.exceptionOrNull()

        // then
        assertThat(failure).isInstanceOf(CancellationException::class.java)
        assertThat(clients.submitted).isEmpty()
    }

    @Test
    fun `a request cancelled while it waits stays a cancellation and is not reported as a 503`() = runBlocking<Unit> {
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
     * The ingress in memory: [submit] runs on every submission, which the fake records with its workflow key, and
     * [attach] answers the run's charged response, or suspends until cancelled, as a run that never finishes does.
     * [output] answers Restate's one-off output request: `null` is Restate's 470 (not ready).
     */
    private class FakeClients(
        private val submit: suspend () -> Unit = {},
        private val attach: suspend () -> FeeResponse,
        private val output: suspend () -> FeeResponse? = { null },
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
                override suspend fun attach(): FeeResponse {
                    attaches++
                    attaching.complete(Unit)
                    return this@FakeClients.attach()
                }

                override suspend fun output(): FeeResponse? {
                    outputs++
                    return this@FakeClients.output()
                }
            }
        }
    }
}
