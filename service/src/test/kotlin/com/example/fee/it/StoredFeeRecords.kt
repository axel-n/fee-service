package com.example.fee.it

import com.example.fee.contract.FeeResponse
import com.example.fee.domain.Asset
import com.example.fee.domain.AssetType
import com.example.fee.domain.Transaction
import com.example.fee.domain.TransactionState
import com.example.fee.domain.TransactionType
import com.example.fee.record.FeeRecord
import com.example.fee.record.FeeRecordStatus
import kotlinx.coroutines.runBlocking
import org.springframework.r2dbc.connection.SingleConnectionFactory
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.await
import org.springframework.r2dbc.core.awaitOneOrNull
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.r2dbc.core.awaitSingle
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime

/**
 * The fee records read and written straight in the shared Postgres ([PostgresTestEnvironment]), past the app's own code,
 * so a test sees what is stored rather than what the app reports.
 */
object StoredFeeRecords {
    /** The token of [claimByARunThatNeverFinished] and [claimInOpenTransaction]: no workflow run of the app ever holds it. */
    const val FOREIGN_TOKEN = "a-run-that-never-finished"

    private const val SELECT_RECORD = """
        SELECT status, claim_token, amount, asset, type, fee, rate, description
        FROM fee_record
        WHERE transaction_id = :id
    """

    private const val SELECT_REQUEST = """
        SELECT amount, asset, asset_type, type, state, created_at
        FROM fee_record
        WHERE transaction_id = :id
    """

    private const val SELECT_TIMES = "SELECT claimed_at, charged_at FROM fee_record WHERE transaction_id = :id"

    private const val COUNT = "SELECT count(*) AS records FROM fee_record WHERE transaction_id = :id"

    // a Mobile Top Up of 1000 USD, the requirements' sample
    private const val INSERT_FOREIGN_CLAIM = """
        INSERT INTO fee_record
            (transaction_id, amount, asset, asset_type, type, state, created_at, status, claim_token, claimed_at)
        VALUES
            (:id, 1000, 'USD', 'FIAT', 'Mobile Top Up', 'SETTLED - PENDING FEE', '2023-08-30 15:42:17.610059',
             'IN_PROGRESS', :token, now())
    """

    private const val BEGIN = "BEGIN"
    private const val COMMIT = "COMMIT"
    private const val SELECT_BACKEND_PID = "SELECT pg_backend_pid() AS pid"
    private const val COUNT_BLOCKED_BY = "SELECT count(*) AS blocked FROM pg_stat_activity WHERE :pid = ANY(pg_blocking_pids(pid))"

    /** The record of [transactionId], or `null` if the id was never claimed. */
    fun of(transactionId: String): FeeRecord? =
        runBlocking {
            PostgresTestEnvironment.client
                .sql(SELECT_RECORD)
                .bind("id", transactionId)
                .map { row ->
                    val status = FeeRecordStatus.valueOf(row.get("status", String::class.java).orEmpty())
                    val claimToken = row.get("claim_token", String::class.java).orEmpty()
                    val response =
                        if (status == FeeRecordStatus.COMPLETED) {
                            FeeResponse(
                                transactionId = transactionId,
                                amount = checkNotNull(row.get("amount", BigDecimal::class.java)),
                                asset = row.get("asset", String::class.java).orEmpty(),
                                type = row.get("type", String::class.java).orEmpty(),
                                fee = checkNotNull(row.get("fee", BigDecimal::class.java)),
                                rate = checkNotNull(row.get("rate", BigDecimal::class.java)),
                                description = row.get("description", String::class.java).orEmpty(),
                            )
                        } else {
                            null
                        }
                    FeeRecord(status, claimToken, response)
                }.awaitOneOrNull()
        }

    /** The request fields stored in the record of [transactionId], or `null` if the id was never claimed. */
    fun transactionOf(transactionId: String): Transaction? =
        runBlocking {
            PostgresTestEnvironment.client
                .sql(SELECT_REQUEST)
                .bind("id", transactionId)
                .map { row ->
                    Transaction(
                        transactionId = transactionId,
                        amount = checkNotNull(row.get("amount", BigDecimal::class.java)),
                        asset = Asset.fromValue(row.get("asset", String::class.java)),
                        assetType = AssetType.fromValue(row.get("asset_type", String::class.java)),
                        type = TransactionType.fromValue(row.get("type", String::class.java)),
                        state = TransactionState.fromValue(row.get("state", String::class.java)),
                        createdAt = checkNotNull(row.get("created_at", LocalDateTime::class.java)),
                    )
                }.awaitOneOrNull()
        }

