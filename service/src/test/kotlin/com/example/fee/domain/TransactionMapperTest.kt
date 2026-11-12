package com.example.fee.domain

import com.example.fee.contract.FeeRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.math.BigDecimal
import java.time.LocalDateTime

class TransactionMapperTest {

    private val mapper = TransactionMapper()

    private val valid = FeeRequest(
        transactionId = "txn_001",
        amount = BigDecimal("1000.50"),
        asset = "EUR",
        assetType = "FIAT",
        type = "Bank Transfer",
        state = "SETTLED - PENDING FEE",
        createdAt = "2023-08-30 15:42:17.610059",
    )

    @Test
    fun `maps every field of a valid request`() {
        // given
        val request = valid

        // when
        val transaction = mapper.toTransaction(request)

        // then
        assertThat(transaction).isEqualTo(
            Transaction(
                transactionId = "txn_001",
                amount = BigDecimal("1000.50"),
                asset = Asset.EUR,
                assetType = AssetType.FIAT,
                type = TransactionType.BANK_TRANSFER,
                state = TransactionState.SETTLED_PENDING_FEE,
                createdAt = LocalDateTime.of(2023, 8, 30, 15, 42, 17, 610_059_000),
            ),
        )
    }

    @Test
    fun `keeps the amount exactly as sent, scale included`() {
        // given
        val request = valid.copy(amount = BigDecimal("1000"))

        // when
        val transaction = mapper.toTransaction(request)

        // then
        assertThat(transaction?.amount).isEqualByComparingTo(BigDecimal("1000"))
        assertThat(transaction?.amount?.scale()).isEqualTo(0)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("supportedTypes")
    fun `maps every supported transaction type`(raw: String, expected: TransactionType) {
        // given
        val request = valid.copy(type = raw)

        // when
        val transaction = mapper.toTransaction(request)

        // then
        assertThat(transaction?.type).isEqualTo(expected)
    }

    @Test
    fun `maps unsupported enum values to UNKNOWN without throwing`() {
        // given
        val request = valid.copy(asset = "BTC", assetType = "CRYPTO", type = "Refund", state = "PENDING")

        // when
        val transaction = mapper.toTransaction(request)

        // then
        assertThat(transaction?.asset).isEqualTo(Asset.UNKNOWN)
        assertThat(transaction?.assetType).isEqualTo(AssetType.UNKNOWN)
        assertThat(transaction?.type).isEqualTo(TransactionType.UNKNOWN)
        assertThat(transaction?.state).isEqualTo(TransactionState.UNKNOWN)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unvalidatedRequests")
    fun `returns null instead of throwing for a request that would not pass validation`(
        case: String,
        request: FeeRequest,
    ) {
        // given
        val input = request

        // when
        val transaction = mapper.toTransaction(input)

        // then
        assertThat(transaction).isNull()
    }

    companion object {
        @JvmStatic
        fun supportedTypes(): List<Arguments> = TransactionType.entries
            .filter { it != TransactionType.UNKNOWN }
            .map { Arguments.of(it.value, it) }

        @JvmStatic
        fun unvalidatedRequests(): List<Arguments> {
            val valid = TransactionMapperTest().valid
            return listOf(
                Arguments.of("empty request", FeeRequest()),
                Arguments.of("no transaction_id", valid.copy(transactionId = null)),
                Arguments.of("no amount", valid.copy(amount = null)),
                Arguments.of("no created_at", valid.copy(createdAt = null)),
                Arguments.of("malformed created_at", valid.copy(createdAt = "yesterday")),
            )
        }
    }
}
