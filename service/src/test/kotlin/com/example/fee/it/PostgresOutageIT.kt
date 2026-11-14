package com.example.fee.it

import com.example.fee.contract.FeeRequest
import com.example.fee.contract.FeeResponse
import com.example.fee.record.FeeRecordStatus
import com.example.fee.workflow.WorkflowStep
import com.github.benmanes.caffeine.cache.Cache
import io.r2dbc.spi.Connection
import io.r2dbc.spi.ConnectionFactory
import io.r2dbc.spi.ValidationDepth
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import reactor.core.publisher.Mono
import java.math.BigDecimal
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/**
 * Postgres goes away while the app runs, and comes back ([PostgresTestEnvironment.goDown]: open connections are cut, new
 * ones refused). `FeeService` needs Postgres to give a new transaction its `workflow_id`, so without it a request is
 * answered 503 before anything reaches Restate, and sending it again once Postgres is back is safe. A run already in
 * Restate waits in its retries at CHARGE, so a fee is charged exactly once, and only after its record is written. What
 * needs no database (a charge still cached, a rejection, which the gateway answers before any call to Postgres or
 * Restate) is answered during the outage.
 *
 * Postgres also hangs ([PostgresTestEnvironment.freeze]: connections open, nothing answers, as in a network partition).
 * Each of `FeeService`'s calls then gives up after `fee-cache.lookup-timeout`, so a request doesn't wait for the database.
 */
