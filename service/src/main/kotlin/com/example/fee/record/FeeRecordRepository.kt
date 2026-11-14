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
import org.jooq.SQLDialect
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL
import org.jooq.impl.DefaultBinding
import org.jooq.impl.SQLDataType
import org.springframework.stereotype.Repository
import java.sql.Types
import java.time.Clock
import java.time.LocalDateTime

/**
 * The fee records in PostgreSQL (the `fee_record` table, `schema.sql`): one row per charged `transaction_id`.
 * `FeeWorkflow` charges a transaction inside a journaled step by inserting its record, and `ChargedFees` reads the
 * charged ones. An interface, so the workflow and the gateway are tested with in-memory fakes.
 */
interface FeeRecordRepository {
    /**
     * Charges the fee of [transaction] by inserting its record: every field of [transaction], the payload the workflow
     * charged, with the fee, rate and description of [response]. Returns the charge the record then holds: [response],
     * or the charge already stored for the `transaction_id`, which is left as it is and stands. So charging again changes
     * nothing and returns the first charge, and a step run again after a crash is safe.
     */
    suspend fun charge(transaction: Transaction, response: FeeResponse): FeeResponse

    /** The charged response of [transactionId], or `null` when it has no record. */
    suspend fun findCharged(transactionId: String): FeeResponse?
}

/**
 * [FeeRecordRepository] in jOOQ over R2DBC: each query is built with jOOQ's DSL on the classes generated from
 * `schema.sql` ([FEE_RECORD]), and runs as a `Publisher` on Boot's pooled [ConnectionFactory] that the caller awaits.
 * Non-blocking, so callers suspend instead of holding a thread. jOOQ's blocking `fetch()` and `execute()` are never
 * used: over R2DBC they would block the thread until the database answers.
 *
 * No explicit transaction: the one write is a single statement, which Postgres runs as one transaction of its own, and
 * its row count tells what happened. READ COMMITTED (Postgres' default) is enough. The primary key serializes
 * concurrent inserts of one id: an insert that meets a row being inserted waits for that transaction, then does nothing
 * (`ON CONFLICT DO NOTHING`), and the next statement sees the committed row.
 *
 * `charged_at` comes from [clock]. A charge run again keeps the first time: the insert does nothing for an existing row.
 */
@Repository
class PostgresFeeRecordRepository(
    connectionFactory: ConnectionFactory,
    private val clock: Clock,
) : FeeRecordRepository {
    private val dsl = DSL.using(connectionFactory, SQLDialect.POSTGRES)

    override suspend fun charge(transaction: Transaction, response: FeeResponse): FeeResponse {
        val transactionId = transaction.transactionId
        require(response.transactionId == transactionId) {
            "The response of transaction '${response.transactionId}' can't charge the fee record of '$transactionId'"
        }
        // The charged payload and its fee; an existing record of the id is left as it is
        val inserted =
            failingAs("Charging", transactionId) {
                dsl
                    .insertInto(FEE_RECORD)
                    .set(FEE_RECORD.TRANSACTION_ID, transactionId)
                    .set(FEE_RECORD.AMOUNT, transaction.amount)
                    .set(FEE_RECORD.ASSET, transaction.asset.value)
                    .set(FEE_RECORD.ASSET_TYPE, transaction.assetType.value)
                    .set(FEE_RECORD.TYPE, transaction.type.value)
                    .set(FEE_RECORD.STATE, transaction.state.value)
                    .set(FEE_RECORD.CREATED_AT, transaction.createdAt)
                    .set(FEE_RECORD.FEE, response.fee)
                    .set(FEE_RECORD.RATE, response.rate)
                    .set(FEE_RECORD.DESCRIPTION, response.description)
                    .set(FEE_RECORD.CHARGED_AT, clock.instant())
                    .onConflict(FEE_RECORD.TRANSACTION_ID)
                    .doNothing()
                    .awaitSingle()
            }
        if (inserted == 1) return response
        // The id was charged already: by this step before a crash, or by an earlier run of the id. That charge stands
        return checkNotNull(findCharged(transactionId)) { "Fee record '$transactionId' is missing although its insert met it" }
    }

    override suspend fun findCharged(transactionId: String): FeeResponse? = failingAs("Reading", transactionId) {
        dsl
            .selectFrom(FEE_RECORD)
            .where(FEE_RECORD.TRANSACTION_ID.eq(transactionId))
            .awaitFirstOrNull()
    }?.let(::response)

    // jOOQ's message of a query that failed before it ran (no connection) is the whole statement with its values, over
    // many lines; Restate stores it as the invocation's failure and the log repeats it on every retry. The operation, the
    // id and the cause say what failed.
    private inline fun <T> failingAs(operation: String, transactionId: String, query: () -> T): T = try {
        query()
    } catch (failure: DataAccessException) {
        val cause = failure.cause ?: failure
        throw DataAccessException("$operation the fee record of '$transactionId' failed: ${cause.message}", cause)
    }

    private fun response(row: FeeRecordRecord) = FeeResponse(
        transactionId = row.transactionId,
        amount = row.amount,
        asset = row.asset,
        type = row.type,
        fee = row.fee,
        rate = row.rate,
        description = row.description,
    )
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