    /** When the record of [transactionId] was claimed and charged, or `null` if the id was never claimed. */
    fun timesOf(transactionId: String): StoredTimes? =
        runBlocking {
            PostgresTestEnvironment.client
                .sql(SELECT_TIMES)
                .bind("id", transactionId)
                .map { row ->
                    StoredTimes(
                        claimedAt = checkNotNull(row.get("claimed_at", Instant::class.java)),
                        chargedAt = row.get("charged_at", Instant::class.java),
                    )
                }.awaitOneOrNull()
        }

    /** How many rows `fee_record` holds for [transactionId]: 0 or 1, the primary key allows no more. */
    fun count(transactionId: String): Long =
        runBlocking {
            PostgresTestEnvironment.client
                .sql(COUNT)
                .bind("id", transactionId)
                .map { row -> checkNotNull(row.get("records", Long::class.javaObjectType)) }
                .awaitSingle()
        }

    /**
     * Inserts an `IN_PROGRESS` record of [transactionId] (the requirements' sample request) under [FOREIGN_TOKEN], as a
     * run killed after its CLAIM leaves it once Restate has dropped the run.
     */
    fun claimByARunThatNeverFinished(transactionId: String) {
        runBlocking {
            val inserted =
                PostgresTestEnvironment.client
                    .sql(INSERT_FOREIGN_CLAIM)
                    .bind("id", transactionId)
                    .bind("token", FOREIGN_TOKEN)
                    .fetch()
                    .awaitRowsUpdated()
            check(inserted == 1L) { "the record of $transactionId was not inserted" }
        }
    }

    /**
     * Inserts an `IN_PROGRESS` record of [transactionId] under [FOREIGN_TOKEN], as [claimByARunThatNeverFinished] does,
     * but in a transaction left open on a connection of its own: other connections don't see the record until
     * [OpenClaim.commit], and an insert of the same id waits for it. A claim that loses a race, made deterministic.
     */
    fun claimInOpenTransaction(transactionId: String): OpenClaim {
        // suppressClose: every statement runs on the one connection, so the transaction stays open between them
        val connection = SingleConnectionFactory(PostgresTestEnvironment.urlWithCredentials, true)
        val client = DatabaseClient.create(connection)
        try {
            return runBlocking {
                val backendPid =
                    client
                        .sql(SELECT_BACKEND_PID)
                        .map { row -> checkNotNull(row.get("pid", Int::class.javaObjectType)) }
                        .awaitSingle()
                client.sql(BEGIN).await()
                val inserted =
                    client
                        .sql(INSERT_FOREIGN_CLAIM)
                        .bind("id", transactionId)
                        .bind("token", FOREIGN_TOKEN)
                        .fetch()
                        .awaitRowsUpdated()
                check(inserted == 1L) { "the record of $transactionId was not inserted" }
                OpenClaim(client, connection, backendPid)
            }
        } catch (failure: Exception) {
            // an open transaction would hold its row, and its lock on fee_record, until the JVM exits
            connection.destroy()
            throw failure
        }
    }

    /**
     * The open transaction of [claimInOpenTransaction], on the connection of Postgres backend [backendPid]. Closing the
     * connection without [commit] rolls it back, so a statement waiting for it goes on.
     */
    class OpenClaim(
        private val client: DatabaseClient,
        private val connection: SingleConnectionFactory,
        private val backendPid: Int,
    ) : AutoCloseable {
        /** How many statements of other connections wait for this transaction, such as an insert of the same id. */
        fun blockedStatements(): Long =
            runBlocking {
                PostgresTestEnvironment.client
                    .sql(COUNT_BLOCKED_BY)
                    .bind("pid", backendPid)
                    .map { row -> checkNotNull(row.get("blocked", Long::class.javaObjectType)) }
                    .awaitSingle()
            }

        /** Commits the record: every connection sees it from now on. */
        fun commit() {
            runBlocking { client.sql(COMMIT).await() }
        }

        override fun close() {
            connection.destroy()
        }
    }

    /** The `claimed_at` and `charged_at` of a record; [chargedAt] is `null` until it is `COMPLETED`. */
    data class StoredTimes(
        val claimedAt: Instant,
        val chargedAt: Instant?,
    )
}
