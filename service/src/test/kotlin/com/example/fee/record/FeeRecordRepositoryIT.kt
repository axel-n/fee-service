package com.example.fee.record

import com.example.fee.contract.FeeResponse
import com.example.fee.domain.Asset
import com.example.fee.domain.AssetType
import com.example.fee.domain.Transaction
import com.example.fee.domain.TransactionState
import com.example.fee.domain.TransactionType
import com.example.fee.it.PostgresTestEnvironment
import com.example.fee.it.RestateIntegrationTest
import com.example.fee.it.StoredFeeRecords
import com.example.fee.it.StoredFeeRecords.StoredTimes
import com.example.fee.it.TestClock
import com.example.fee.it.awaitUntil
import com.example.fee.record.jooq.tables.records.FeeRecordRecord
import com.example.fee.record.jooq.tables.references.FEE_RECORD
import io.r2dbc.pool.ConnectionPool
import io.r2dbc.pool.ConnectionPoolConfiguration
import io.r2dbc.spi.ConnectionFactories
import io.r2dbc.spi.ConnectionFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jooq.InsertSetMoreStep
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime
import java.util.TimeZone
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * [PostgresFeeRecordRepository] against the shared Postgres of the ITs, with the table the app created at startup.
 * Each test uses its own `transaction_id`. Rows are read back through [StoredFeeRecords], past the repository.
 */
