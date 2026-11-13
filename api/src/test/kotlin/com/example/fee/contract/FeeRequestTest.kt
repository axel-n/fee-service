package com.example.fee.contract

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.math.BigDecimal

class FeeRequestTest {
    private val full =
        FeeRequest(
            transactionId = "txn_001",
            amount = BigDecimal("1000"),
            asset = "USD",
            assetType = "FIAT",
            type = "Mobile Top Up",
            state = "SETTLED - PENDING FEE",
            createdAt = "2023-08-30 15:42:17.610059",
        )

    @ParameterizedTest
    @EnumSource(ContractMapper::class)
    fun `reads the reference request with snake_case field names`(mapper: ContractMapper) {
        // given
        val json =
            """
            {
              "transaction_id": "txn_001",
              "amount": 1000,
              "asset": "USD",
              "asset_type": "FIAT",
              "type": "Mobile Top Up",
              "state": "SETTLED - PENDING FEE",
              "created_at": "2023-08-30 15:42:17.610059"
            }
            """.trimIndent()

        // when
        val request = mapper.read(json, FeeRequest::class.java)

        // then
        assertThat(request).isEqualTo(full)
    }

    @ParameterizedTest
    @EnumSource(ContractMapper::class)
    fun `writes exactly the pinned snake_case field names`(mapper: ContractMapper) {
        // given
        val request = full

        // when
        val json = mapper.write(request, FeeRequest::class.java)

        // then
        assertThat(ContractMapper.fieldNames(json)).containsExactlyInAnyOrder(
            "transaction_id",
            "amount",
            "asset",
            "asset_type",
            "type",
            "state",
            "created_at",
        )
        val node = ContractMapper.tree(json)
        assertThat(node["transaction_id"].asText()).isEqualTo("txn_001")
        assertThat(node["asset_type"].asText()).isEqualTo("FIAT")
        assertThat(node["created_at"].asText()).isEqualTo("2023-08-30 15:42:17.610059")
        assertThat(node["amount"].isNumber).isTrue()
    }

    @ParameterizedTest
    @EnumSource(ContractMapper::class)
    fun `round-trips unchanged, keeping the amount's scale`(mapper: ContractMapper) {
        // given
        val request = full.copy(amount = BigDecimal("1000.50"))

        // when
        val roundTripped = mapper.read(mapper.write(request, FeeRequest::class.java), FeeRequest::class.java)

        // then
        assertThat(roundTripped).isEqualTo(request)
        assertThat(roundTripped.amount?.scale()).isEqualTo(2)
    }

    @ParameterizedTest
    @EnumSource(ContractMapper::class)
    fun `writes the amount as a plain number, never in scientific notation`(mapper: ContractMapper) {
        // given
        val request = full.copy(amount = BigDecimal("1E+3"))

        // when
        val json = mapper.write(request, FeeRequest::class.java)

        // then
        assertThat(json).contains("\"amount\":1000")
        assertThat(mapper.read(json, FeeRequest::class.java).amount).isEqualByComparingTo("1000")
    }

    @ParameterizedTest
    @EnumSource(ContractMapper::class)
    fun `reads missing fields as null, so validation can explain what is missing`(mapper: ContractMapper) {
        // given
        val json = """{"transaction_id": "txn_001"}"""

        // when
        val request = mapper.read(json, FeeRequest::class.java)

        // then
        assertThat(request).isEqualTo(FeeRequest(transactionId = "txn_001"))
    }

    @ParameterizedTest
    @EnumSource(ContractMapper::class)
    fun `round-trips an empty request`(mapper: ContractMapper) {
        // given
        val request = FeeRequest()

        // when
        val roundTripped = mapper.read(mapper.write(request, FeeRequest::class.java), FeeRequest::class.java)

        // then
        assertThat(roundTripped).isEqualTo(request)
    }
}
