package com.example.fee.validation

import com.example.fee.contract.FeeRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.core.annotation.Order
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** [RequestValidator] with the 9 production validators, built as Spring builds them but in shuffled order. */
class RequestValidatorAllChecksTest {

    private val clock = Clock.fixed(Instant.parse("2023-08-30T16:00:00Z"), ZoneOffset.UTC)

    private val validators: List<TransactionValidator> = listOf(
        AmountScaleValidator(),
        StateValidator(),
        CreatedAtValidator(clock),
        TransactionTypeValidator(allRulesCalculator()),
        RequiredFieldsValidator(),
        AssetTypeValidator(),
        AmountValidator(),
        AssetValidator(),
        TransactionIdValidator(),
    )

    private val requestValidator = RequestValidator(validators)

    /** Fails every business check at once; the amount scale check needs a known asset, so it is shown separately. */
    private val unsupportedEverywhere = validRequest.copy(
        state = "PENDING",
        asset = "BTC",
        assetType = "CRYPTO",
        type = "Refund",
    )

    @Test
    fun `passes the reference request`() {
        // given
        val request = validRequest

        // when
        val error = requestValidator.validate(request)

        // then
        assertThat(error).isNull()
    }

    @Test
    fun `uses all 9 validators, each with its own order`() {
        // given
        val orders = validators.map { it::class.java.getAnnotation(Order::class.java)?.value }

        // when
        val distinct = orders.filterNotNull().toSet()

        // then
        assertThat(distinct).hasSize(9)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("structuralProblems")
    fun `reports a 400 problem before every 422 problem`(field: String, broken: (FeeRequest) -> FeeRequest) {
        // given
        val request = broken(unsupportedEverywhere)

        // when
        val error = requestValidator.validate(request)

        // then
        assertThat(error?.kind).isEqualTo(ValidationError.Kind.INVALID)
        assertThat(error?.field).isEqualTo(field)
    }

    @Test
    fun `reports a malformed date before too many decimals`() {
        // given
        val request = validRequest.copy(amount = BigDecimal("10.001"), createdAt = "2099-01-01 00:00:00")

        // when
        val error = requestValidator.validate(request)

        // then
        assertThat(error?.kind).isEqualTo(ValidationError.Kind.INVALID)
        assertThat(error?.field).isEqualTo("created_at")
    }

    @Test
    fun `reports the business problems in the order state, asset, asset type, type, amount scale`() {
        // given
        val requests = listOf(
            unsupportedEverywhere.copy(amount = BigDecimal("10.001")),
            unsupportedEverywhere.copy(amount = BigDecimal("10.001"), state = "SETTLED - PENDING FEE"),
            unsupportedEverywhere.copy(amount = BigDecimal("10.001"), state = "SETTLED - PENDING FEE", asset = "USD"),
            validRequest.copy(amount = BigDecimal("10.001"), type = "Refund"),
            validRequest.copy(amount = BigDecimal("10.001")),
        )

        // when
        val errors = requests.map { requestValidator.validate(it) }

        // then
        assertThat(errors.map { it?.field }).containsExactly("state", "asset", "asset_type", "type", "amount")
        assertThat(errors.map { it?.kind }).containsOnly(ValidationError.Kind.UNSUPPORTED)
    }

    @Test
    fun `reports too many decimals with the asset's allowed decimals`() {
        // given
        val request = validRequest.copy(amount = BigDecimal("10.001"))

        // when
        val error = requestValidator.validate(request)

        // then
        assertThat(error?.message).isEqualTo("Amount 10.001 has 3 decimals. USD allows 2")
    }

    companion object {

        @JvmStatic
        fun structuralProblems(): List<Arguments> = listOf(
            problem("transaction_id") { it.copy(transactionId = null) },
            problem("amount") { it.copy(amount = null) },
            problem("created_at") { it.copy(createdAt = " ") },
            problem("transaction_id") { it.copy(transactionId = "txn:001") },
            problem("amount") { it.copy(amount = BigDecimal("-10.001")) },
            problem("created_at") { it.copy(createdAt = "2023-08-30T15:42:17") },
            problem("created_at") { it.copy(createdAt = "2099-01-01 00:00:00") },
        )

        private fun problem(field: String, broken: (FeeRequest) -> FeeRequest): Arguments =
            Arguments.of(field, broken)
    }
}
