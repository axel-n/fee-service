package com.example.fee.workflow

import com.example.fee.contract.FeeRequest
import com.example.fee.contract.FeeResponse
import com.example.fee.validation.ValidationError
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

/**
 * The input of `FeeWorkflow.run`: the [request] exactly as the client sent it and its [payloadHash], the second half of
 * the workflow key. The workflow passes the hash to the claim, so the record can tell this payload from another.
 *
 * Journaled as the workflow input: renaming or removing a field breaks in-flight workflows; adding a nullable
 * field is safe.
 */
data class FeeWorkflowInput(
    @JsonProperty("request") val request: FeeRequest,
    @JsonProperty("payload_hash") val payloadHash: String,
)

/**
 * The business result of `FeeWorkflow.run`, returned instead of thrown so nobody parses exception messages.
 * `FeeService` maps it to HTTP: [Charged] → 200, [Rejected] → 400 or 422, [Conflict] → 409, [Failed] → 422.
 *
 * The workflow's output, kept by Restate for the retention period and returned to every caller that attaches, so it is
 * durable: the type ids are the explicit `@JsonSubTypes` names, never class names, and renaming a type id or a
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

    /** Validation rejected the request; the [error]'s kind decides 400 or 422. The id is not used up. */
    data class Rejected(@JsonProperty("error") val error: ValidationError) : FeeOutcome

    /** The id is claimed by another payload, either charged or still in progress. */
    data class Conflict(@JsonProperty("transaction_id") val transactionId: String) : FeeOutcome

    /** The wallet declined the charge for [reason]; the record is `FAILED`, so the id may be retried. */
    data class Failed(@JsonProperty("reason") val reason: String) : FeeOutcome
}

/**
 * The steps of `FeeWorkflow.run` in the order they run, plus [DONE]. Each step is wrapped by [StepHook], and the
 * current one is kept in workflow state for `status()`.
 *
 * Stored and returned by name, so renaming, removing or reordering a value is a breaking change for in-flight workflows.
 * A new step is a new value here, its `StepHook` wrapping in the workflow and a row in the crash-recovery tests.
 */
enum class WorkflowStep {
    /** `RequestValidator` inside `ctx.run`, because it reads the clock. */
    VALIDATE,

    /** `TransactionMapper`: pure and deterministic, so not journaled. */
    MAP,

    /** `TransactionFeeRecord.claim(hash)`: one payload per `transaction_id`. */
    CLAIM,

    /** `FeeCalculator` inside `ctx.run`, because the fee numbers come from configuration. */
    CALCULATE,

    /** `WalletGateway.charge` inside `ctx.run`; a decline becomes a `TerminalException`. */
    CHARGE,

    /** `TransactionFeeRecord.complete(response)` or `fail(reason)`. */
    RECORD,

    /** The workflow returned its outcome. */
    DONE,
}
