package com.example.fee.restate

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
