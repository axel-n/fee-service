package com.example.fee.record

import com.example.fee.contract.FeeResponse

/**
 * The fee record of one `transaction_id`: its row in the `fee_record` table. [claimToken] names the workflow run that
 * claimed it, the only one that may complete it. [response] is set exactly when [FeeRecordStatus.COMPLETED]; the
 * constructor rejects any other combination, and so does the table.
 */
data class FeeRecord(
    val status: FeeRecordStatus,
    val claimToken: String,
    val response: FeeResponse? = null,
) {
    init {
        require((status == FeeRecordStatus.COMPLETED) == (response != null)) {
            "A fee record has a response exactly when it is COMPLETED, but status is $status and response is $response"
        }
    }

    /** The same claim, now charged and recorded with [response]. */
    fun complete(response: FeeResponse): FeeRecord = FeeRecord(FeeRecordStatus.COMPLETED, claimToken, response)

    companion object {
        /** A new claim by the run holding [claimToken], not charged yet. */
        fun inProgress(claimToken: String): FeeRecord = FeeRecord(FeeRecordStatus.IN_PROGRESS, claimToken)
    }
}

/**
 * Where the fee for a `transaction_id` stands in its [FeeRecord]. Stored by name in the `status` column, so renaming or
 * removing a value is a breaking change for existing records.
 */
enum class FeeRecordStatus {
    /** Claimed by a run that has not finished: another run of the same id gets a conflict. */
    IN_PROGRESS,

    /** Charged and recorded: a later run of the same id gets the stored response. */
    COMPLETED,
}
