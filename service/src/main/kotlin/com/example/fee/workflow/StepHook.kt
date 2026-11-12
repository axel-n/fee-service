package com.example.fee.workflow

import org.springframework.stereotype.Component

/**
 * Called by `FeeWorkflow` around every [WorkflowStep], the seam for fault injection. Production uses
 * [NoOpStepHook]; tests replace it to crash the workflow at any step boundary.
 *
 * Both methods run in the handler, outside `ctx.run`, so they run again on every replay. A plain exception thrown here
 * makes Restate retry the invocation from the top, replaying the steps journaled so far: that is how a crash is
 * simulated. An implementation must not touch anything the workflow decides on.
 */
interface StepHook {

    /** Called before [step] runs in the workflow with [workflowKey] (`{transaction_id}:{payloadHash}`). */
    fun before(step: WorkflowStep, workflowKey: String)

    /** Called after [step] completed in the workflow with [workflowKey]. */
    fun after(step: WorkflowStep, workflowKey: String)
}

/** The production [StepHook]: does nothing. Tests provide their own bean in its place. */
@Component
class NoOpStepHook : StepHook {

    override fun before(step: WorkflowStep, workflowKey: String) = Unit

    override fun after(step: WorkflowStep, workflowKey: String) = Unit
}
