package com.example.fee.validation.rule

import com.example.fee.validation.ValidationError
import com.example.fee.validation.ValidationOrder
import com.example.fee.validation.ValueDisplay
import com.example.fee.validation.validRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.core.annotation.Order
import java.math.BigDecimal

class AmountScaleValidatorTest {
    private val validator = AmountScaleValidator()

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["1000", "10.5", "10.01", "1E+3", "10.100", "10.0000"])
    fun `passes an amount with at most the asset's decimals, trailing zeros not counted`(amount: String) {
        // given
        val request = validRequest.copy(amount = BigDecimal(amount), asset = "USD")

        // when
        val error = validator.validate(request)

        // then
        assertThat(error).isNull()
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource("10.001, USD, 3", "10.0010, USD, 3", "0.0000001, EUR, 7", "1.999, EUR, 3")
    fun `rejects more decimals than the asset allows with readable message`(
        amount: String,
        asset: String,
        decimals: Int,
    ) {
        // given
        val request = validRequest.copy(amount = BigDecimal(amount), asset = asset)

        // when
        val error = validator.validate(request)

        // then
        assertThat(error).isEqualTo(
            ValidationError(
                "amount",
                amount,
                "Amount $amount has $decimals decimals. $asset allows 2",
                ValidationError.Kind.UNSUPPORTED,
            ),
        )
    }

    @Test
    fun `shortens a long amount in the message`() {
        // given
        val raw = "0." + "0".repeat(ValueDisplay.MAX_MESSAGE_LENGTH) + "1"
        val request = validRequest.copy(amount = BigDecimal(raw))

        // when
        val error = validator.validate(request)

        // then
        assertThat(error?.value).isEqualTo(raw)
        assertThat(error?.message).isEqualTo(
            "Amount ${raw.take(ValueDisplay.MAX_MESSAGE_LENGTH)}… has ${ValueDisplay.MAX_MESSAGE_LENGTH + 1} decimals. " +
                "USD allows 2",
        )
    }

    @Test
    fun `shows a tiny amount beyond the plain bound in scientific form`() {
        // given
        val request = validRequest.copy(amount = BigDecimal("1E-999999999"))

        // when
        val error = validator.validate(request)

        // then
        assertThat(error?.message).isEqualTo("Amount 1E-999999999 has 999999999 decimals. USD allows 2")
    }

    @Test
    fun `leaves an unknown asset to the asset check`() {
        // given
        val request = validRequest.copy(amount = BigDecimal("10.001"), asset = "BTC")

        // when
        val error = validator.validate(request)

        // then
        assertThat(error).isNull()
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
    fun `runs after the asset check`() {
        // given
        val type = AmountScaleValidator::class.java

        // when
        val order = type.getAnnotation(Order::class.java)?.value

        // then
        assertThat(order).isEqualTo(ValidationOrder.AMOUNT_SCALE)
    }
}
