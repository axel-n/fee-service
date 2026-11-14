package com.example.fee.record

import com.example.fee.contract.FeeResponse
import com.example.fee.domain.Transaction
import com.example.fee.record.jooq.tables.records.FeeRecordRecord
import com.example.fee.record.jooq.tables.references.FEE_RECORD
import io.r2dbc.spi.ConnectionFactory
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import org.jooq.Binding
import org.jooq.BindingGetResultSetContext
import org.jooq.BindingSetStatementContext
import org.jooq.Field
import org.jooq.SQLDialect
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL
import org.jooq.impl.DSL.coalesce
import org.jooq.impl.DSL.value
import org.jooq.impl.DefaultBinding
import org.jooq.impl.SQLDataType
import org.springframework.stereotype.Repository
import java.sql.Types
import java.time.Clock
import java.time.LocalDateTime

/**
 * The fee records in PostgreSQL (the `fee_record` table, `schema.sql`): one row per `transaction_id`. `FeeWorkflow`
 * claims and completes them inside journaled steps, `ChargedFees` reads the charged ones. An interface, so the workflow
 * and the gateway are tested with in-memory fakes.
 *
 * Each write is one statement, atomic on its own, and its row count decides: the insert of an id a plain read didn't
 * find ([claim]), and the update of the row a run claimed ([complete]). Both run after validation, which comes first in
 * the workflow.
 */
interface FeeRecordRepository {
    /**
     * Claims the `transaction_id` of [transaction] for the run holding [claimToken] and returns its record as it then
     * stands: a new `IN_PROGRESS` record with [claimToken] and every field of [transaction], or the record that was
     * already there, unchanged (`ClaimDecider` decides from it). Calling it again with the same token returns the same
     * record, so a step run again after a crash is safe.
     */
    suspend fun claim(transaction: Transaction, claimToken: String): FeeRecord

    /**
     * Charges the fee by recording the fee, rate and description of [response] on the record of [transactionId]: it
     * becomes `COMPLETED`. Its amount, asset and type are the transaction's, stored by [claim]. Only the run holding
     * [claimToken] can; anything else throws and changes nothing. Recording the same response again changes nothing
     * either.
     */
    suspend fun complete(transactionId: String, claimToken: String, response: FeeResponse)

    /** The charged response of [transactionId], or `null` when it isn't charged (no record, or `IN_PROGRESS`). */
    suspend fun findCharged(transactionId: String): FeeResponse?
}

/**
 * [FeeRecordRepository] in jOOQ over R2DBC: each query is built with jOOQ's DSL on the classes generated from
 * `schema.sql` ([FEE_RECORD]), and runs as a `Publisher` on Boot's pooled [ConnectionFactory] that the caller awaits.
 * Non-blocking, so callers suspend instead of holding a thread. jOOQ's blocking `fetch()` and `execute()` are never
 * used: over R2DBC they would block the thread until the database answers.
 *
 * No explicit transaction: each write is a single statement, which Postgres runs as one transaction of its own, and
 * its row count tells what happened. READ COMMITTED (Postgres' default) is enough. The primary key serializes
 * concurrent inserts of one id: an insert that meets a row being inserted waits for that transaction, then does nothing
 * (`ON CONFLICT DO NOTHING`), and the next statement sees the committed row. The token check of [complete] sits in its
 * `UPDATE` statement itself.
 *
 * `claimed_at` and `charged_at` come from [clock]. A write run again after a crash keeps the first time: the insert does
 * nothing for an existing row, and the update keeps a `charged_at` that is already set.
 */
