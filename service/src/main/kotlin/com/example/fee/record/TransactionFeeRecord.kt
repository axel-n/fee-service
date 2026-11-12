package com.example.fee.record

import com.example.fee.contract.FeeResponse
import com.example.fee.restate.serde.KotlinJacksonSerdeFactory
import dev.restate.sdk.annotation.CustomSerdeFactory
import dev.restate.sdk.annotation.Handler
import dev.restate.sdk.annotation.Shared
import dev.restate.sdk.annotation.VirtualObject
import dev.restate.sdk.common.StateKey
import dev.restate.sdk.kotlin.objectKey
import dev.restate.sdk.kotlin.state
import dev.restate.sdk.kotlin.stateKey
import org.springframework.stereotype.Component

/**
 * The permanent fee record of one `transaction_id` (the object key): one [FeeRecord] in Restate state. Restate runs the
 * exclusive handlers of one key one at a time, so two workflows claiming the same id can't both get [ClaimResult.New].
 * The claim rules live in [ClaimDecider]; this class only reads and writes the state, through [FeeRecordState].
 */
// Breaking changes for stored records and in-flight workflows: the class and handler names and signatures, and the
// state key name.
@Component
@VirtualObject
@CustomSerdeFactory(KotlinJacksonSerdeFactory::class)
class TransactionFeeRecord(
    private val claimDecider: ClaimDecider,
    private val state: FeeRecordState,
) {

    /** Claims the id for the calling run; on [ClaimResult.New] the record becomes `IN_PROGRESS`. */
    @Handler
    suspend fun claim(): ClaimResult {
        val result = claimDecider.decide(state.get())
        if (result is ClaimResult.New) state.set(FeeRecord.inProgress())
        return result
    }

    /** Records the charged [response]: the record becomes `COMPLETED`. */
    @Handler
    suspend fun complete(response: FeeResponse) {
        state.set(inProgress("complete").complete(response))
    }

    /** The current record, or `null` if the id was never claimed. Shared, so it answers while a claim is running. */
    @Shared
    suspend fun get(): FeeRecord? = state.get()

    // Only the workflow holding the claim calls complete, so anything but IN_PROGRESS is a bug: a plain exception,
    // retried until Restate pauses the invocation, which can be resumed once the bug is fixed.
    private suspend fun inProgress(handler: String): FeeRecord {
        val record = state.get()
        checkNotNull(record) { "$handler on transaction '${state.key()}' without a claim" }
        check(record.status == FeeRecordStatus.IN_PROGRESS) {
            "$handler on transaction '${state.key()}' whose record is ${record.status}, not IN_PROGRESS"
        }
        return record
    }
}

/**
 * The state of [TransactionFeeRecord]: the object key and its one [FeeRecord]. The handlers reach Restate only through
 * this, so tests replace it with an in-memory fake.
 */
interface FeeRecordState {

    /** The object key: the `transaction_id`. */
    suspend fun key(): String

    /** The stored record, or `null` if the id was never claimed. */
    suspend fun get(): FeeRecord?

    /** Stores [record], replacing the one before. */
    suspend fun set(record: FeeRecord)
}

/**
 * The [FeeRecordState] of the Restate invocation the calling coroutine runs in. Stateless: the Kotlin SDK finds the
 * invocation in the coroutine context, so one bean serves every invocation.
 */
@Component
class RestateFeeRecordState : FeeRecordState {

    override suspend fun key(): String = objectKey()

    override suspend fun get(): FeeRecord? = state().get(RECORD)

    override suspend fun set(record: FeeRecord) = state().set(RECORD, record)

    companion object {
        /** The state key of the record. Stored state: renaming it orphans every existing record. */
        internal val RECORD: StateKey<FeeRecord> = stateKey("record")
    }
}