class PostgresOutageIT
    @Autowired
    constructor(
        private val calculator: CountingFeeCalculator,
        private val records: FaultInjectingFeeRecordRepository,
        private val chargedFeesCache: Cache<String, FeeResponse>,
        private val connectionFactory: ConnectionFactory,
        @LocalServerPort apiPort: Int,
    ) : RestateIntegrationTest() {
        private val api = FeeApi(apiPort)
        private val blockedIds = mutableListOf<String>()

        @AfterEach
        fun restorePostgres() {
            // a failed test must not leave the database down or frozen, a thread waiting, or the app's pool holding cut
            // connections, for the other ITs: each step runs even if one before it failed
            try {
                PostgresTestEnvironment.thaw()
            } finally {
                try {
                    PostgresTestEnvironment.goUp()
                } finally {
                    try {
                        blockedIds.forEach(calculator::release)
                    } finally {
                        awaitLiveAppPool()
                    }
                }
            }
        }

        // The app's pool keeps the connections an outage cut until a request meets them (a 503 each). Holding every
        // connection of the pool at once, each checked with a query, replaces all of them before the next test runs.
        private fun awaitLiveAppPool() {
            awaitUntil("every connection of the app's pool reaches Postgres", timeout = Duration.ofSeconds(30)) {
                runBlocking {
                    val held = mutableListOf<Connection>()
                    try {
                        // bounded: a connection another test left busy must not hold the wait up for good
                        withTimeout(5.seconds) { repeat(APP_POOL_SIZE) { held += Mono.from(connectionFactory.create()).awaitSingle() } }
                        held.all { Mono.from(it.validate(ValidationDepth.REMOTE)).awaitSingle() }
                    } catch (_: Exception) {
                        false
                    } finally {
                        held.forEach { Mono.from(it.close()).awaitFirstOrNull() }
                    }
                }
            }
        }

        @Test
        fun `a transaction sent while Postgres is down is a 503 that reaches no workflow, and is charged once when sent again`() {
            // given
            val request = validRequest(newTransactionId())
            PostgresTestEnvironment.goDown()

            // when
            val response = api.submit(bodyOf(request))

            // then: the record can't be started, so nothing went to Restate
            assertThat(response.status).describedAs("POST status, body %s", response.body).isEqualTo(503)
            assertThat(response.body["code"].asText()).isEqualTo("WORKFLOW_UNAVAILABLE")
            assertThat(calculator.count(transactionIdOf(request))).isZero()

            // when: Postgres is back, and the same request is sent again until the app's pool has replaced the cut
            // connections (each request meeting one of them is a 503 too)
            PostgresTestEnvironment.goUp()
            val again = submitOnceAvailable(request)

            // then: charged once
            assertThat(again.status).describedAs("second POST status, body %s", again.body).isEqualTo(200)
            assertThat(again.body["fee"].decimalValue()).isEqualByComparingTo(FEE)
            assertChargedOnce(request)
        }

        @Test
        fun `Postgres going down during CALCULATE charges nothing until the record is written, then charges once`() {
            // given: the record is started, and the run is held in CALCULATE
            val request = validRequest(newTransactionId())
            val transactionId = transactionIdOf(request)
            calculator.block(transactionId)
            blockedIds += transactionId
            val post = CompletableFuture.supplyAsync { api.submit(bodyOf(request)) }
            awaitUntil("CALCULATE has started") { calculator.count(transactionId) == 1 }
            assertThat(StoredFeeRecords.of(transactionId)?.status).isEqualTo(FeeRecordStatus.PENDING)
            val workflowId = checkNotNull(StoredWorkflows.keyOf(transactionId))

            // when: Postgres goes down, and CALCULATE finishes during the outage
            PostgresTestEnvironment.goDown()
            calculator.release(transactionId)
            awaitUntil("Restate retries the run") { retries(workflowId) >= 1 }

            // then: the run waits at CHARGE with no response: nothing is reported charged before the record is written
            assertThat(StoredWorkflows.stepOf(workflowId)).isEqualTo(WorkflowStep.CHARGE)
            assertThat(StoredWorkflows.responseOf(workflowId)).isNull()
            assertThat(post).isNotDone

            // when
            PostgresTestEnvironment.goUp()

            // then: charged once, with the fee CALCULATE journaled before the outage
            val response = post.get(2, TimeUnit.MINUTES)
            assertThat(response.status).describedAs("POST status, body %s", response.body).isEqualTo(200)
            assertThat(response.body["fee"].decimalValue()).isEqualByComparingTo(FEE)
            assertThat(StoredWorkflows.responseOf(workflowId)?.fee).isEqualByComparingTo(FEE)
            assertChargedOnce(request)
        }

        @Test
        fun `a charged transaction sent again while Postgres is down is answered from the cache, and a 503 once uncached`() {
            // given: charged, then Postgres goes down
            val request = validRequest(newTransactionId())
            val first = api.submit(bodyOf(request))
            assertThat(first.status).describedAs("first POST status, body %s", first.body).isEqualTo(200)
            PostgresTestEnvironment.goDown()

            // when: sent again
            val fromCache = api.submit(bodyOf(request))

            // then
            assertThat(fromCache.status).describedAs("cached POST status, body %s", fromCache.body).isEqualTo(200)
            assertThat(fromCache.body).isEqualTo(first.body)

            // when: sent again once the cache forgot it (expired, evicted, another instance)
            chargedFeesCache.invalidate(transactionIdOf(request))
            val uncached = api.submit(bodyOf(request))

            // then: without Postgres the app can't tell its workflow, so it asks to send it again later
            assertThat(uncached.status).describedAs("uncached POST status, body %s", uncached.body).isEqualTo(503)

            // when: Postgres is back, and the request is sent again until the app's pool has replaced the cut connections
            PostgresTestEnvironment.goUp()
            val fromPostgres = submitOnceAvailable(request)

            // then: the stored charge, calculated once
            assertThat(fromPostgres.status).describedAs("POST status, body %s", fromPostgres.body).isEqualTo(200)
            assertThat(fromPostgres.body).isEqualTo(first.body)
            assertThat(calculator.count(transactionIdOf(request))).describedAs("calculations").isEqualTo(1)
        }

        @Test
        fun `a rejected transaction is answered while Postgres is down, and never stored`() {
            // given
            val request = validRequest(newTransactionId()).copy(state = "PENDING")
            PostgresTestEnvironment.goDown()

            // when
            val response = api.submit(bodyOf(request))

            // then: the gateway validates before any call to Postgres or Restate, so the rejection needs neither
            assertThat(response.status).describedAs("POST status, body %s", response.body).isEqualTo(422)
            assertThat(records.callCount(transactionIdOf(request))).describedAs("Postgres calls").isZero()
            assertThat(calculator.count(transactionIdOf(request))).isZero()
            PostgresTestEnvironment.goUp()
            assertThat(StoredFeeRecords.count(transactionIdOf(request))).isZero()
            assertThat(StoredWorkflows.step(request)).describedAs("workflow step").isNull()
        }

        @Test
        fun `while Postgres hangs, a transaction no longer cached is a 503 once the database timeout passed`() {
            // given: charged, no longer cached, and Postgres frozen: connections open, nothing answers
            val request = validRequest(newTransactionId())
            val first = api.submit(bodyOf(request))
            assertThat(first.status).describedAs("first POST status, body %s", first.body).isEqualTo(200)
            chargedFeesCache.invalidate(transactionIdOf(request))
            PostgresTestEnvironment.freeze()
            assertThat(PostgresTestEnvironment.answersWithin(Duration.ofSeconds(2))).describedAs("Postgres answers while frozen").isFalse()
            assertThat(chargedFeesCache.getIfPresent(transactionIdOf(request))).describedAs("cached charge").isNull()

            // when
            val started = System.nanoTime()
            val again = api.submit(bodyOf(request))
            val took = Duration.ofNanos(System.nanoTime() - started)

            // then: the lookup and the start each gave up after fee-cache.lookup-timeout (1 s)
            assertThat(again.status).describedAs("POST status, body %s", again.body).isEqualTo(503)
            assertThat(took).describedAs("answered in").isLessThan(HANG_BOUND)
            assertThat(calculator.count(transactionIdOf(request))).describedAs("calculations").isEqualTo(1)
        }

        @Test
        fun `while Postgres hangs, a rejected transaction is answered without a lookup`() {
            // given
            val request = validRequest(newTransactionId()).copy(state = "PENDING")
            PostgresTestEnvironment.freeze()
            assertThat(PostgresTestEnvironment.answersWithin(Duration.ofSeconds(2))).describedAs("Postgres answers while frozen").isFalse()

            // when
            val started = System.nanoTime()
            val response = api.submit(bodyOf(request))
            val took = Duration.ofNanos(System.nanoTime() - started)

            // then: the gateway rejected it before any lookup, so the hanging database never held it up
            assertThat(response.status).describedAs("POST status, body %s", response.body).isEqualTo(422)
            assertThat(took).describedAs("answered in").isLessThan(HANG_BOUND)
            assertThat(records.callCount(transactionIdOf(request))).describedAs("Postgres calls").isZero()
            assertThat(calculator.count(transactionIdOf(request))).isZero()
        }

        // After goUp the app's pool still holds the connections goDown cut: a request that meets one is a 503, which the
        // client answers by sending it again. Bounded: once the pool has replaced them, the answer is the real one.
        private fun submitOnceAvailable(request: FeeRequest): FeeApi.Response {
            var response = api.submit(bodyOf(request))
            awaitUntil("the app reaches Postgres again", timeout = Duration.ofSeconds(30)) {
                (response.status != 503).also { if (!it) response = api.submit(bodyOf(request)) }
            }
            return response
        }

        // Restate's own count of failed attempts of the run
        private fun retries(workflowId: String): Long =
            RestateTestEnvironment.admin
                .query("$RETRY_COUNT 'FeeWorkflow/$workflowId/run'")
                .singleOrNull()
                ?.get("retry_count")
                ?.asLong() ?: 0

        private fun assertChargedOnce(request: FeeRequest) {
            val transactionId = transactionIdOf(request)
            val record = StoredFeeRecords.of(transactionId)
            assertThat(record?.status).isEqualTo(FeeRecordStatus.CHARGED)
            assertThat(record?.response?.fee).isEqualByComparingTo(FEE)
            assertThat(StoredFeeRecords.count(transactionId)).isEqualTo(1)
            assertThat(calculator.count(transactionId)).describedAs("calculations").isEqualTo(1)
        }

        private fun newTransactionId() = "it-postgres-outage-${UUID.randomUUID().toString().take(13)}"

        private fun transactionIdOf(request: FeeRequest) = checkNotNull(request.transactionId)

        private fun bodyOf(request: FeeRequest): Map<String, Any?> =
            mapOf(
                "transaction_id" to request.transactionId,
                "amount" to request.amount,
                "asset" to request.asset,
                "asset_type" to request.assetType,
                "type" to request.type,
                "state" to request.state,
                "created_at" to request.createdAt,
            )

        private fun validRequest(transactionId: String) =
            FeeRequest(
                transactionId = transactionId,
                amount = BigDecimal("1000"),
                asset = "USD",
                assetType = "FIAT",
                type = "Mobile Top Up",
                state = "SETTLED - PENDING FEE",
                createdAt = "2023-08-30 15:42:17.610059",
            )

        private companion object {
            val FEE = BigDecimal("1.50")

            // well above the two 1 s database timeouts, far below FeeApi's own 60 s: a call that waited for the database fails it
            val HANG_BOUND: Duration = Duration.ofSeconds(10)

            // Restate's query API takes no parameters: the run's target is appended, quoted
            const val RETRY_COUNT = "SELECT retry_count FROM sys_invocation WHERE target ="

            // Boot's default spring.r2dbc.pool.max-size, which the app keeps
            const val APP_POOL_SIZE = 10
        }
    }
