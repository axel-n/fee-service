package com.example.fee.restate

import org.springframework.stereotype.Component

/**
 * Decides a claim on a `transaction_id` from its stored [FeeRecord] and the claiming payload's hash. Pure, so the rules
 * are unit-tested without Restate; `TransactionFeeRecord.claim` applies the result (a [ClaimResult.New] stores
 * [FeeRecord.inProgress]).
 */
@Component
class ClaimDecider {

    fun decide(record: FeeRecord?, payloadHash: String): ClaimResult {
        if (record == null) return ClaimResult.New
        return when (record.status) {
            FeeRecordStatus.IN_PROGRESS -> ClaimResult.InProgress
            FeeRecordStatus.COMPLETED -> completed(record, payloadHash)
            // a declined charge doesn't use up the id, whichever payload it was for
            FeeRecordStatus.FAILED -> ClaimResult.New
        }
    }

    private fun completed(record: FeeRecord, payloadHash: String): ClaimResult {
        if (record.payloadHash != payloadHash) return ClaimResult.Conflict
        val response = checkNotNull(record.response) { "COMPLETED fee record without a response" }
        return ClaimResult.AlreadyCompleted(response)
    }
}
