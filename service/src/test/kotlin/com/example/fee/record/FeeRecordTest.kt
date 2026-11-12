package com.example.fee.record

import com.example.fee.record.FeeRecords.response
import dev.restate.common.Slice
import dev.restate.serde.jackson.JacksonSerdeFactory
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class FeeRecordTest {

    private val serde = JacksonSerdeFactory().create(FeeRecord::class.java)

    @Test
    fun `a new claim is in progress without a response`() {
        // when
        val record = FeeRecord.inProgress()

        // then
        assertThat(record).isEqualTo(FeeRecord(FeeRecordStatus.IN_PROGRESS, response = null))
    }

    @Test
    fun `completing stores the response`() {
        // given
        val record = FeeRecord.inProgress()

        // when
        val completed = record.complete(response)

        // then
        assertThat(completed).isEqualTo(FeeRecord(FeeRecordStatus.COMPLETED, response = response))
    }


    @Test
    fun `rejects a completed record without a response`() {
        // when / then
        assertThatThrownBy { FeeRecord(FeeRecordStatus.COMPLETED) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("COMPLETED")
    }

    @Test
    fun `rejects a response on a record that is not completed`() {
        // when / then
        assertThatThrownBy { FeeRecord(FeeRecordStatus.IN_PROGRESS, response = response) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("IN_PROGRESS")
    }


    @Test
    fun `is stored by Restate's serde with snake_case names and read back unchanged`() {
        // given
        val record = FeeRecord.inProgress().complete(response)

        // when
        val json = String(serde.serialize(record).toByteArray())
        val restored = serde.deserialize(Slice.wrap(json))

        // then
        assertThat(json).startsWith("""{"status":"COMPLETED","response":{"transaction_id":"txn_001",""")
        assertThat(json).doesNotContain("failure_reason")
        assertThat(restored).isEqualTo(record)
        assertThat(restored.response?.fee).isEqualByComparingTo("1.5")
    }

    @Test
    fun `refuses stored state that breaks the status invariant`() {
        // given
        val json = """{"status":"COMPLETED","response":null}"""

        // when / then
        assertThatThrownBy { serde.deserialize(Slice.wrap(json)) }
            .hasStackTraceContaining("COMPLETED")
    }
}

class FeeRecordStatusTest {

    @Test
    fun `lists exactly the two stored statuses by name`() {
        // given
        val stored = listOf("IN_PROGRESS", "COMPLETED")

        // when
        val names = FeeRecordStatus.entries.map { it.name }

        // then
        assertThat(names).containsExactlyElementsOf(stored)
    }
}
