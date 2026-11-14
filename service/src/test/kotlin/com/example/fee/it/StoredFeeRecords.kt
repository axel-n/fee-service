package com.example.fee.it

import com.example.fee.contract.FeeResponse
import com.example.fee.domain.Asset
import com.example.fee.domain.AssetType
import com.example.fee.domain.Transaction
import com.example.fee.domain.TransactionState
import com.example.fee.domain.TransactionType
import com.example.fee.record.jooq.tables.references.FEE_RECORD
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.springframework.r2dbc.connection.SingleConnectionFactory
import java.time.Instant
import java.time.LocalDateTime

/**
 * The fee records read and written straight in the shared Postgres ([PostgresTestEnvironment]), past the app's own code,
 * so a test sees what is stored rather than what the app reports.
 */
object StoredFeeRecords {
    // pg_stat_activity is a system view, which has no generated class: the statements waiting for the backend {0}
    private const val BLOCKED_BY = "{0} = ANY(pg_blocking_pids(pid))"
    private val PG_STAT_ACTIVITY = DSL.table(DSL.name("pg_stat_activity"))

    /** The charge stored for [transactionId], or `null` if the id has no record. */
    fun of(transactionId: String): FeeResponse? = runBlocking {
        PostgresTestEnvironment.dsl
            .selectFrom(FEE_RECORD)
            .where(FEE_RECORD.TRANSACTION_ID.eq(transactionId))
            .awaitFirstOrNull()
            ?.let { row ->
                FeeResponse(
                    transactionId = transactionId,
                    amount = row.amount,
                    asset = row.asset,
                    type = row.type,
                    fee = row.fee,
                    rate = row.rate,
                    description = row.description,
                )
            }
    }

    /** The request fields stored in the record of [transactionId], or `null` if the id has no record. */
    fun transactionOf(transactionId: String): Transaction? = runBlocking {
        PostgresTestEnvironment.dsl
            .selectFrom(FEE_RECORD)
            .where(FEE_RECORD.TRANSACTION_ID.eq(transactionId))
            .awaitFirstOrNull()
            ?.let { row ->
                Transaction(
                    transactionId = transactionId,
                    amount = row.amount,
                    asset = Asset.fromValue(row.asset),
                    assetType = AssetType.fromValue(row.assetType),
                    type = TransactionType.fromValue(row.type),
                    state = TransactionState.fromValue(row.state),
                    createdAt = row.createdAt,
                )
            }
    }

    /**
     * The `created_at` of the record of [transactionId] as Postgres itself writes it (`2023-08-30 15:42:17.610059`), cast
     * to text in the database, so no Java date binding can shift it; `null` if the id has no record.
     */
    fun createdAtAsStored(transactionId: String): String? = runBlocking {
        PostgresTestEnvironment.dsl
            .select(FEE_RECORD.CREATED_AT.cast(SQLDataType.VARCHAR))
            .from(FEE_RECORD)
            .where(FEE_RECORD.TRANSACTION_ID.eq(transactionId))
            .awaitFirstOrNull()
            ?.value1()
    }

    /** When the record of [transactionId] was charged, or `null` if the id has no record. */
    fun chargedAtOf(transactionId: String): Instant? = runBlocking {
        PostgresTestEnvironment.dsl
            .select(FEE_RECORD.CHARGED_AT)
            .from(FEE_RECORD)
            .where(FEE_RECORD.TRANSACTION_ID.eq(transactionId))
            .awaitFirstOrNull()
            ?.value1()
    }

    /** How many rows `fee_record` holds for [transactionId]: 0 or 1, the primary key allows no more. */
    fun count(transactionId: String): Long = runBlocking {
        PostgresTestEnvironment.dsl
            .selectCount()
            .from(FEE_RECORD)
            .where(FEE_RECORD.TRANSACTION_ID.eq(transactionId))
            .awaitSingle()
            .value1()
            .toLong()
    }

    /**
     * Inserts the record of [transactionId] charged with [response] (the requirements' sample request, a Mobile Top Up
     * of 1000 USD), in a transaction left open on a connection of its own: other connections don't see the record until
     * [OpenCharge.commit], and an insert of the same id waits for it. A charge that wins a race, made deterministic.
     */
    fun chargeInOpenTransaction(transactionId: String, response: FeeResponse): OpenCharge {
        // suppressClose: every statement runs on the one connection, so the transaction stays open between them
        val connection = SingleConnectionFactory(PostgresTestEnvironment.urlWithCredentials, true)
        val dsl = jooq(connection)
        try {
            return runBlocking {
                val backendPid =
                    dsl
                        .select(DSL.function("pg_backend_pid", Int::class.javaObjectType))
                        .awaitSingle()
                        .value1()
                dsl.startTransaction().awaitFirstOrNull()
                val inserted =
                    dsl
                        .insertInto(FEE_RECORD)
                        .set(FEE_RECORD.TRANSACTION_ID, transactionId)
                        .set(FEE_RECORD.AMOUNT, response.amount)
                        .set(FEE_RECORD.ASSET, Asset.USD.value)
                        .set(FEE_RECORD.ASSET_TYPE, AssetType.FIAT.value)
                        .set(FEE_RECORD.TYPE, TransactionType.MOBILE_TOP_UP.value)
                        .set(FEE_RECORD.STATE, TransactionState.SETTLED_PENDING_FEE.value)
                        .set(FEE_RECORD.CREATED_AT, LocalDateTime.of(2023, 8, 30, 15, 42, 17, 610_059_000))
                        .set(FEE_RECORD.FEE, response.fee)
                        .set(FEE_RECORD.RATE, response.rate)
                        .set(FEE_RECORD.DESCRIPTION, response.description)
                        .set(FEE_RECORD.CHARGED_AT, Instant.now())
                        .awaitSingle()
                check(inserted == 1) { "the record of $transactionId was not inserted" }
                OpenCharge(dsl, connection, backendPid)
            }
        } catch (failure: Exception) {
            // an open transaction would hold its row, and its lock on fee_record, until the JVM exits
            connection.destroy()
            throw failure
        }
    }

    /**
     * The open transaction of [chargeInOpenTransaction], on the connection of Postgres backend [backendPid]. Closing the
     * connection without [commit] rolls it back, so a statement waiting for it goes on.
     */
    class OpenCharge(
        private val dsl: DSLContext,
        private val connection: SingleConnectionFactory,
        private val backendPid: Int,
    ) : AutoCloseable {
        /** How many statements of other connections wait for this transaction, such as an insert of the same id. */
        fun blockedStatements(): Long = runBlocking {
            PostgresTestEnvironment.dsl
                .selectCount()
                .from(PG_STAT_ACTIVITY)
                .where(DSL.condition(BLOCKED_BY, DSL.value(backendPid)))
                .awaitSingle()
                .value1()
                .toLong()
        }

        /** Commits the record: every connection sees it from now on. */
        fun commit() {
            runBlocking { dsl.commit().awaitFirstOrNull() }
        }

        override fun close() {
            connection.destroy()
        }
    }
}
