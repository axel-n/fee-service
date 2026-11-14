package com.example.fee.workflow

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WorkflowStepTest {
    @Test
    fun `lists the steps in the order the workflow runs them, ending with DONE`() {
        // given
        val order = listOf("CALCULATE", "CHARGE", "DONE")

        // when
        val names = WorkflowStep.entries.map { it.name }

        // then
        assertThat(names).containsExactlyElementsOf(order)
    }
}
