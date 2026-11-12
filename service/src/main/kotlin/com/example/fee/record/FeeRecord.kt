package com.example.fee.record

import com.example.fee.contract.FeeResponse
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * The permanent fee record of one `transaction_id`, the state of `TransactionFeeRecord`. [payloadHash] is the hash of the
 * payload that claimed it, so a later payload can be told apart (D8). [response] is set once [FeeRecordStatus.COMPLETED],
 * [failureReason] once [FeeRecordStatus.FAILED]; the constructor rejects any other combination, also when Restate reads
 * the state back.
 *
 * Stored state (D21, D35): renaming or removing a field breaks existing records; adding a nullable field is safe.
 */
data class FeeRecord(
    @JsonProperty("status") val status: FeeRecordStatus,
    @JsonProperty("payload_hash") val payloadHash: String,
    @JsonProperty("response") val response: FeeResponse? = null,
    @JsonProperty("failure_reason") val failureReason: String? = null,
) {

    init {
        require((status == FeeRecordStatus.COMPLETED) == (response != null)) {
            "A fee record has a response exactly when it is COMPLETED, but status is $status and response is $response"
        }
        require((status == FeeRecordStatus.FAILED) == (failureReason != null)) {
            "A fee record has a failure reason exactly when it is FAILED, but status is $status and reason is $failureReason"
        }
    }

    /** The same claim, now charged and recorded with [response]. */
    fun complete(response: FeeResponse): FeeRecord = FeeRecord(FeeRecordStatus.COMPLETED, payloadHash, response = response)

    /** The same claim, now failed for [reason]. */
    fun fail(reason: String): FeeRecord = FeeRecord(FeeRecordStatus.FAILED, payloadHash, failureReason = reason)

    companion object {
        /** A new claim by the payload with [payloadHash]. */
        fun inProgress(payloadHash: String): FeeRecord = FeeRecord(FeeRecordStatus.IN_PROGRESS, payloadHash)
    }
}

/**
 * Where the fee for a `transaction_id` stands in its [FeeRecord]. Stored by name in `TransactionFeeRecord` state, so
 * renaming or removing a value is a breaking change for existing records (D21).
 */
enum class FeeRecordStatus {
    /** Claimed by a workflow that has not finished: another payload for the same id gets a conflict. */
    IN_PROGRESS,

    /** Charged and recorded: the same payload gets the stored response, another payload a conflict. */
    COMPLETED,

    /** The charge was declined: the id is free again, so a new claim may start over. */
    FAILED,
}
