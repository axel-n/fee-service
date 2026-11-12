package com.example.fee.restate

import com.example.fee.restate.FeeRecords.HASH
import com.example.fee.restate.FeeRecords.response
import dev.restate.common.Slice
import dev.restate.serde.jackson.JacksonSerdeFactory
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class FeeRecordTest {

    private val serde = JacksonSerdeFactory().create(FeeRecord::class.java)

    @Test
    fun `a new claim is in progress with only the payload hash`() {
        // given
        val hash = HASH

        // when
        val record = FeeRecord.inProgress(hash)

        // then
        assertThat(record).isEqualTo(FeeRecord(FeeRecordStatus.IN_PROGRESS, HASH, response = null, failureReason = null))
    }

    @Test
    fun `completing keeps the payload hash and stores the response`() {
        // given
        val record = FeeRecord.inProgress(HASH)

        // when
        val completed = record.complete(response)

        // then
        assertThat(completed).isEqualTo(FeeRecord(FeeRecordStatus.COMPLETED, HASH, response = response))
    }

    @Test
    fun `failing keeps the payload hash and stores the reason`() {
        // given
        val record = FeeRecord.inProgress(HASH)

        // when
        val failed = record.fail("Charge declined: insufficient funds")

        // then
        assertThat(failed).isEqualTo(
            FeeRecord(FeeRecordStatus.FAILED, HASH, failureReason = "Charge declined: insufficient funds"),
        )
    }

    @Test
    fun `rejects a completed record without a response`() {
        // when / then
        assertThatThrownBy { FeeRecord(FeeRecordStatus.COMPLETED, HASH) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("COMPLETED")
    }

    @Test
    fun `rejects a response on a record that is not completed`() {
        // when / then
        assertThatThrownBy { FeeRecord(FeeRecordStatus.IN_PROGRESS, HASH, response = response) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("IN_PROGRESS")
    }

    @Test
    fun `rejects a failed record without a reason and a reason on any other`() {
        // when / then
        assertThatThrownBy { FeeRecord(FeeRecordStatus.FAILED, HASH) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("FAILED")
        assertThatThrownBy { FeeRecord(FeeRecordStatus.IN_PROGRESS, HASH, failureReason = "boom") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("boom")
    }

    @Test
    fun `is stored by Restate's serde with snake_case names and read back unchanged`() {
        // given
        val record = FeeRecord.inProgress(HASH).complete(response)

        // when
        val json = String(serde.serialize(record).toByteArray())
        val restored = serde.deserialize(Slice.wrap(json))

        // then
        assertThat(json).startsWith("""{"status":"COMPLETED","payload_hash":"hash-a","response":{"transaction_id":"txn_001",""")
        assertThat(json).endsWith(""","failure_reason":null}""")
        assertThat(restored).isEqualTo(record)
        assertThat(restored.response?.fee).isEqualByComparingTo("1.5")
    }

    @Test
    fun `refuses stored state that breaks the status invariant`() {
        // given
        val json = """{"status":"COMPLETED","payload_hash":"hash-a","response":null,"failure_reason":null}"""

        // when / then
        assertThatThrownBy { serde.deserialize(Slice.wrap(json)) }
            .hasStackTraceContaining("COMPLETED")
    }
}
