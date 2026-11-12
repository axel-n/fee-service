package com.example.fee.record

import com.example.fee.contract.FeeResponse
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import org.springframework.stereotype.Component

/**
 * Decides a claim on a `transaction_id` from its stored [FeeRecord]. Pure, so the rules are unit-tested without
 * Restate; `TransactionFeeRecord.claim` applies the result (a [ClaimResult.New] stores [FeeRecord.inProgress]).
 *
 * The workflow key is the `transaction_id`, so Restate already runs one workflow per id while it keeps the run. The
 * record still decides once Restate has dropped a finished run: a charged id answers with its stored response.
 */
@Component
class ClaimDecider {

    fun decide(record: FeeRecord?): ClaimResult {
        if (record == null) return ClaimResult.New
        return when (record.status) {
            FeeRecordStatus.IN_PROGRESS -> ClaimResult.InProgress
            FeeRecordStatus.COMPLETED ->
                ClaimResult.AlreadyCompleted(checkNotNull(record.response) { "COMPLETED fee record without a response" })
        }
    }
}

/**
 * What a claim on `TransactionFeeRecord` decided, as [ClaimDecider] computes it. Returned by the `claim` handler and
 * journaled in the workflow, so it is durable: the type ids are the explicit `@JsonSubTypes` names, never class
 * names, and renaming one breaks in-flight workflows. Compare the field-less results with `==`, `is` or `when`.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@type")
@JsonSubTypes(
    JsonSubTypes.Type(value = ClaimResult.New::class, name = "NEW"),
    JsonSubTypes.Type(value = ClaimResult.AlreadyCompleted::class, name = "ALREADY_COMPLETED"),
    JsonSubTypes.Type(value = ClaimResult.InProgress::class, name = "IN_PROGRESS"),
)
sealed interface ClaimResult {

    /** The id was never claimed: this run owns it now and goes on to charge it. */
    data object New : ClaimResult

    /** The id was already charged: return the stored [response] without charging again. */
    data class AlreadyCompleted(@JsonProperty("response") val response: FeeResponse) : ClaimResult

    /** An earlier run holds the claim and never finished. */
    data object InProgress : ClaimResult
}
