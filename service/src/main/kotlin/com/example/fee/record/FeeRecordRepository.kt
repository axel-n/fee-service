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
 * The fee records in PostgreSQL (the `fee_record` table, `schema.sql`): one row per `transaction_id`. `FeeService`
 * starts them and `ChargedFees` reads the charged ones; `FeeWorkflow` charges them inside a journaled step. An interface,
 * so the workflow and the gateway are tested with in-memory fakes.
 *
 * Each write is one statement, atomic on its own, and its row count decides: the insert that starts a record ([start]),
 * and the update that charges it ([charge]).
 */
interface FeeRecordRepository {
    /**
     * Starts the record of [transaction] for the workflow [workflowId] and returns the record as it then stands: a new
     * `PENDING` record with [workflowId] and every field of [transaction], or the record of the `transaction_id` that was
     * already there, unchanged, with the `workflow_id` of the request that started it. So every request of one id gets
     * one `workflow_id`, whatever runs at the same time.
     */
    suspend fun start(transaction: Transaction, workflowId: String): FeeRecord

    /**
     * Charges the fee of [transaction] by recording the fee, rate and description of [response] on its record: it
     * becomes `CHARGED`. The request fields are written again from [transaction], the payload the workflow charged: a
     * request of the same id sent at the same time may have started the record with another payload. Only the workflow
     * [workflowId] the record was started for can; anything else throws and changes nothing. Charging the same response
     * again changes nothing either, so a step run again after a crash is safe.
     */
    suspend fun charge(transaction: Transaction, workflowId: String, response: FeeResponse)

    /** The charged response of [transactionId], or `null` when it isn't charged (no record, or `PENDING`). */
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
 * (`ON CONFLICT DO NOTHING`), and the next statement sees the committed row. The `workflow_id` check of [charge] sits in
 * its `UPDATE` statement itself.
 *
 * `started_at` and `charged_at` come from [clock]. A write run again keeps the first time: the insert does nothing for
 * an existing row, and the update keeps a `charged_at` that is already set.
 */
@Repository
class PostgresFeeRecordRepository(
    connectionFactory: ConnectionFactory,
    private val clock: Clock,
) : FeeRecordRepository {
    private val dsl = DSL.using(connectionFactory, SQLDialect.POSTGRES)

    override suspend fun start(transaction: Transaction, workflowId: String): FeeRecord {
        val transactionId = transaction.transactionId
        // A new PENDING record with every field of the request; an existing record of the id is left as it is
        val inserted =
            failingAs("Starting", transactionId) {
                dsl
                    .insertInto(FEE_RECORD)
                    .set(FEE_RECORD.TRANSACTION_ID, transactionId)
                    .set(FEE_RECORD.AMOUNT, transaction.amount)
                    .set(FEE_RECORD.ASSET, transaction.asset.value)
                    .set(FEE_RECORD.ASSET_TYPE, transaction.assetType.value)
                    .set(FEE_RECORD.TYPE, transaction.type.value)
                    .set(FEE_RECORD.STATE, transaction.state.value)
                    .set(FEE_RECORD.CREATED_AT, transaction.createdAt)
                    .set(FEE_RECORD.STATUS, FeeRecordStatus.PENDING.name)
                    .set(FEE_RECORD.WORKFLOW_ID, workflowId)
                    .set(FEE_RECORD.STARTED_AT, clock.instant())
                    .onConflict(FEE_RECORD.TRANSACTION_ID)
                    .doNothing()
                    .awaitSingle()
            }
        if (inserted == 1) return FeeRecord.pending(workflowId)
        // The id already has a record, from an earlier request or one running at the same time: it decides
        return checkNotNull(find(transactionId)) { "Fee record '$transactionId' is missing although its insert met it" }
    }

    override suspend fun charge(transaction: Transaction, workflowId: String, response: FeeResponse) {
        val transactionId = transaction.transactionId
        require(response.transactionId == transactionId) {
            "The response of transaction '${response.transactionId}' can't charge the fee record of '$transactionId'"
        }
        // The charged payload and its fee, by the record's own workflow only; a charged_at already set is kept
        val updated =
            failingAs("Charging", transactionId) {
                dsl
                    .update(FEE_RECORD)
                    .set(FEE_RECORD.AMOUNT, transaction.amount)
                    .set(FEE_RECORD.ASSET, transaction.asset.value)
                    .set(FEE_RECORD.ASSET_TYPE, transaction.assetType.value)
                    .set(FEE_RECORD.TYPE, transaction.type.value)
                    .set(FEE_RECORD.STATE, transaction.state.value)
                    .set(FEE_RECORD.CREATED_AT, transaction.createdAt)
                    .set(FEE_RECORD.STATUS, FeeRecordStatus.CHARGED.name)
                    .set(FEE_RECORD.FEE, response.fee)
                    .set(FEE_RECORD.RATE, response.rate)
                    .set(FEE_RECORD.DESCRIPTION, response.description)
                    .set(FEE_RECORD.CHARGED_AT, coalesce(FEE_RECORD.CHARGED_AT, value(clock.instant(), FEE_RECORD.CHARGED_AT)))
                    .where(FEE_RECORD.TRANSACTION_ID.eq(transactionId))
                    .and(FEE_RECORD.WORKFLOW_ID.eq(workflowId))
                    .awaitSingle()
            }
        // FeeService starts the record before it submits the workflow, so anything else is a bug: nothing was written,
        // and Restate retries until it pauses the invocation, which can be resumed once the bug is fixed.
        check(updated == 1) { "Fee record '$transactionId' was not started for workflow $workflowId, so it can't be charged" }
    }

    override suspend fun findCharged(transactionId: String): FeeResponse? = find(transactionId)?.response

    private suspend fun find(transactionId: String): FeeRecord? = failingAs("Reading", transactionId) {
        dsl
            .selectFrom(FEE_RECORD)
            .where(FEE_RECORD.TRANSACTION_ID.eq(transactionId))
            .awaitFirstOrNull()
    }?.let(::record)

    // jOOQ's message of a query that failed before it ran (no connection) is the whole statement with its values, over
    // many lines; Restate stores it as the invocation's failure and the log repeats it on every retry. The operation, the
    // id and the cause say what failed.
    private inline fun <T> failingAs(operation: String, transactionId: String, query: () -> T): T = try {
        query()
    } catch (failure: DataAccessException) {
        val cause = failure.cause ?: failure
        throw DataAccessException("$operation the fee record of '$transactionId' failed: ${cause.message}", cause)
    }

    // The workflow, and the charged response once CHARGED
    private fun record(row: FeeRecordRecord): FeeRecord {
        val status = FeeRecordStatus.valueOf(row.status)
        if (status != FeeRecordStatus.CHARGED) return FeeRecord(status, row.workflowId)
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
        return FeeRecord(status, row.workflowId, response)
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
