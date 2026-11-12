package com.example.fee.restate

import com.example.fee.restate.FeeRecords.HASH
import com.example.fee.restate.FeeRecords.OTHER_HASH
import com.example.fee.restate.FeeRecords.response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
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

    @ParameterizedTest
    @ValueSource(strings = [HASH, OTHER_HASH])
    fun `a failed record frees the id for a new claim, whatever the payload`(hash: String) {
        // given
        val record = FeeRecord.inProgress(HASH).fail("Charge declined: insufficient funds")

        // when
        val result = decider.decide(record, hash)

        // then
        assertThat(result).isEqualTo(ClaimResult.New)
    }
}
