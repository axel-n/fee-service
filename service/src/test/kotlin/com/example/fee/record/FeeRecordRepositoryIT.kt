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
import io.r2dbc.pool.ConnectionPool
import io.r2dbc.spi.ConnectionFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.transaction.reactive.TransactionCallback
import org.springframework.transaction.reactive.TransactionalOperator
import reactor.core.publisher.Flux
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * [PostgresFeeRecordRepository] against the shared Postgres of the ITs, with the table the app created at startup.
 * Each test uses its own `transaction_id`. Rows are read back through [StoredFeeRecords], past the repository.
 */
class FeeRecordRepositoryIT
    @Autowired
    constructor(
        private val connectionFactory: ConnectionFactory,
        private val transactionalOperator: TransactionalOperator,
    ) : RestateIntegrationTest() {
        private val transactions = CountingTransactionalOperator(transactionalOperator)
        private val clock = TestClock().apply { pin(CLAIMED_AT) }
        private val repository = PostgresFeeRecordRepository(connectionFactory, transactions, clock)

        @Test
        fun `the app's connections to Postgres come from a pool`() {
            // given / when: the ConnectionFactory the app injects into the repository
            val injected = connectionFactory

            // then: Boot pools R2DBC connections only when r2dbc-pool is on the classpath
            assertThat(injected).isInstanceOf(ConnectionPool::class.java)
        }

        @Test
        fun `claiming a new transaction id inserts an IN_PROGRESS record with the token, in one transaction`() =
            runBlocking<Unit> {
                // given
                val transactionId = newTransactionId()

                // when
                val record = repository.claim(transactionFor(transactionId), "token-a")

                // then
                assertThat(record).isEqualTo(FeeRecord.inProgress("token-a"))
                assertThat(StoredFeeRecords.of(transactionId)).isEqualTo(record)
                assertThat(transactions.count).describedAs("transactions opened").isEqualTo(1)
            }

        @Test
        fun `claiming stores every field of the request, created_at to the microsecond, and when it was claimed`() =
            runBlocking<Unit> {
                // given
                val transaction = transactionFor(newTransactionId())

                // when
                repository.claim(transaction, "token-a")

                // then
                assertThat(StoredFeeRecords.transactionOf(transaction.transactionId)).isEqualTo(transaction)
                assertThat(StoredFeeRecords.timesOf(transaction.transactionId)).isEqualTo(StoredTimes(CLAIMED_AT, chargedAt = null))
            }

        @Test
        fun `claiming an id that has a record opens no transaction and changes nothing, whatever the token or payload`() =
            runBlocking<Unit> {
                // given
                val transactionId = newTransactionId()
                repository.claim(transactionFor(transactionId), "token-a")
                val opened = transactions.count

                // when
                val again = repository.claim(transactionFor(transactionId), "token-a")
                val other = repository.claim(transactionFor(transactionId).copy(amount = BigDecimal("2000")), "token-b")

                // then: the record of the first claim, both times, with the first claim's request
                assertThat(listOf(again, other)).containsOnly(FeeRecord.inProgress("token-a"))
                assertThat(StoredFeeRecords.of(transactionId)).isEqualTo(FeeRecord.inProgress("token-a"))
                assertThat(StoredFeeRecords.transactionOf(transactionId)).isEqualTo(transactionFor(transactionId))
                assertThat(transactions.count - opened).describedAs("transactions opened").isZero()
            }

        @Test
        fun `completing with the claim token stores the response and when it was charged, every scale kept`() =
            runBlocking<Unit> {
                // given
                val transactionId = newTransactionId()
                repository.claim(transactionFor(transactionId), "token-a")
                val response = responseFor(transactionId)
                clock.pin(CHARGED_AT)

                // when
                repository.complete(transactionId, "token-a", response)

                // then: equal as data classes, so 1000 is still 1000 and 1.50 still 1.50
                assertThat(repository.findCharged(transactionId)).isEqualTo(response)
                assertThat(StoredFeeRecords.of(transactionId)).isEqualTo(FeeRecord.inProgress("token-a").complete(response))
                assertThat(StoredFeeRecords.timesOf(transactionId)).isEqualTo(StoredTimes(CLAIMED_AT, CHARGED_AT))
            }

        @Test
        fun `completing again with the same response changes nothing, not even charged_at, so a step run again after a crash is safe`() =
            runBlocking<Unit> {
                // given
                val transactionId = newTransactionId()
                repository.claim(transactionFor(transactionId), "token-a")
                val response = responseFor(transactionId)
                clock.pin(CHARGED_AT)
                repository.complete(transactionId, "token-a", response)
                clock.pin(CHARGED_AT.plusSeconds(30))

                // when
                repository.complete(transactionId, "token-a", response)

                // then
                assertThat(StoredFeeRecords.of(transactionId)).isEqualTo(FeeRecord.inProgress("token-a").complete(response))
                assertThat(StoredFeeRecords.timesOf(transactionId)).isEqualTo(StoredTimes(CLAIMED_AT, CHARGED_AT))
                assertThat(StoredFeeRecords.count(transactionId)).isEqualTo(1)
            }

        @Test
        fun `completing with another token fails and leaves the record as it was`() {
            // given
            val transactionId = newTransactionId()
            runBlocking { repository.claim(transactionFor(transactionId), "token-a") }

            // when / then
            assertThatThrownBy { runBlocking { repository.complete(transactionId, "token-b", responseFor(transactionId)) } }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessage("Fee record '$transactionId' is not claimed by token token-b, so it can't be completed")
            assertThat(StoredFeeRecords.of(transactionId)).isEqualTo(FeeRecord.inProgress("token-a"))
        }

        @Test
        fun `completing a transaction id that was never claimed fails and stores nothing`() {
            // given
            val transactionId = newTransactionId()

            // when / then
            assertThatThrownBy { runBlocking { repository.complete(transactionId, "token-a", responseFor(transactionId)) } }
                .isInstanceOf(IllegalStateException::class.java)
            assertThat(StoredFeeRecords.count(transactionId)).isZero()
        }

        @Test
        fun `claiming a charged transaction id returns its completed record`() =
            runBlocking<Unit> {
                // given
                val transactionId = newTransactionId()
                repository.claim(transactionFor(transactionId), "token-a")
                val response = responseFor(transactionId)
                repository.complete(transactionId, "token-a", response)

                // when
                val record = repository.claim(transactionFor(transactionId), "token-b")

                // then
                assertThat(record).isEqualTo(FeeRecord.inProgress("token-a").complete(response))
            }

        @Test
        fun `the table refuses a half-written charge, in progress or completed`() {
            listOf(INSERT_IN_PROGRESS_WITH_A_FEE, INSERT_COMPLETED_WITHOUT_CHARGED_AT).forEach { insert ->
                // given
                val transactionId = newTransactionId()

                // when / then
                assertThatThrownBy {
                    runBlocking {
                        PostgresTestEnvironment.client
                            .sql(insert)
                            .bind("id", transactionId)
                            .fetch()
                            .awaitRowsUpdated()
                    }
                }.hasStackTraceContaining("fee_record_charge_exactly_when_completed")
                assertThat(StoredFeeRecords.count(transactionId)).isZero()
            }
        }

        @Test
        fun `finds no charge for a transaction id without a record or with one in progress`() =
            runBlocking<Unit> {
                // given
                val unknown = newTransactionId()
                val inProgress = newTransactionId()
                repository.claim(transactionFor(inProgress), "token-a")
                val opened = transactions.count

                // when
                val found = listOf(repository.findCharged(unknown), repository.findCharged(inProgress))

                // then
                assertThat(found).containsOnlyNulls()
                assertThat(transactions.count - opened).describedAs("transactions opened").isZero()
            }

        @Test
        fun `concurrent claims with different tokens leave exactly one claim, and only its token can complete it`() =
            runBlocking<Unit> {
                // given: claims released together, each with its own token
                val transactionId = newTransactionId()
                val tokens = List(CONCURRENT_CLAIMS) { "token-$it" }
                val gate = CompletableDeferred<Unit>()
                val claims =
                    tokens.map { token ->
                        async(Dispatchers.IO) {
                            gate.await()
                            token to repository.claim(transactionFor(transactionId), token)
                        }
                    }

                // when
                gate.complete(Unit)
                val results = claims.awaitAll()

                // then: one record, and every claim saw it: only the winner got its own token back
                val winner = checkNotNull(StoredFeeRecords.of(transactionId)).claimToken
                assertThat(StoredFeeRecords.count(transactionId)).isEqualTo(1)
                assertThat(results.map { it.second }).containsOnly(FeeRecord.inProgress(winner))
                assertThat(results.filter { (token, record) -> record.claimToken == token }.map { it.first }).containsExactly(winner)

                // then: a losing token can't complete it, the winner's can
                val response = responseFor(transactionId)
                val loser = tokens.first { it != winner }
                assertThat(runCatching { repository.complete(transactionId, loser, response) }.isFailure).isTrue()
                repository.complete(transactionId, winner, response)
                assertThat(StoredFeeRecords.of(transactionId)).isEqualTo(FeeRecord.inProgress(winner).complete(response))
            }

        private fun newTransactionId() = "it-repository-${UUID.randomUUID().toString().take(13)}"

        /** The reference request of [transactionId], as MAP builds it: the transaction [responseFor] is the charge of. */
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

        /** The app's [TransactionalOperator], counting the transactions it opens. */
        private class CountingTransactionalOperator(
            private val delegate: TransactionalOperator,
        ) : TransactionalOperator by delegate {
            private val opened = AtomicInteger()

            val count: Int get() = opened.get()

            override fun <T : Any> execute(action: TransactionCallback<T>): Flux<T> {
                opened.incrementAndGet()
                return delegate.execute(action)
            }
        }

        private companion object {
            const val CONCURRENT_CLAIMS = 10

            const val INSERT_IN_PROGRESS_WITH_A_FEE = """
                INSERT INTO fee_record
                    (transaction_id, amount, asset, asset_type, type, state, created_at, status, claim_token, claimed_at, fee)
                VALUES
                    (:id, 1000, 'USD', 'FIAT', 'Mobile Top Up', 'SETTLED - PENDING FEE', now(), 'IN_PROGRESS', 'token-a', now(), 1.50)
            """

            const val INSERT_COMPLETED_WITHOUT_CHARGED_AT = """
                INSERT INTO fee_record
                    (transaction_id, amount, asset, asset_type, type, state, created_at, status, claim_token, claimed_at, fee,
                     rate, description)
                VALUES
                    (:id, 1000, 'USD', 'FIAT', 'Mobile Top Up', 'SETTLED - PENDING FEE', now(), 'COMPLETED', 'token-a', now(), 1.50,
                     0.0015, 'Standard fee rate of 0.15%')
            """

            // microseconds at most: Postgres keeps no more
            val CLAIMED_AT: Instant = Instant.parse("2026-11-13T10:15:30.123456Z")
            val CHARGED_AT: Instant = Instant.parse("2026-11-13T10:15:31.654321Z")
        }
    }
