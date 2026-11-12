@file:Suppress("DEPRECATION") // the ctx-based codegen API of SDK 2.9.3, kept on purpose (D29)

package com.example.fee.restate

import com.example.fee.contract.FeeResponse
import dev.restate.sdk.ObjectContext
import dev.restate.sdk.SharedObjectContext
import dev.restate.sdk.annotation.Handler
import dev.restate.sdk.annotation.Shared
import dev.restate.sdk.annotation.VirtualObject
import dev.restate.sdk.common.StateKey
import org.springframework.stereotype.Component

/**
 * The permanent fee record of one `transaction_id` (the object key): one [FeeRecord] in Restate state. Restate runs the
 * exclusive handlers of one key one at a time, so two workflows claiming the same id can't both get [ClaimResult.New].
 * The claim rules live in [ClaimDecider]; this class only reads and writes the state.
 */
// Breaking changes for stored records and in-flight workflows: the handler names and signatures and the state key name.
@Component
@VirtualObject
class TransactionFeeRecord(private val claimDecider: ClaimDecider) {

    /** Claims the id for the payload with [payloadHash]; on [ClaimResult.New] the record becomes `IN_PROGRESS`. */
    @Handler
    fun claim(ctx: ObjectContext, payloadHash: String): ClaimResult {
        val result = claimDecider.decide(ctx.get(RECORD).orElse(null), payloadHash)
        if (result is ClaimResult.New) ctx.set(RECORD, FeeRecord.inProgress(payloadHash))
        return result
    }

    /** Records the charged [response]: the record becomes `COMPLETED`, keeping the claiming payload's hash. */
    @Handler
    fun complete(ctx: ObjectContext, response: FeeResponse) {
        ctx.set(RECORD, inProgress(ctx, "complete").complete(response))
    }

    /** Records that the charge failed for [reason]: the record becomes `FAILED`, which frees the id for a new claim. */
    @Handler
    fun fail(ctx: ObjectContext, reason: String) {
        ctx.set(RECORD, inProgress(ctx, "fail").fail(reason))
    }

    /** The current record, or `null` if the id was never claimed. Shared, so it answers while a claim is running. */
    @Shared
    fun get(ctx: SharedObjectContext): FeeRecord? = ctx.get(RECORD).orElse(null)

    // Only the workflow holding the claim calls complete or fail, so anything but IN_PROGRESS is a bug: a plain exception,
    // retried until Restate pauses the invocation, which can be resumed once the bug is fixed (like D39 and D42).
    private fun inProgress(ctx: ObjectContext, handler: String): FeeRecord {
        val record = ctx.get(RECORD).orElse(null)
        checkNotNull(record) { "$handler on transaction '${ctx.key()}' without a claim" }
        check(record.status == FeeRecordStatus.IN_PROGRESS) {
            "$handler on transaction '${ctx.key()}' whose record is ${record.status}, not IN_PROGRESS"
        }
        return record
    }

    companion object {
        /** The state key of the record. Stored state: renaming it orphans every existing record. */
        internal val RECORD: StateKey<FeeRecord> = StateKey.of("record", FeeRecord::class.java)
    }
}
