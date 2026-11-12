package com.example.fee.record

import com.example.fee.contract.FeeResponse
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * The permanent fee record of one `transaction_id`, the state of `TransactionFeeRecord`. [response] is set exactly when
 * [FeeRecordStatus.COMPLETED]; the constructor rejects any other combination, also when Restate reads the state back.
 *
 * Stored state: renaming or removing a field breaks existing records; adding a nullable field is safe.
 */
data class FeeRecord(
    @JsonProperty("status") val status: FeeRecordStatus,
    @JsonProperty("response") val response: FeeResponse? = null,
) {

    init {
        require((status == FeeRecordStatus.COMPLETED) == (response != null)) {
            "A fee record has a response exactly when it is COMPLETED, but status is $status and response is $response"
        }
    }

    /** The same claim, now charged and recorded with [response]. */
    fun complete(response: FeeResponse): FeeRecord = FeeRecord(FeeRecordStatus.COMPLETED, response)

    companion object {
        /** A new claim, not charged yet. */
        fun inProgress(): FeeRecord = FeeRecord(FeeRecordStatus.IN_PROGRESS)
    }
}

/**
 * Where the fee for a `transaction_id` stands in its [FeeRecord]. Stored by name in `TransactionFeeRecord` state, so
 * renaming or removing a value is a breaking change for existing records.
 */
enum class FeeRecordStatus {
    /** Claimed by a run that has not finished: another run of the same id gets a conflict. */
    IN_PROGRESS,

    /** Charged and recorded: a later run of the same id gets the stored response. */
    COMPLETED,
}
