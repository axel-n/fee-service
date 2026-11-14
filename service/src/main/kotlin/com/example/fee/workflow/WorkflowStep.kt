package com.example.fee.workflow

/**
 * The steps of `FeeWorkflow.run` in the order they run, plus [DONE]. Each step is wrapped by [StepHook], and the
 * current one is kept in workflow state for `status()`.
 *
 * Stored and returned by name, so renaming, removing or reordering a value is a breaking change for in-flight workflows.
 * A new step is a new value here, its `StepHook` wrapping in the workflow and a row in the crash-recovery tests.
 */
enum class WorkflowStep {
    /** `FeeCalculator` in a journaled step, because the fee numbers come from configuration. */
    CALCULATE,

    /** `FeeRecordRepository.charge` in a journaled step: the fee is charged by recording it on the transaction's row. */
    CHARGE,

    /** The workflow returned the charged response. */
    DONE,
}
