package com.example.fee.record

import com.example.fee.record.FeeRecords.HASH
import com.example.fee.record.FeeRecords.OTHER_HASH
import com.example.fee.record.FeeRecords.response
import dev.restate.common.Slice
import dev.restate.serde.jackson.JacksonSerdeFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource

class ClaimDeciderTest {

    private val decider = ClaimDecider()

    @Test
    fun `no record yet is a new claim`() {
        // given
        val record: FeeRecord? = null

        // when
        val result = decider.decide(record, HASH)

        // then
        assertThat(result).isEqualTo(ClaimResult.New)
    }

    @ParameterizedTest
    @ValueSource(strings = [HASH, OTHER_HASH])
    fun `a record in progress is reported as in progress, whatever the payload`(hash: String) {
        // given
        val record = FeeRecord.inProgress(HASH)

        // when
        val result = decider.decide(record, hash)

        // then
        assertThat(result).isEqualTo(ClaimResult.InProgress)
    }

    @Test
    fun `a completed record for the same payload returns its stored response`() {
        // given
        val record = FeeRecord.inProgress(HASH).complete(response)

        // when
        val result = decider.decide(record, HASH)

        // then
        assertThat(result).isEqualTo(ClaimResult.AlreadyCompleted(response))
    }

    @Test
    fun `a completed record for another payload is a conflict`() {
        // given
        val record = FeeRecord.inProgress(HASH).complete(response)

        // when
        val result = decider.decide(record, OTHER_HASH)

        // then
        assertThat(result).isEqualTo(ClaimResult.Conflict)
    }

}

class ClaimResultTest {

    private val serde = JacksonSerdeFactory().create(ClaimResult::class.java)

    @ParameterizedTest
    @MethodSource("results")
    fun `is journaled under its explicit type name and read back equal`(result: ClaimResult, typeName: String) {
        // given
        val claim = result

        // when
        val json = String(serde.serialize(claim).toByteArray())
        val restored = serde.deserialize(Slice.wrap(json))

        // then
        assertThat(json).startsWith("""{"@type":"$typeName"""")
        assertThat(restored).isEqualTo(result)
    }

    @Test
    fun `keeps the stored response of an already completed claim`() {
        // given
        val claim: ClaimResult = ClaimResult.AlreadyCompleted(response)

        // when
        val json = String(serde.serialize(claim).toByteArray())

        // then
        assertThat(json).contains(""""response":{"transaction_id":"txn_001"""", """"fee":1.50""")
    }

    companion object {
        @JvmStatic
        fun results() = listOf(
            Arguments.of(ClaimResult.New, "NEW"),
            Arguments.of(ClaimResult.AlreadyCompleted(response), "ALREADY_COMPLETED"),
            Arguments.of(ClaimResult.InProgress, "IN_PROGRESS"),
            Arguments.of(ClaimResult.Conflict, "CONFLICT"),
        )
    }
}
