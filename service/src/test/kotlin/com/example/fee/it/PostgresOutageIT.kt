package com.example.fee.it

import com.example.fee.contract.FeeRequest
import com.example.fee.contract.FeeResponse
import com.example.fee.record.FeeRecordStatus
import com.example.fee.workflow.FeeOutcome
import com.example.fee.workflow.WorkflowStep
import com.github.benmanes.caffeine.cache.Cache
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import java.math.BigDecimal
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Postgres goes away while the app runs, and comes back ([PostgresTestEnvironment.goDown]: open connections are cut, new
 * ones refused). Restate still accepts work. A run that needs the database waits in Restate's retries, so a fee is charged
 * exactly once, and only after its record is written; what needs no database (a charge Restate or the cache still knows,
 * a rejection, which the gateway answers before any call to Postgres or Restate) is answered during the outage.
 *
 * Postgres also hangs ([PostgresTestEnvironment.freeze]: connections open, nothing answers, as in a network partition).
 * `FeeService`'s lookup then gives up after `fee-cache.lookup-timeout` and leaves the answer to Restate, so a request
 * doesn't wait for the database.
 */
class PostgresOutageIT
    @Autowired
    constructor(
        private val calculator: CountingFeeCalculator,
        private val records: FaultInjectingFeeRecordRepository,
        private val chargedFeesCache: Cache<String, FeeResponse>,
        @LocalServerPort apiPort: Int,
    ) : RestateIntegrationTest() {
        private val api = FeeApi(apiPort)
        private val blockedIds = mutableListOf<String>()

        @AfterEach
        fun restorePostgres() {
            // a failed test must not leave the database down or frozen, or a thread waiting, for the other ITs: each step
            // runs even if one before it failed
            try {
                PostgresTestEnvironment.thaw()
            } finally {
                try {
                    PostgresTestEnvironment.goUp()
                } finally {
                    blockedIds.forEach(calculator::release)
                }
            }
        }

        @Test
        fun `a transaction sent while Postgres is down is accepted by Restate, waits at CLAIM, and is charged once Postgres is back`() {
            // given
            val request = validRequest(newTransactionId())
            PostgresTestEnvironment.goDown()

            // when: the gateway can't look the id up, so it submits; Restate accepts the run, and its CLAIM fails
            val post = CompletableFuture.supplyAsync { api.submit(bodyOf(request)) }
            awaitUntil("Restate retries the run") { retries(request) >= 1 }

            // then: accepted, waiting at CLAIM, nothing calculated and nothing answered
            assertThat(StoredWorkflows.step(request)).isEqualTo(WorkflowStep.CLAIM)
            assertThat(calculator.count(transactionIdOf(request))).isZero()
            assertThat(post).isNotDone

            // when
            PostgresTestEnvironment.goUp()

            // then: the caller gets the charge, which is recorded once
            val response = post.get(2, TimeUnit.MINUTES)
            assertThat(response.status).describedAs("POST status, body %s", response.body).isEqualTo(200)
            assertThat(response.body["fee"].decimalValue()).isEqualByComparingTo(FEE)
            assertCompletedOnce(request)

            // then: sent again, the same fee
            val again = api.submit(bodyOf(request))
            assertThat(again.status).describedAs("second POST status, body %s", again.body).isEqualTo(200)
            assertThat(again.body).isEqualTo(response.body)
            assertCompletedOnce(request)
        }

        @Test
        fun `Postgres going down between CLAIM and RECORD charges nothing until the record is written, then charges once`() {
            // given: the run claimed the id and is held in CALCULATE
            val request = validRequest(newTransactionId())
            val transactionId = transactionIdOf(request)
            calculator.block(transactionId)
            blockedIds += transactionId
            val post = CompletableFuture.supplyAsync { api.submit(bodyOf(request)) }
            awaitUntil("CALCULATE has started") { calculator.count(transactionId) == 1 }
            assertThat(StoredFeeRecords.of(transactionId)?.status).isEqualTo(FeeRecordStatus.IN_PROGRESS)

            // when: Postgres goes down, and CALCULATE finishes during the outage
            PostgresTestEnvironment.goDown()
            calculator.release(transactionId)
            awaitUntil("Restate retries the run") { retries(request) >= 1 }

            // then: the run waits at RECORD with no outcome: nothing is reported charged before the record is written
            assertThat(StoredWorkflows.step(request)).isEqualTo(WorkflowStep.RECORD)
            assertThat(StoredWorkflows.outcome(request)).isNull()
            assertThat(post).isNotDone

            // when
            PostgresTestEnvironment.goUp()

            // then: charged once, with the fee CALCULATE journaled before the outage
            val response = post.get(2, TimeUnit.MINUTES)
            assertThat(response.status).describedAs("POST status, body %s", response.body).isEqualTo(200)
            assertThat(response.body["fee"].decimalValue()).isEqualByComparingTo(FEE)
            assertThat(StoredWorkflows.outcome(request)).isInstanceOf(FeeOutcome.Charged::class.java)
            assertCompletedOnce(request)
        }

        @Test
        fun `a charged transaction sent again while Postgres is down is answered from the cache, then from Restate`() {
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
            val fromRestate = api.submit(bodyOf(request))

            // then: the lookup fails, and Restate answers with the finished run's charge
            assertThat(fromRestate.status).describedAs("uncached POST status, body %s", fromRestate.body).isEqualTo(200)
            assertThat(fromRestate.body).isEqualTo(first.body)
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
            assertThat(StoredWorkflows.step(request)).describedAs("workflow step").isNull()
            assertThat(calculator.count(transactionIdOf(request))).isZero()
            PostgresTestEnvironment.goUp()
            assertThat(StoredFeeRecords.count(transactionIdOf(request))).isZero()
        }

        @Test
        fun `while Postgres hangs, a charged transaction no longer cached is answered by Restate once the lookup gives up`() {
            // given: charged, no longer cached, and Postgres frozen: connections open, nothing answers
            val request = validRequest(newTransactionId())
            val first = api.submit(bodyOf(request))
            assertThat(first.status).describedAs("first POST status, body %s", first.body).isEqualTo(200)
            chargedFeesCache.invalidate(transactionIdOf(request))
            PostgresTestEnvironment.freeze()
            assertThat(PostgresTestEnvironment.answersWithin(Duration.ofSeconds(2))).describedAs("Postgres answers while frozen").isFalse()

            // when
            val started = System.nanoTime()
            val again = api.submit(bodyOf(request))
            val took = Duration.ofNanos(System.nanoTime() - started)

            // then: the lookup gave up after fee-cache.lookup-timeout (1 s), and Restate answered from the finished run
            assertThat(again.status).describedAs("POST status, body %s", again.body).isEqualTo(200)
            assertThat(again.body).isEqualTo(first.body)
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
            assertThat(StoredWorkflows.step(request)).describedAs("workflow step").isNull()
            assertThat(calculator.count(transactionIdOf(request))).isZero()
        }

        // Restate's own count of failed attempts of the run
        private fun retries(request: FeeRequest): Long =
            RestateTestEnvironment.admin
                .query("$RETRY_COUNT 'FeeWorkflow/${transactionIdOf(request)}/run'")
                .singleOrNull()
                ?.get("retry_count")
                ?.asLong() ?: 0

        private fun assertCompletedOnce(request: FeeRequest) {
            val transactionId = transactionIdOf(request)
            val record = StoredFeeRecords.of(transactionId)
            assertThat(record?.status).isEqualTo(FeeRecordStatus.COMPLETED)
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

            // well above the 1 s lookup timeout, far below FeeApi's own 60 s: a lookup that waited for the database fails it
            val HANG_BOUND: Duration = Duration.ofSeconds(10)

            // Restate's query API takes no parameters: the run's target is appended, quoted
            const val RETRY_COUNT = "SELECT retry_count FROM sys_invocation WHERE target ="
        }
    }
