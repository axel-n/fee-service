package com.example.fee.application

import com.example.fee.contract.FeeResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class FeeSubmissionTest {

    private val response = FeeResponse(
        transactionId = "txn_001",
        amount = BigDecimal("1000"),
        asset = "USD",
        type = "Mobile Top Up",
        fee = BigDecimal("1.50"),
        rate = BigDecimal("0.0015"),
        description = "Standard fee rate of 0.15%",
    )

    @Test
    fun `a completed submission carries the fee response`() {
        // given
        val submission: FeeSubmission = FeeSubmission.Completed(response)

        // when
        val carried = (submission as FeeSubmission.Completed).response

        // then
        assertThat(carried).isEqualTo(response)
        assertThat(carried.fee).isEqualByComparingTo("1.5")
    }

    @Test
    fun `a submission still processing carries the transaction id the caller polls`() {
        // given
        val submission: FeeSubmission = FeeSubmission.Processing("txn_001")

        // when
        val transactionId = (submission as FeeSubmission.Processing).transactionId

        // then
        assertThat(transactionId).isEqualTo("txn_001")
    }

    @Test
    fun `submissions are values compared by content`() {
        // given
        val first = FeeSubmission.Processing("txn_001")

        // when
        val same = FeeSubmission.Processing("txn_001")

        // then
        assertThat(same).isEqualTo(first)
        assertThat(FeeSubmission.Completed(response.copy())).isEqualTo(FeeSubmission.Completed(response))
        assertThat(FeeSubmission.Processing("txn_002")).isNotEqualTo(first)
    }
}
