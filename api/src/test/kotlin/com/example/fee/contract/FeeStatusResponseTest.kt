package com.example.fee.contract

import com.example.fee.contract.FeeStatusResponse.Status
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class FeeStatusResponseTest {
    // HTTP only, so Spring's mapper is the one that matters
    private val mapper = ContractMapper.SPRING

    private val response =
        FeeResponse(
            transactionId = "txn_001",
            amount = BigDecimal("1000"),
            asset = "USD",
            type = "Mobile Top Up",
            fee = BigDecimal("1.50"),
            rate = BigDecimal("0.0015"),
            description = "Standard fee rate of 0.15%",
        )

    @Test
    fun `completed status carries every fee response field`() {
        // given
        val status = FeeStatusResponse.completed(response)

        // when
        val json = mapper.write(status, FeeStatusResponse::class.java)

        // then
        assertThat(ContractMapper.fieldNames(json)).containsExactlyInAnyOrder(
            "transaction_id",
            "status",
            "amount",
            "asset",
            "type",
            "fee",
            "rate",
            "description",
        )
        val node = ContractMapper.tree(json)
        assertThat(node["status"].asText()).isEqualTo("COMPLETED")
        assertThat(node["transaction_id"].asText()).isEqualTo("txn_001")
        assertThat(node["fee"].decimalValue()).isEqualByComparingTo("1.5")
        assertThat(node["rate"].decimalValue()).isEqualByComparingTo("0.0015")
        assertThat(node["description"].asText()).isEqualTo("Standard fee rate of 0.15%")
    }

    @Test
    fun `in-progress status has only the id and the status`() {
        // given
        val status = FeeStatusResponse.inProgress("txn_001")

        // when
        val json = mapper.write(status, FeeStatusResponse::class.java)

        // then
        assertThat(ContractMapper.fieldNames(json)).containsExactlyInAnyOrder("transaction_id", "status")
        assertThat(ContractMapper.tree(json)["status"].asText()).isEqualTo("IN_PROGRESS")
    }

    @Test
    fun `round-trips a completed status unchanged`() {
        // given
        val status = FeeStatusResponse.completed(response)

        // when
        val roundTripped = mapper.read(mapper.write(status, FeeStatusResponse::class.java), FeeStatusResponse::class.java)

        // then
        assertThat(roundTripped).isEqualTo(status)
        assertThat(roundTripped.status).isEqualTo(Status.COMPLETED)
    }
}
