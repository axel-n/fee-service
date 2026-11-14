package com.example.fee.record

import com.example.fee.contract.FeeResponse
import com.example.fee.domain.Transaction
import io.r2dbc.spi.ConnectionFactory
import io.r2dbc.spi.Readable
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitOneOrNull
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.Clock

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
 * [FeeRecordRepository] over R2DBC: non-blocking, so callers suspend instead of holding a thread.
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
    private val client = DatabaseClient.create(connectionFactory)

    override suspend fun claim(transaction: Transaction, claimToken: String): FeeRecord {
        val transactionId = transaction.transactionId
        find(transactionId)?.let { return it }
        val inserted =
            client
                .sql(INSERT_CLAIM)
                .bind("id", transactionId)
                .bind("amount", transaction.amount)
                .bind("asset", transaction.asset.value)
                .bind("assetType", transaction.assetType.value)
                .bind("type", transaction.type.value)
                .bind("state", transaction.state.value)
                .bind("createdAt", transaction.createdAt)
                .bind("status", FeeRecordStatus.IN_PROGRESS.name)
                .bind("token", claimToken)
                .bind("claimedAt", clock.instant())
                .fetch()
                .awaitRowsUpdated()
        if (inserted == 1L) return FeeRecord.inProgress(claimToken)
        // A concurrent claim committed its row between the read and the insert: decide from that row
        return checkNotNull(find(transactionId)) { "Fee record '$transactionId' is missing although its insert met it" }
    }

    override suspend fun complete(transactionId: String, claimToken: String, response: FeeResponse) {
        require(response.transactionId == transactionId) {
            "The response of transaction '${response.transactionId}' can't complete the fee record of '$transactionId'"
        }
        val updated =
            client
                .sql(UPDATE_COMPLETE)
                .bind("status", FeeRecordStatus.COMPLETED.name)
                .bind("fee", response.fee)
                .bind("rate", response.rate)
                .bind("description", response.description)
                .bind("chargedAt", clock.instant())
                .bind("id", transactionId)
                .bind("token", claimToken)
                .fetch()
                .awaitRowsUpdated()
        // Only the run that claimed the id completes it, so anything else is a bug: nothing was written, and Restate
        // retries until it pauses the invocation, which can be resumed once the bug is fixed.
        check(updated == 1L) { "Fee record '$transactionId' is not claimed by token $claimToken, so it can't be completed" }
    }

    override suspend fun findCharged(transactionId: String): FeeResponse? = find(transactionId)?.response

    private suspend fun find(transactionId: String): FeeRecord? =
        client
            .sql(SELECT_BY_ID)
            .bind("id", transactionId)
            .map(::record)
            .awaitOneOrNull()

    private fun record(row: Readable): FeeRecord {
        val transactionId = row.required("transaction_id", String::class.java)
        val status = FeeRecordStatus.valueOf(row.required("status", String::class.java))
        val claimToken = row.required("claim_token", String::class.java)
        if (status != FeeRecordStatus.COMPLETED) return FeeRecord(status, claimToken)
        val response =
            FeeResponse(
                transactionId = transactionId,
                amount = row.required("amount", BigDecimal::class.java),
                asset = row.required("asset", String::class.java),
                type = row.required("type", String::class.java),
                fee = row.required("fee", BigDecimal::class.java),
                rate = row.required("rate", BigDecimal::class.java),
                description = row.required("description", String::class.java),
            )
        return FeeRecord(status, claimToken, response)
    }

    private fun <T : Any> Readable.required(column: String, type: Class<T>): T =
        checkNotNull(get(column, type)) { "fee_record.$column is null" }

    private companion object {
        /** A new `IN_PROGRESS` record with every field of the request; an existing record of the id is left as it is. */
        const val INSERT_CLAIM = """
            INSERT INTO fee_record
                (transaction_id, amount, asset, asset_type, type, state, created_at, status, claim_token, claimed_at)
            VALUES
                (:id, :amount, :asset, :assetType, :type, :state, :createdAt, :status, :token, :claimedAt)
            ON CONFLICT (transaction_id) DO NOTHING
        """

        /** The charge, by the claim token's holder only; a `charged_at` already set is kept. */
        const val UPDATE_COMPLETE = """
            UPDATE fee_record
            SET status = :status, fee = :fee, rate = :rate, description = :description,
                charged_at = COALESCE(charged_at, :chargedAt)
            WHERE transaction_id = :id AND claim_token = :token
        """

        /** What the app reads back: the claim, and the charged response once `COMPLETED`. */
        const val SELECT_BY_ID = """
            SELECT transaction_id, status, claim_token, amount, asset, type, fee, rate, description
            FROM fee_record
            WHERE transaction_id = :id
        """
    }
}
