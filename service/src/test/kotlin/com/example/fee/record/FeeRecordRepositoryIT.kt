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
import com.example.fee.it.TestClock
import com.example.fee.it.awaitUntil
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
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.math.RoundingMode
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
        private val clock = TestClock().apply { pin(CHARGED_AT) }
        private val repository = PostgresFeeRecordRepository(connectionFactory, clock)

        @Test
        fun `the app's connections to Postgres come from a pool`() {
            // given / when: the ConnectionFactory the app injects into the repository
            val injected = connectionFactory

            // then: Boot pools R2DBC connections only when r2dbc-pool is on the classpath
            assertThat(injected).isInstanceOf(ConnectionPool::class.java)
        }

        @Test
        fun `charging a new transaction id stores every field of the request, created_at to the microsecond, the fee and when`() =
            runBlocking<Unit> {
                // given
                val transaction = transactionFor(newTransactionId())
                val response = responseFor(transaction)

                // when
                val charged = repository.charge(transaction, response)

                // then: equal as data classes, so 1000 is still 1000 and 1.50 still 1.50
                assertThat(charged).isEqualTo(response)
                assertThat(StoredFeeRecords.of(transaction.transactionId)).isEqualTo(response)
                assertThat(StoredFeeRecords.transactionOf(transaction.transactionId)).isEqualTo(transaction)
                assertThat(StoredFeeRecords.chargedAtOf(transaction.transactionId)).isEqualTo(CHARGED_AT)
                assertThat(repository.findCharged(transaction.transactionId)).isEqualTo(response)
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
                        repository.charge(transaction, responseFor(transaction))
                        StoredFeeRecords.transactionOf(transaction.transactionId)
                    } finally {
                        TimeZone.setDefault(defaultZone)
                    }

                // then: as Postgres itself shows it, and as jOOQ reads it back
                assertThat(StoredFeeRecords.createdAtAsStored(transaction.transactionId)).isEqualTo("2024-03-31 02:30:00.123456")
                assertThat(readBack).isEqualTo(transaction)
            }

        @Test
        fun `a zone-free timestamp that is null binds and reads back as null`() = runBlocking<Unit> {
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
            val transaction = transactionFor(newTransactionId())
            val transactionId = transaction.transactionId

            // when
            val charging = runCatching { runBlocking { refused.charge(transaction, responseFor(transaction)) } }
            val reading = runCatching { runBlocking { refused.findCharged(transactionId) } }

            // then: one line each, without the statement or the request's values
            assertThat(charging.exceptionOrNull())
                .isInstanceOf(DataAccessException::class.java)
                .hasMessageStartingWith("Charging the fee record of '$transactionId' failed: ")
            assertThat(reading.exceptionOrNull())
                .isInstanceOf(DataAccessException::class.java)
                .hasMessageStartingWith("Reading the fee record of '$transactionId' failed: ")
            listOf(charging, reading).forEach { failed ->
                assertThat(failed.exceptionOrNull()).hasMessageContaining("password authentication failed")
                listOf("\n", "\"fee_record\"", "Mobile Top Up").forEach { revealing ->
                    assertThat(failed.exceptionOrNull()).hasMessageNotContaining(revealing)
                }
            }
        }

        @Test
        fun `charging again with the same response changes nothing, not even charged_at, so a step run again after a crash is safe`() =
            runBlocking<Unit> {
                // given
                val transaction = transactionFor(newTransactionId())
                val response = responseFor(transaction)
                repository.charge(transaction, response)
                clock.pin(CHARGED_AT.plusSeconds(30))

                // when
                val again = repository.charge(transaction, response)

                // then
                assertThat(again).isEqualTo(response)
                assertThat(StoredFeeRecords.of(transaction.transactionId)).isEqualTo(response)
                assertThat(StoredFeeRecords.chargedAtOf(transaction.transactionId)).isEqualTo(CHARGED_AT)
                assertThat(StoredFeeRecords.count(transaction.transactionId)).isEqualTo(1)
            }

        @Test
        fun `charging an id charged earlier returns the earlier charge and leaves its record as it was, whatever the payload`() =
            runBlocking<Unit> {
                // given: the id was charged for another amount
                val transactionId = newTransactionId()
                val earlier = transactionFor(transactionId).copy(amount = BigDecimal("2000"))
                repository.charge(earlier, responseFor(earlier))
                val later = transactionFor(transactionId)

                // when
                val charged = repository.charge(later, responseFor(later))

                // then: the first charge stands
                assertThat(charged).isEqualTo(responseFor(earlier))
                assertThat(StoredFeeRecords.of(transactionId)).isEqualTo(responseFor(earlier))
                assertThat(StoredFeeRecords.transactionOf(transactionId)).isEqualTo(earlier)
            }

        @Test
        fun `a charge whose insert meets a charge committed meanwhile returns that charge, not its own`() = runBlocking<Unit> {
            // given: another charge of the id, not committed yet, so this charge's insert waits for it
            val transaction = transactionFor(newTransactionId())
            val other = responseFor(transaction.copy(amount = BigDecimal("2000")))
            StoredFeeRecords.chargeInOpenTransaction(transaction.transactionId, other).use { open ->
                val charge = async(Dispatchers.IO) { repository.charge(transaction, responseFor(transaction)) }
                awaitUntil("the charge's insert waits for the other charge") { open.blockedStatements() == 1L }

                // when
                open.commit()
                val charged = charge.await()

                // then: the other charge, which stays as it was
                assertThat(charged).isEqualTo(other)
                assertThat(StoredFeeRecords.of(transaction.transactionId)).isEqualTo(other)
            }
        }

        @Test
        fun `rejects the response of another transaction and stores nothing`() {
            // given
            val transaction = transactionFor(newTransactionId())
            val ofAnother = responseFor(transactionFor(newTransactionId()))

            // when / then
            assertThatThrownBy { runBlocking { repository.charge(transaction, ofAnother) } }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining(transaction.transactionId)
            assertThat(StoredFeeRecords.count(transaction.transactionId)).isZero()
        }

        @Test
        fun `lookups cut short at any moment still give their connection back to the pool`() = runBlocking<Unit> {
            // given: a charged record, and a repository on a pool of a single connection
            val transaction = transactionFor(newTransactionId())
            repository.charge(transaction, responseFor(transaction))
            val pool = poolOfOneConnection()
            try {
                val onOneConnection = PostgresFeeRecordRepository(pool, clock)

                // when: lookups cut short by a timeout, as ChargedFees cuts a slow one short, at varying moments of
                // their run: acquiring the connection, running the query, reading the row
                repeat(CUT_SHORT_LOOKUPS) { attempt ->
                    withTimeoutOrNull((1 + attempt % 3).milliseconds) { onOneConnection.findCharged(transaction.transactionId) }
                }

                // then: the one connection still serves a lookup, and nothing holds it afterwards
                val charged = withTimeout(10.seconds) { onOneConnection.findCharged(transaction.transactionId) }
                assertThat(charged).isEqualTo(responseFor(transaction))
                assertThat(pool.metrics.orElseThrow().acquiredSize()).isZero()
            } finally {
                pool.dispose()
            }
        }

        @Test
        fun `every query gives its connection back to the pool, so a pool of one serves them one after another`() = runBlocking<Unit> {
            // given: a repository on a pool of a single connection
            val pool = poolOfOneConnection()
            try {
                val onOneConnection = PostgresFeeRecordRepository(pool, clock)
                val transaction = transactionFor(newTransactionId())

                // when: each query needs the connection the one before used; a query that kept it would wait forever.
                // The second charge meets the first one's record, so it reads it too
                val charged =
                    withTimeout(10.seconds) {
                        onOneConnection.charge(transaction, responseFor(transaction))
                        onOneConnection.charge(transaction, responseFor(transaction))
                        onOneConnection.findCharged(transaction.transactionId)
                    }

                // then
                assertThat(charged).isEqualTo(responseFor(transaction))
                assertThat(pool.metrics.orElseThrow().acquiredSize()).isZero()
            } finally {
                pool.dispose()
            }
        }

        @Test
        fun `finds no charge for a transaction id without a record`() = runBlocking<Unit> {
            // given
            val unknown = newTransactionId()

            // when
            val found = repository.findCharged(unknown)

            // then
            assertThat(found).isNull()
        }

        @Test
        fun `concurrent charges of one id with different payloads leave exactly one record, and all return its charge`() =
            runBlocking<Unit> {
                // given: charges released together, each for its own amount
                val transactionId = newTransactionId()
                val transactions = List(CONCURRENT_CHARGES) { transactionFor(transactionId).copy(amount = BigDecimal(1_000 + it)) }
                val gate = CompletableDeferred<Unit>()
                val charges =
                    transactions.map { transaction ->
                        async(Dispatchers.IO) {
                            gate.await()
                            repository.charge(transaction, responseFor(transaction))
                        }
                    }

                // when
                gate.complete(Unit)
                val results = charges.awaitAll()

                // then: one record, one of the payloads, and every charge returned it
                val stored = checkNotNull(StoredFeeRecords.of(transactionId))
                assertThat(StoredFeeRecords.count(transactionId)).isEqualTo(1)
                assertThat(results).containsOnly(stored)
                assertThat(transactions.map(::responseFor)).contains(stored)
            }

        private fun newTransactionId() = "it-repository-${UUID.randomUUID().toString().take(13)}"

        /** The reference request of [transactionId], as TransactionMapper builds it. */
        private fun transactionFor(transactionId: String) = Transaction(
            transactionId = transactionId,
            amount = BigDecimal("1000"),
            asset = Asset.USD,
            assetType = AssetType.FIAT,
            type = TransactionType.MOBILE_TOP_UP,
            state = TransactionState.SETTLED_PENDING_FEE,
            createdAt = LocalDateTime.of(2023, 8, 30, 15, 42, 17, 610_059_000),
        )

        /** The charge of [transaction], a Mobile Top Up at 0.15%: 1.50 for the reference amount of 1000. */
        private fun responseFor(transaction: Transaction) = FeeResponse(
            transactionId = transaction.transactionId,
            amount = transaction.amount,
            asset = transaction.asset.value,
            type = transaction.type.value,
            fee = transaction.amount.multiply(BigDecimal("0.0015")).setScale(2, RoundingMode.HALF_EVEN),
            rate = BigDecimal("0.0015"),
            description = "Standard fee rate of 0.15%",
        )

        /** A pool of a single connection to the shared Postgres: a query that keeps its connection blocks every other. */
        private fun poolOfOneConnection() = ConnectionPool(
            ConnectionPoolConfiguration
                .builder(ConnectionFactories.get(PostgresTestEnvironment.urlWithCredentials))
                .initialSize(1)
                .maxSize(1)
                .build(),
        )

        private companion object {
            const val CONCURRENT_CHARGES = 10
            const val CUT_SHORT_LOOKUPS = 300

            // microseconds at most: Postgres keeps no more
            val CHARGED_AT: Instant = Instant.parse("2026-11-13T10:15:31.654321Z")
        }
    }
