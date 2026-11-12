package com.example.fee.restate

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

    /** `FeeCalculator` inside `ctx.run`, because the fee numbers come from configuration (D23). */
    CALCULATE,

    /** `WalletGateway.charge` inside `ctx.run`; a decline becomes a `TerminalException`. */
    CHARGE,

    /** `TransactionFeeRecord.complete(response)` or `fail(reason)`. */
    RECORD,

    /** The workflow returned its outcome. */
    DONE,
}