class FeeRecordRepositoryIT
    @Autowired
    constructor(
        private val connectionFactory: ConnectionFactory,
    ) : RestateIntegrationTest() {
        private val clock = TestClock().apply { pin(STARTED_AT) }
        private val repository = PostgresFeeRecordRepository(connectionFactory, clock)

        @Test
        fun `the app's connections to Postgres come from a pool`() {
            // given / when: the ConnectionFactory the app injects into the repository
            val injected = connectionFactory

            // then: Boot pools R2DBC connections only when r2dbc-pool is on the classpath
            assertThat(injected).isInstanceOf(ConnectionPool::class.java)
        }

        @Test
        fun `starting a new transaction id inserts a PENDING record with the workflow id`() =
            runBlocking<Unit> {
                // given
                val transactionId = newTransactionId()

                // when
                val record = repository.start(transactionFor(transactionId), "workflow-a")

                // then
                assertThat(record).isEqualTo(FeeRecord.pending("workflow-a"))
                assertThat(StoredFeeRecords.of(transactionId)).isEqualTo(record)
            }

        @Test
        fun `a start whose insert meets a row committed meanwhile returns that row, not a record of its own`() =
            runBlocking<Unit> {
                // given: another request's row, not committed yet, so the start's insert waits for it
                val transactionId = newTransactionId()
                StoredFeeRecords.startInOpenTransaction(transactionId).use { other ->
                    val start = async(Dispatchers.IO) { repository.start(transactionFor(transactionId), "workflow-a") }
                    awaitUntil("the start's insert waits for the other request's") { other.blockedStatements() == 1L }

                    // when
                    other.commit()
                    val record = start.await()

                    // then: the other request's row, which stays as it was
                    assertThat(record).isEqualTo(FeeRecord.pending(StoredFeeRecords.FOREIGN_WORKFLOW_ID))
                    assertThat(StoredFeeRecords.of(transactionId)).isEqualTo(record)
                }
            }

        @Test
        fun `starting stores every field of the request, created_at to the microsecond, and when it was started`() =
            runBlocking<Unit> {
                // given
                val transaction = transactionFor(newTransactionId())

                // when
                repository.start(transaction, "workflow-a")

                // then
                assertThat(StoredFeeRecords.transactionOf(transaction.transactionId)).isEqualTo(transaction)
                assertThat(StoredFeeRecords.timesOf(transaction.transactionId)).isEqualTo(StoredTimes(STARTED_AT, chargedAt = null))
            }

        @Test
        fun `created_at is stored and read exactly as sent whatever the JVM's time zone, even in a daylight saving gap`() =
            runBlocking<Unit> {
                // given: a created_at in the hour Berlin skipped on 2024-03-31 (02:00 to 03:00), with Berlin as the
                // JVM's default zone; created_at has no zone, so the zone must not matter
                val transaction =
                    transactionFor(newTransactionId()).copy(createdAt = LocalDateTime.of(2024, 3, 31, 2, 30, 0, 123_456_000))
                val defaultZone = TimeZone.getDefault()
                val readBack =
                    try {
                        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))

                        // when
                        repository.start(transaction, "workflow-a")
                        StoredFeeRecords.transactionOf(transaction.transactionId)
                    } finally {
                        TimeZone.setDefault(defaultZone)
                    }

                // then: as Postgres itself shows it, and as jOOQ reads it back
                assertThat(StoredFeeRecords.createdAtAsStored(transaction.transactionId)).isEqualTo("2024-03-31 02:30:00.123456")
                assertThat(readBack).isEqualTo(transaction)
            }

        @Test
        fun `a zone-free timestamp that is null binds and reads back as null`() =
            runBlocking<Unit> {
                // given: a null bind value of created_at's type, so of its ZoneFreeLocalDateTimeBinding
                val nothing = DSL.value(null as LocalDateTime?, FEE_RECORD.CREATED_AT)

                // when
                val read =
                    PostgresTestEnvironment.dsl
                        .select(nothing)
                        .awaitSingle()
                        .value1()

                // then
                assertThat(read).isNull()
            }

        @Test
        fun `a query that gets no connection fails with the operation, the id and the cause, not its statement and values`() {
            // given: a repository whose database refuses its password
            val refused =
                PostgresFeeRecordRepository(
                    ConnectionFactories.get(
                        PostgresTestEnvironment.url.replace(
                            "r2dbc:postgresql://",
                            "r2dbc:postgresql://${PostgresTestEnvironment.USERNAME}:not-the-password@",
                        ),
                    ),
                    clock,
                )
            val transactionId = newTransactionId()

            // when
            val starting = runCatching { runBlocking { refused.start(transactionFor(transactionId), "workflow-a") } }
            val charging =
                runCatching { runBlocking { refused.charge(transactionFor(transactionId), "workflow-a", responseFor(transactionId)) } }

            // then: one line each, without the statement, the workflow id or the request's values
            assertThat(starting.exceptionOrNull())
                .isInstanceOf(DataAccessException::class.java)
                .hasMessageStartingWith("Starting the fee record of '$transactionId' failed: ")
            assertThat(charging.exceptionOrNull())
                .isInstanceOf(DataAccessException::class.java)
                .hasMessageStartingWith("Charging the fee record of '$transactionId' failed: ")
            listOf(starting, charging).forEach { failed ->
                assertThat(failed.exceptionOrNull()).hasMessageContaining("password authentication failed")
                listOf("\n", "\"fee_record\"", "workflow-a", "Mobile Top Up").forEach { revealing ->
                    assertThat(failed.exceptionOrNull()).hasMessageNotContaining(revealing)
                }
            }
        }

        @Test
        fun `starting an id that has a record changes nothing, whatever the workflow id or payload`() =
            runBlocking<Unit> {
                // given
                val transactionId = newTransactionId()
                repository.start(transactionFor(transactionId), "workflow-a")

                // when
                val again = repository.start(transactionFor(transactionId), "workflow-a")
                val other = repository.start(transactionFor(transactionId).copy(amount = BigDecimal("2000")), "workflow-b")

                // then: the record of the first start, both times, with the first start's request
                assertThat(listOf(again, other)).containsOnly(FeeRecord.pending("workflow-a"))
                assertThat(StoredFeeRecords.of(transactionId)).isEqualTo(FeeRecord.pending("workflow-a"))
                assertThat(StoredFeeRecords.transactionOf(transactionId)).isEqualTo(transactionFor(transactionId))
            }

        @Test
        fun `charging under its workflow id stores the response and when it was charged, every scale kept`() =
            runBlocking<Unit> {
                // given
                val transactionId = newTransactionId()
                repository.start(transactionFor(transactionId), "workflow-a")
                val response = responseFor(transactionId)
                clock.pin(CHARGED_AT)

                // when
                repository.charge(transactionFor(transactionId), "workflow-a", response)

                // then: equal as data classes, so 1000 is still 1000 and 1.50 still 1.50
                assertThat(repository.findCharged(transactionId)).isEqualTo(response)
                assertThat(StoredFeeRecords.of(transactionId)).isEqualTo(FeeRecord.pending("workflow-a").charge(response))
                assertThat(StoredFeeRecords.timesOf(transactionId)).isEqualTo(StoredTimes(STARTED_AT, CHARGED_AT))
            }

        @Test
        fun `charging writes the request fields of the payload it charged, whichever payload started the record`() =
            runBlocking<Unit> {
                // given: another request of the id started the record with a different amount
                val transactionId = newTransactionId()
                repository.start(transactionFor(transactionId).copy(amount = BigDecimal("2000")), "workflow-a")
                val charged = transactionFor(transactionId)

                // when: the workflow charges the payload it got
                repository.charge(charged, "workflow-a", responseFor(transactionId))

                // then: the record holds what was charged
                assertThat(StoredFeeRecords.transactionOf(transactionId)).isEqualTo(charged)
                assertThat(repository.findCharged(transactionId)).isEqualTo(responseFor(transactionId))
            }

        @Test
        fun `charging again with the same response changes nothing, not even charged_at, so a step run again after a crash is safe`() =
            runBlocking<Unit> {
                // given
                val transactionId = newTransactionId()
                repository.start(transactionFor(transactionId), "workflow-a")
                val response = responseFor(transactionId)
                clock.pin(CHARGED_AT)
                repository.charge(transactionFor(transactionId), "workflow-a", response)
                clock.pin(CHARGED_AT.plusSeconds(30))

                // when
                repository.charge(transactionFor(transactionId), "workflow-a", response)

                // then
                assertThat(StoredFeeRecords.of(transactionId)).isEqualTo(FeeRecord.pending("workflow-a").charge(response))
                assertThat(StoredFeeRecords.timesOf(transactionId)).isEqualTo(StoredTimes(STARTED_AT, CHARGED_AT))
                assertThat(StoredFeeRecords.count(transactionId)).isEqualTo(1)
            }

        @Test
        fun `charging under another workflow id fails and leaves the record as it was`() {
            // given
            val transactionId = newTransactionId()
            runBlocking { repository.start(transactionFor(transactionId), "workflow-a") }

            // when / then
            assertThatThrownBy {
                runBlocking {
                    repository.charge(
                        transactionFor(transactionId),
                        "workflow-b",
                        responseFor(transactionId),
                    )
                }
            }.isInstanceOf(IllegalStateException::class.java)
                .hasMessage("Fee record '$transactionId' was not started for workflow workflow-b, so it can't be charged")
            assertThat(StoredFeeRecords.of(transactionId)).isEqualTo(FeeRecord.pending("workflow-a"))
        }

        @Test
        fun `charging a transaction id that was never started fails and stores nothing`() {
            // given
            val transactionId = newTransactionId()

            // when / then
            assertThatThrownBy {
                runBlocking {
                    repository.charge(
                        transactionFor(transactionId),
                        "workflow-a",
                        responseFor(transactionId),
                    )
                }
            }.isInstanceOf(IllegalStateException::class.java)
            assertThat(StoredFeeRecords.count(transactionId)).isZero()
        }

        @Test
        fun `starting a charged transaction id returns its charged record`() =
            runBlocking<Unit> {
                // given
                val transactionId = newTransactionId()
                repository.start(transactionFor(transactionId), "workflow-a")
                val response = responseFor(transactionId)
                repository.charge(transactionFor(transactionId), "workflow-a", response)

                // when
                val record = repository.start(transactionFor(transactionId), "workflow-b")

                // then
                assertThat(record).isEqualTo(FeeRecord.pending("workflow-a").charge(response))
            }

        @Test
        fun `the table refuses a half-written charge, pending or charged`() {
            val halfWrittenCharges: List<(String) -> InsertSetMoreStep<FeeRecordRecord>> =
                listOf(
                    // pending, with a fee
                    { id -> rowOf(id, FeeRecordStatus.PENDING).set(FEE_RECORD.FEE, BigDecimal("1.50")) },
                    // charged, without charged_at
                    { id ->
                        rowOf(id, FeeRecordStatus.CHARGED)
                            .set(FEE_RECORD.FEE, BigDecimal("1.50"))
                            .set(FEE_RECORD.RATE, BigDecimal("0.0015"))
                            .set(FEE_RECORD.DESCRIPTION, "Standard fee rate of 0.15%")
                    },
                )
            halfWrittenCharges.forEach { insert ->
                // given
                val transactionId = newTransactionId()

                // when / then
                assertThatThrownBy { runBlocking { insert(transactionId).awaitSingle() } }
                    .hasStackTraceContaining("fee_record_charge_exactly_when_charged")
                assertThat(StoredFeeRecords.count(transactionId)).isZero()
            }
        }

        @Test
        fun `lookups cut short at any moment still give their connection back to the pool`() =
            runBlocking<Unit> {
                // given: a charged record, and a repository on a pool of a single connection
                val transactionId = newTransactionId()
                repository.start(transactionFor(transactionId), "workflow-a")
                repository.charge(transactionFor(transactionId), "workflow-a", responseFor(transactionId))
                val pool = poolOfOneConnection()
                try {
                    val onOneConnection = PostgresFeeRecordRepository(pool, clock)

                    // when: lookups cut short by a timeout, as ChargedFees cuts a slow one short, at varying moments of
                    // their run: acquiring the connection, running the query, reading the row
                    repeat(CUT_SHORT_LOOKUPS) { attempt ->
                        withTimeoutOrNull((1 + attempt % 3).milliseconds) { onOneConnection.findCharged(transactionId) }
                    }

                    // then: the one connection still serves a lookup, and nothing holds it afterwards
                    val charged = withTimeout(10.seconds) { onOneConnection.findCharged(transactionId) }
                    assertThat(charged).isEqualTo(responseFor(transactionId))
                    assertThat(pool.metrics.orElseThrow().acquiredSize()).isZero()
                } finally {
                    pool.dispose()
                }
            }

        @Test
        fun `every query gives its connection back to the pool, so a pool of one serves them one after another`() =
            runBlocking<Unit> {
                // given: a repository on a pool of a single connection
                val pool = poolOfOneConnection()
                try {
                    val onOneConnection = PostgresFeeRecordRepository(pool, clock)
                    val transactionId = newTransactionId()

                    // when: each query needs the connection the one before used; a query that kept it would wait forever
                    val charged =
                        withTimeout(10.seconds) {
                            onOneConnection.start(transactionFor(transactionId), "workflow-a")
                            onOneConnection.start(transactionFor(transactionId), "workflow-a")
                            onOneConnection.charge(transactionFor(transactionId), "workflow-a", responseFor(transactionId))
                            onOneConnection.findCharged(transactionId)
                        }

                    // then
                    assertThat(charged).isEqualTo(responseFor(transactionId))
                    assertThat(pool.metrics.orElseThrow().acquiredSize()).isZero()
                } finally {
                    pool.dispose()
                }
            }

        @Test
        fun `finds no charge for a transaction id without a record or with a pending one`() =
            runBlocking<Unit> {
                // given
                val unknown = newTransactionId()
                val pending = newTransactionId()
                repository.start(transactionFor(pending), "workflow-a")

                // when
                val found = listOf(repository.findCharged(unknown), repository.findCharged(pending))

                // then
                assertThat(found).containsOnlyNulls()
            }

        @Test
        fun `concurrent starts with different workflow ids leave exactly one record, and only its workflow can charge it`() =
            runBlocking<Unit> {
                // given: starts released together, each with its own workflow id
                val transactionId = newTransactionId()
                val workflowIds = List(CONCURRENT_STARTS) { "workflow-$it" }
                val gate = CompletableDeferred<Unit>()
                val starts =
                    workflowIds.map { workflowId ->
                        async(Dispatchers.IO) {
                            gate.await()
                            workflowId to repository.start(transactionFor(transactionId), workflowId)
                        }
                    }

                // when
                gate.complete(Unit)
                val results = starts.awaitAll()

                // then: one record, and every start saw it: only the winner got its own workflow id back
                val winner = checkNotNull(StoredFeeRecords.of(transactionId)).workflowId
                assertThat(StoredFeeRecords.count(transactionId)).isEqualTo(1)
                assertThat(results.map { it.second }).containsOnly(FeeRecord.pending(winner))
                assertThat(
                    results
                        .filter { (workflowId, record) ->
                            record.workflowId == workflowId
                        }.map { it.first },
                ).containsExactly(winner)

                // then: a losing workflow can't charge it, the winner can
                val response = responseFor(transactionId)
                val loser = workflowIds.first { it != winner }
                assertThat(runCatching { repository.charge(transactionFor(transactionId), loser, response) }.isFailure).isTrue()
                repository.charge(transactionFor(transactionId), winner, response)
                assertThat(StoredFeeRecords.of(transactionId)).isEqualTo(FeeRecord.pending(winner).charge(response))
            }

        private fun newTransactionId() = "it-repository-${UUID.randomUUID().toString().take(13)}"

        /** The reference request of [transactionId], as TransactionMapper builds it: the transaction [responseFor] is the charge of. */
        private fun transactionFor(transactionId: String) =
            Transaction(
                transactionId = transactionId,
                amount = BigDecimal("1000"),
                asset = Asset.USD,
                assetType = AssetType.FIAT,
                type = TransactionType.MOBILE_TOP_UP,
                state = TransactionState.SETTLED_PENDING_FEE,
                createdAt = LocalDateTime.of(2023, 8, 30, 15, 42, 17, 610_059_000),
            )

        /** A pool of a single connection to the shared Postgres: a query that keeps its connection blocks every other. */
        private fun poolOfOneConnection() =
            ConnectionPool(
                ConnectionPoolConfiguration
                    .builder(ConnectionFactories.get(PostgresTestEnvironment.urlWithCredentials))
                    .initialSize(1)
                    .maxSize(1)
                    .build(),
            )

        /** An insert of the reference request of [transactionId] with [status], started for `workflow-a`: no charge yet. */
        private fun rowOf(transactionId: String, status: FeeRecordStatus): InsertSetMoreStep<FeeRecordRecord> {
            val transaction = transactionFor(transactionId)
            return PostgresTestEnvironment.dsl
                .insertInto(FEE_RECORD)
                .set(FEE_RECORD.TRANSACTION_ID, transactionId)
                .set(FEE_RECORD.AMOUNT, transaction.amount)
                .set(FEE_RECORD.ASSET, transaction.asset.value)
                .set(FEE_RECORD.ASSET_TYPE, transaction.assetType.value)
                .set(FEE_RECORD.TYPE, transaction.type.value)
                .set(FEE_RECORD.STATE, transaction.state.value)
                .set(FEE_RECORD.CREATED_AT, transaction.createdAt)
                .set(FEE_RECORD.STATUS, status.name)
                .set(FEE_RECORD.WORKFLOW_ID, "workflow-a")
                .set(FEE_RECORD.STARTED_AT, STARTED_AT)
        }

        private fun responseFor(transactionId: String) =
            FeeResponse(
                transactionId = transactionId,
                amount = BigDecimal("1000"),
                asset = "USD",
                type = "Mobile Top Up",
                fee = BigDecimal("1.50"),
                rate = BigDecimal("0.0015"),
                description = "Standard fee rate of 0.15%",
            )

        private companion object {
            const val CONCURRENT_STARTS = 10
            const val CUT_SHORT_LOOKUPS = 300

            // microseconds at most: Postgres keeps no more
            val STARTED_AT: Instant = Instant.parse("2026-11-13T10:15:30.123456Z")
            val CHARGED_AT: Instant = Instant.parse("2026-11-13T10:15:31.654321Z")
        }
    }
