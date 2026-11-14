package com.example.fee.record

import com.example.fee.record.FeeRecords.response
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class FeeRecordTest {
    @Test
    fun `a new record is pending with its workflow id and without a response`() {
        // when
        val record = FeeRecord.pending("workflow-a")

        // then
        assertThat(record).isEqualTo(FeeRecord(FeeRecordStatus.PENDING, "workflow-a", response = null))
    }

    @Test
    fun `charging stores the response and keeps the workflow id`() {
        // given
        val record = FeeRecord.pending("workflow-a")

        // when
        val charged = record.charge(response)

        // then
        assertThat(charged).isEqualTo(FeeRecord(FeeRecordStatus.CHARGED, "workflow-a", response = response))
    }

    @Test
    fun `rejects a charged record without a response`() {
        // when / then
        assertThatThrownBy { FeeRecord(FeeRecordStatus.CHARGED, "workflow-a") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("CHARGED")
    }

    @Test
    fun `rejects a response on a record that is not charged`() {
        // when / then
        assertThatThrownBy { FeeRecord(FeeRecordStatus.PENDING, "workflow-a", response = response) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("PENDING")
    }
}

class FeeRecordStatusTest {
    @Test
    fun `lists exactly the two stored statuses by name, as the table's check allows them`() {
        // given
        val stored = listOf("PENDING", "CHARGED")

        // when
        val names = FeeRecordStatus.entries.map { it.name }

        // then
        assertThat(names).containsExactlyElementsOf(stored)
    }
}
