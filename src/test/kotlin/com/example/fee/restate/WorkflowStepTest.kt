package com.example.fee.restate

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WorkflowStepTest {

    @Test
    fun `lists the steps in the order the workflow runs them, ending with DONE`() {
        // given
        val order = listOf("VALIDATE", "MAP", "CLAIM", "CALCULATE", "CHARGE", "RECORD", "DONE")

        // when
        val names = WorkflowStep.entries.map { it.name }

        // then
        assertThat(names).containsExactlyElementsOf(order)
    }
}
