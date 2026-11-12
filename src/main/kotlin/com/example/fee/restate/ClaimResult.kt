package com.example.fee.restate

import com.example.fee.contract.FeeResponse
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

/**
 * What a claim on `TransactionFeeRecord` decided, as [ClaimDecider] computes it. Returned by the `claim` handler and
 * journaled in the workflow, so it is durable (D35): the type ids are the explicit `@JsonSubTypes` names, never class
 * names, and renaming one breaks in-flight workflows. Compare the field-less results with `==`, `is` or `when`.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@type")
@JsonSubTypes(
    JsonSubTypes.Type(value = ClaimResult.New::class, name = "NEW"),
    JsonSubTypes.Type(value = ClaimResult.AlreadyCompleted::class, name = "ALREADY_COMPLETED"),
    JsonSubTypes.Type(value = ClaimResult.InProgress::class, name = "IN_PROGRESS"),
    JsonSubTypes.Type(value = ClaimResult.Conflict::class, name = "CONFLICT"),
)
sealed interface ClaimResult {

    /** The id is free (never claimed, or its charge failed): this payload owns it now and goes on to charge. */
    data object New : ClaimResult

    /** This payload was already charged: return the stored [response] without charging again. */
    data class AlreadyCompleted(@JsonProperty("response") val response: FeeResponse) : ClaimResult

    /** Another workflow holds the claim and hasn't finished. */
    data object InProgress : ClaimResult

    /** The id was already charged for a different payload. */
    data object Conflict : ClaimResult
}
