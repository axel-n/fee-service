package com.example.fee.record

import com.example.fee.record.FeeRecords.response
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class FeeRecordTest {
    @Test
    fun `a new claim is in progress with its token and without a response`() {
        // when
        val record = FeeRecord.inProgress("token-a")

        // then
        assertThat(record).isEqualTo(FeeRecord(FeeRecordStatus.IN_PROGRESS, "token-a", response = null))
    }

    @Test
    fun `completing stores the response and keeps the claim token`() {
        // given
        val record = FeeRecord.inProgress("token-a")

        // when
        val completed = record.complete(response)

        // then
        assertThat(completed).isEqualTo(FeeRecord(FeeRecordStatus.COMPLETED, "token-a", response = response))
    }

    @Test
    fun `rejects a completed record without a response`() {
        // when / then
        assertThatThrownBy { FeeRecord(FeeRecordStatus.COMPLETED, "token-a") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("COMPLETED")
    }

    @Test
    fun `rejects a response on a record that is not completed`() {
        // when / then
        assertThatThrownBy { FeeRecord(FeeRecordStatus.IN_PROGRESS, "token-a", response = response) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("IN_PROGRESS")
    }
}

class FeeRecordStatusTest {
    @Test
    fun `lists exactly the two stored statuses by name, as the table's check allows them`() {
        // given
        val stored = listOf("IN_PROGRESS", "COMPLETED")

        // when
        val names = FeeRecordStatus.entries.map { it.name }

        // then
        assertThat(names).containsExactlyElementsOf(stored)
    }
}
