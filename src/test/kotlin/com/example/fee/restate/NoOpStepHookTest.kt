package com.example.fee.restate

import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class NoOpStepHookTest {

    private val hook: StepHook = NoOpStepHook()

    @ParameterizedTest
    @EnumSource(WorkflowStep::class)
    fun `lets every step pass without interfering`(step: WorkflowStep) {
        // given
        val key = "txn_001:hash-a"

        // when / then
        assertThatCode {
            hook.before(step, key)
            hook.after(step, key)
        }.doesNotThrowAnyException()
    }
}
