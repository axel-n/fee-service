package com.example.fee.record

import com.example.fee.contract.FeeResponse

/**
 * The fee record of one `transaction_id`: its row in the `fee_record` table. [workflowId] is the key of the
 * `FeeWorkflow` that charges it, the only one that may. [response] is set exactly when [FeeRecordStatus.CHARGED]; the
 * constructor rejects any other combination, and so does the table.
 */
data class FeeRecord(
    val status: FeeRecordStatus,
    val workflowId: String,
    val response: FeeResponse? = null,
) {
    init {
        require((status == FeeRecordStatus.CHARGED) == (response != null)) {
            "A fee record has a response exactly when it is CHARGED, but status is $status and response is $response"
        }
    }

    /** The same record, now charged with [response]. */
    fun charge(response: FeeResponse): FeeRecord = FeeRecord(FeeRecordStatus.CHARGED, workflowId, response)

    companion object {
        /** A new record for the workflow [workflowId], not charged yet. */
        fun pending(workflowId: String): FeeRecord = FeeRecord(FeeRecordStatus.PENDING, workflowId)
    }
}

/**
 * Where the fee for a `transaction_id` stands in its [FeeRecord]. Stored by name in the `status` column, so renaming or
 * removing a value is a breaking change for existing records.
 */
enum class FeeRecordStatus {
    /**
     * Not charged yet: its workflow is running, or was never submitted (the app died after the insert). A later request of
     * the id submits that same workflow again, or attaches to it.
     */
    PENDING,

    /** Charged: a later request of the id gets the stored response. */
    CHARGED,
}
