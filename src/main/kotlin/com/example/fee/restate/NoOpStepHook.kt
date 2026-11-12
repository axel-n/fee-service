package com.example.fee.restate

import org.springframework.stereotype.Component

/** The production [StepHook]: does nothing. Tests provide their own bean in its place. */
@Component
class NoOpStepHook : StepHook {

    override fun before(step: WorkflowStep, workflowKey: String) = Unit

    override fun after(step: WorkflowStep, workflowKey: String) = Unit
}
