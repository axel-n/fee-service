package com.example.fee.validation

import com.example.fee.domain.TransactionType
import com.example.fee.fee.FeeCalculator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.core.annotation.Order

class TransactionTypeValidatorTest {

    private val validator = TransactionTypeValidator(allRulesCalculator())

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["Mobile Top Up", "Bank Transfer", "Merchant Payment", "Cash Withdrawal"])
    fun `passes every type that has a fee rule`(type: String) {
        // given
        val request = validRequest.copy(type = type)

        // when
        val error = validator.validate(request)

        // then
        assertThat(error).isNull()
    }

    @ParameterizedTest(name = "''{0}''")
    @ValueSource(strings = ["Refund", "mobile top up", "MOBILE_TOP_UP", "UNKNOWN"])
    fun `rejects any other type with readable message naming the supported ones`(type: String) {
        // given
        val request = validRequest.copy(type = type)

        // when
        val error = validator.validate(request)

        // then
        assertThat(error).isEqualTo(
            ValidationError(
                "type",
                type,
                "Unsupported type '$type'. Supported: 'Mobile Top Up', 'Bank Transfer', 'Merchant Payment', " +
                    "'Cash Withdrawal'",
                ValidationError.Kind.UNSUPPORTED,
            ),
        )
    }

    @Test
    fun `asks the fee calculator which types are supported`() {
        // given
        val calculator = mock(FeeCalculator::class.java)
        // a mock answers false for every other type
        `when`(calculator.supports(TransactionType.BANK_TRANSFER)).thenReturn(true)
        val onlyBankTransfer = TransactionTypeValidator(calculator)

        // when
        val passed = onlyBankTransfer.validate(validRequest.copy(type = "Bank Transfer"))
        val rejected = onlyBankTransfer.validate(validRequest.copy(type = "Mobile Top Up"))

        // then
        assertThat(passed).isNull()
        assertThat(rejected?.message).isEqualTo("Unsupported type 'Mobile Top Up'. Supported: 'Bank Transfer'")
    }

    @Test
    fun `leaves a missing type to the required fields check`() {
        // given
        val request = validRequest.copy(type = null)

        // when
        val error = validator.validate(request)

        // then
        assertThat(error).isNull()
    }

    @Test
    fun `runs after the asset type check`() {
        // given
        val type = TransactionTypeValidator::class.java

        // when
        val order = type.getAnnotation(Order::class.java)?.value

        // then
        assertThat(order).isEqualTo(ValidationOrder.TRANSACTION_TYPE)
    }
}
