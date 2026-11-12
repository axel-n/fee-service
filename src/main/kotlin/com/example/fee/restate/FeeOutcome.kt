package com.example.fee.restate

import com.example.fee.contract.FeeResponse
import com.example.fee.validation.ValidationError
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

/**
 * The business result of `FeeWorkflow.run`, returned instead of thrown so nobody parses exception messages (D9).
 * `FeeService` maps it to HTTP: [Charged] → 200, [Rejected] → 400 or 422, [Conflict] → 409, [Failed] → 422.
 *
 * The workflow's output, kept by Restate for the retention period and returned to every caller that attaches, so it is
 * durable (D35): the type ids are the explicit `@JsonSubTypes` names, never class names, and renaming a type id or a
 * field breaks stored outputs.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@type")
@JsonSubTypes(
    JsonSubTypes.Type(value = FeeOutcome.Charged::class, name = "CHARGED"),
    JsonSubTypes.Type(value = FeeOutcome.Rejected::class, name = "REJECTED"),
    JsonSubTypes.Type(value = FeeOutcome.Conflict::class, name = "CONFLICT"),
    JsonSubTypes.Type(value = FeeOutcome.Failed::class, name = "FAILED"),
)
sealed interface FeeOutcome {

    /** The fee was charged and recorded, now or by an earlier run for the same payload. */
    data class Charged(@JsonProperty("response") val response: FeeResponse) : FeeOutcome

    /** Validation rejected the request; the [error]'s kind decides 400 or 422. The id is not used up (D8). */
    data class Rejected(@JsonProperty("error") val error: ValidationError) : FeeOutcome

    /** The id is claimed by another payload, either charged or still in progress. */
    data class Conflict(@JsonProperty("transaction_id") val transactionId: String) : FeeOutcome

    /** The wallet declined the charge for [reason]; the record is `FAILED`, so the id may be retried. */
    data class Failed(@JsonProperty("reason") val reason: String) : FeeOutcome
}