@Repository
class PostgresFeeRecordRepository(
    connectionFactory: ConnectionFactory,
    private val clock: Clock,
) : FeeRecordRepository {
    private val dsl = DSL.using(connectionFactory, SQLDialect.POSTGRES)

    override suspend fun claim(transaction: Transaction, claimToken: String): FeeRecord {
        val transactionId = transaction.transactionId
        find(transactionId)?.let { return it }
        // A new IN_PROGRESS record with every field of the request; an existing record of the id is left as it is
        val inserted =
            failingAs("Claiming", transactionId) {
                dsl
                    .insertInto(FEE_RECORD)
                    .set(FEE_RECORD.TRANSACTION_ID, transactionId)
                    .set(FEE_RECORD.AMOUNT, transaction.amount)
                    .set(FEE_RECORD.ASSET, transaction.asset.value)
                    .set(FEE_RECORD.ASSET_TYPE, transaction.assetType.value)
                    .set(FEE_RECORD.TYPE, transaction.type.value)
                    .set(FEE_RECORD.STATE, transaction.state.value)
                    .set(FEE_RECORD.CREATED_AT, transaction.createdAt)
                    .set(FEE_RECORD.STATUS, FeeRecordStatus.IN_PROGRESS.name)
                    .set(FEE_RECORD.CLAIM_TOKEN, claimToken)
                    .set(FEE_RECORD.CLAIMED_AT, clock.instant())
                    .onConflict(FEE_RECORD.TRANSACTION_ID)
                    .doNothing()
                    .awaitSingle()
            }
        if (inserted == 1) return FeeRecord.inProgress(claimToken)
        // A concurrent claim committed its row between the read and the insert: decide from that row
        return checkNotNull(find(transactionId)) { "Fee record '$transactionId' is missing although its insert met it" }
    }

    override suspend fun complete(transactionId: String, claimToken: String, response: FeeResponse) {
        require(response.transactionId == transactionId) {
            "The response of transaction '${response.transactionId}' can't complete the fee record of '$transactionId'"
        }
        // The charge, by the claim token's holder only; a charged_at already set is kept
        val updated =
            failingAs("Completing", transactionId) {
                dsl
                    .update(FEE_RECORD)
                    .set(FEE_RECORD.STATUS, FeeRecordStatus.COMPLETED.name)
                    .set(FEE_RECORD.FEE, response.fee)
                    .set(FEE_RECORD.RATE, response.rate)
                    .set(FEE_RECORD.DESCRIPTION, response.description)
                    .set(FEE_RECORD.CHARGED_AT, coalesce(FEE_RECORD.CHARGED_AT, value(clock.instant(), FEE_RECORD.CHARGED_AT)))
                    .where(FEE_RECORD.TRANSACTION_ID.eq(transactionId))
                    .and(FEE_RECORD.CLAIM_TOKEN.eq(claimToken))
                    .awaitSingle()
            }
        // Only the run that claimed the id completes it, so anything else is a bug: nothing was written, and Restate
        // retries until it pauses the invocation, which can be resumed once the bug is fixed.
        check(updated == 1) { "Fee record '$transactionId' is not claimed by token $claimToken, so it can't be completed" }
    }

    override suspend fun findCharged(transactionId: String): FeeResponse? = find(transactionId)?.response

    private suspend fun find(transactionId: String): FeeRecord? =
        failingAs("Reading", transactionId) {
            dsl
                .selectFrom(FEE_RECORD)
                .where(FEE_RECORD.TRANSACTION_ID.eq(transactionId))
                .awaitFirstOrNull()
        }?.let(::record)

    // jOOQ's message of a query that failed before it ran (no connection) is the whole statement with its values, over
    // many lines; Restate stores it as the invocation's failure and the log repeats it on every retry. The operation, the
    // id and the cause say what failed.
    private inline fun <T> failingAs(operation: String, transactionId: String, query: () -> T): T =
        try {
            query()
        } catch (failure: DataAccessException) {
            val cause = failure.cause ?: failure
            throw DataAccessException("$operation the fee record of '$transactionId' failed: ${cause.message}", cause)
        }

    // The claim, and the charged response once COMPLETED
    private fun record(row: FeeRecordRecord): FeeRecord {
        val status = FeeRecordStatus.valueOf(row.status)
        if (status != FeeRecordStatus.COMPLETED) return FeeRecord(status, row.claimToken)
        val response =
            FeeResponse(
                transactionId = row.transactionId,
                amount = row.amount,
                asset = row.asset,
                type = row.type,
                fee = required(row.fee, FEE_RECORD.FEE),
                rate = required(row.rate, FEE_RECORD.RATE),
                description = required(row.description, FEE_RECORD.DESCRIPTION),
            )
        return FeeRecord(status, row.claimToken, response)
    }

    private fun <T : Any> required(value: T?, column: Field<*>): T = checkNotNull(value) { "fee_record.${column.name} is null" }
}

/**
 * The jOOQ binding of a `TIMESTAMP` without a zone (`created_at`): the [LocalDateTime] goes to R2DBC and comes back as
 * it is. jOOQ's own binding converts it through `java.sql.Timestamp` in the JVM's default zone, so in a zone with
 * daylight saving a time in the skipped hour (02:30 on 2024-03-31 in Berlin) would be stored and read an hour late.
 * Everything else (rendering, the other contexts) is jOOQ's. Named by the jOOQ code generation in `service/pom.xml`.
 */
class ZoneFreeLocalDateTimeBinding : Binding<LocalDateTime, LocalDateTime> by DefaultBinding.binding(SQLDataType.LOCALDATETIME) {
    override fun set(ctx: BindingSetStatementContext<LocalDateTime>) {
        val value = ctx.convert(converter()).value()
        if (value == null) {
            ctx.statement().setNull(ctx.index(), Types.TIMESTAMP)
        } else {
            ctx.statement().setObject(ctx.index(), value)
        }
    }

    override fun get(ctx: BindingGetResultSetContext<LocalDateTime>) {
        ctx.convert(converter()).value(ctx.resultSet().getObject(ctx.index(), LocalDateTime::class.java))
    }
}
