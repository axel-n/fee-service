package com.example.fee.restate

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class FeeRecordStatusTest {

    @Test
    fun `lists exactly the three stored statuses by name`() {
        // given
        val stored = listOf("IN_PROGRESS", "COMPLETED", "FAILED")

        // when
        val names = FeeRecordStatus.entries.map { it.name }

        // then
        assertThat(names).containsExactlyElementsOf(stored)
    }
}
