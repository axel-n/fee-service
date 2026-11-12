package com.example.fee.workflow

import com.example.fee.contract.FeeResponse
import com.example.fee.validation.ValidationError
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

/**
 * The business result of `FeeWorkflow.run`, returned instead of thrown so nobody parses exception messages.
 * `FeeService` maps it to HTTP: [Charged] → 200, [Rejected] → 400 or 422, [Conflict] → 409.
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
)
sealed interface FeeOutcome {

    /** The fee was charged (recorded), now or by an earlier run of the same `transaction_id`. */
    data class Charged(@JsonProperty("response") val response: FeeResponse) : FeeOutcome

    /**
     * Validation rejected the request; the [error]'s kind decides 400 or 422. Nothing is recorded, but this is the
     * answer for the `transaction_id` until Restate drops the finished run.
     */
    data class Rejected(@JsonProperty("error") val error: ValidationError) : FeeOutcome

    /**
     * The id's fee record is claimed by an earlier run that never finished (one an operator killed, say), so this run
     * can't charge it.
     */
    data class Conflict(@JsonProperty("transaction_id") val transactionId: String) : FeeOutcome
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

    /** `TransactionFeeRecord.claim()`: the fee record of the `transaction_id`, kept beyond the run's retention. */
    CLAIM,

    /** `FeeCalculator` inside `ctx.run`, because the fee numbers come from configuration. */
    CALCULATE,

    /** `TransactionFeeRecord.complete(response)`: the fee is charged by recording it. */
    RECORD,

    /** The workflow returned its outcome. */
    DONE,
}
