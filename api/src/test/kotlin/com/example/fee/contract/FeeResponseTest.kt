package com.example.fee.contract

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.math.BigDecimal

class FeeResponseTest {
    private val reference =
        FeeResponse(
            transactionId = "txn_001",
            amount = BigDecimal("1000"),
            asset = "USD",
            type = "Mobile Top Up",
            fee = BigDecimal("1.50"),
            rate = BigDecimal("0.0015"),
            description = "Standard fee rate of 0.15%",
        )

    @ParameterizedTest
    @EnumSource(ContractMapper::class)
    fun `writes exactly the pinned snake_case field names`(mapper: ContractMapper) {
        // given
        val response = reference

        // when
        val json = mapper.write(response, FeeResponse::class.java)

        // then
        assertThat(ContractMapper.fieldNames(json)).containsExactlyInAnyOrder(
            "transaction_id",
            "amount",
            "asset",
            "type",
            "fee",
            "rate",
            "description",
        )
        val node = ContractMapper.tree(json)
        assertThat(node["transaction_id"].asText()).isEqualTo("txn_001")
        assertThat(node["asset"].asText()).isEqualTo("USD")
        assertThat(node["type"].asText()).isEqualTo("Mobile Top Up")
        assertThat(node["description"].asText()).isEqualTo("Standard fee rate of 0.15%")
    }

    @ParameterizedTest
    @EnumSource(ContractMapper::class)
    fun `writes money and rate as plain JSON numbers`(mapper: ContractMapper) {
        // given
        val response = reference.copy(amount = BigDecimal("1E+3"), rate = BigDecimal("1E-7"))

        // when
        val json = mapper.write(response, FeeResponse::class.java)

        // then
        assertThat(json).contains("\"amount\":1000", "\"fee\":1.50", "\"rate\":0.0000001")
        val node = ContractMapper.tree(json)
        assertThat(node["amount"].isNumber).isTrue()
        assertThat(node["fee"].isNumber).isTrue()
        assertThat(node["rate"].isNumber).isTrue()
    }

    @ParameterizedTest
    @EnumSource(ContractMapper::class)
    fun `round-trips unchanged, keeping every scale`(mapper: ContractMapper) {
        // given
        val response = reference

        // when
        val roundTripped = mapper.read(mapper.write(response, FeeResponse::class.java), FeeResponse::class.java)

        // then
        assertThat(roundTripped).isEqualTo(response)
        assertThat(roundTripped.fee.scale()).isEqualTo(2)
    }

    @ParameterizedTest
    @EnumSource(ContractMapper::class)
    fun `reads the reference response`(mapper: ContractMapper) {
        // given
        val json =
            """
            {
              "transaction_id": "txn_001",
              "amount": 1000,
              "asset": "USD",
              "type": "Mobile Top Up",
              "fee": 1.5,
              "rate": 0.0015,
              "description": "Standard fee rate of 0.15%"
            }
            """.trimIndent()

        // when
        val response = mapper.read(json, FeeResponse::class.java)

        // then
        assertThat(response.transactionId).isEqualTo("txn_001")
        assertThat(response.amount).isEqualByComparingTo("1000")
        assertThat(response.asset).isEqualTo("USD")
        assertThat(response.type).isEqualTo("Mobile Top Up")
        assertThat(response.fee).isEqualByComparingTo("1.5")
        assertThat(response.rate).isEqualByComparingTo("0.0015")
        assertThat(response.description).isEqualTo("Standard fee rate of 0.15%")
    }

    @ParameterizedTest
    @EnumSource(ContractMapper::class)
    fun `rejects a stored response without a required field`(mapper: ContractMapper) {
        // given
        val json = """{"transaction_id": "txn_001", "amount": 1000, "asset": "USD", "type": "Mobile Top Up"}"""

        // when / then
        assertThatThrownBy { mapper.read(json, FeeResponse::class.java) }.isInstanceOf(Exception::class.java)
    }
}
