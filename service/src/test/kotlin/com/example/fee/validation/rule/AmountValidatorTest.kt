package com.example.fee.validation.rule

import com.example.fee.validation.ValidationError
import com.example.fee.validation.ValidationOrder
import com.example.fee.validation.validRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.core.annotation.Order
import java.math.BigDecimal

class AmountValidatorTest {
    private val validator = AmountValidator()

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["1000", "0.01", "1E+3", "10.001", "0.0000001"])
    fun `passes a positive amount whatever its decimals`(amount: String) {
        // given
        val request = validRequest.copy(amount = BigDecimal(amount))

        // when
        val error = validator.validate(request)

        // then
        assertThat(error).isNull()
    }

    @ParameterizedTest(name = "{0} is shown as {1}")
    @CsvSource("0, 0", "0.00, 0.00", "-5, -5", "-1.50, -1.50", "-1E+3, -1000")
    fun `rejects zero and negative amounts with a message naming the value`(amount: String, shown: String) {
        // given
        val request = validRequest.copy(amount = BigDecimal(amount))

        // when
        val error = validator.validate(request)

        // then
        assertThat(error).isEqualTo(
            ValidationError(
                "amount",
                shown,
                "Invalid amount '$shown'. Allowed: a number greater than 0",
                ValidationError.Kind.INVALID,
            ),
        )
    }

    @Test
    fun `shortens a huge negative amount in the message`() {
        // given
        val request = validRequest.copy(amount = BigDecimal("-1E+999999999"))

        // when
        val error = validator.validate(request)

        // then
        assertThat(error?.value).isEqualTo("-1E+999999999")
        assertThat(error?.message).isEqualTo("Invalid amount '-1E+999999999'. Allowed: a number greater than 0")
    }

    @Test
    fun `leaves a missing amount to the required fields check`() {
        // given
        val request = validRequest.copy(amount = null)

        // when
        val error = validator.validate(request)

        // then
        assertThat(error).isNull()
    }

    @Test
    fun `runs after the transaction id check`() {
        // given
        val type = AmountValidator::class.java

        // when
        val order = type.getAnnotation(Order::class.java)?.value

        // then
        assertThat(order).isEqualTo(ValidationOrder.AMOUNT)
    }
}
