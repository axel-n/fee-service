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

class MaxAmountValidatorTest {

    private val validator = MaxAmountValidator()

    @Test
    fun `allows up to 3 billion`() {
        // given
        val expected = 3_000_000_000.toBigDecimal()

        // when
        val max = MaxAmountValidator.MAX_AMOUNT

        // then
        assertThat(max).isEqualByComparingTo(expected)
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = [
            "0.01", "1", "1000", "999999999.99", "2999999999", "2999999999.99", "2999999999.999999",
            "3000000000", "3000000000.00", "3000000000.0000", "3E+9", "3.000000000E+9", "30E+8", "0.3E+10",
        ],
    )
    fun `passes an amount up to and including the maximum, whatever its form`(amount: String) {
        // given
        val request = validRequest.copy(amount = BigDecimal(amount))

        // when
        val error = validator.validate(request)

        // then
        assertThat(error).isNull()
    }

    @ParameterizedTest(name = "{0} is shown as {1}")
    @CsvSource(
        "3000000000.01, 3000000000.01",
        "3000000000.001, 3000000000.001",
        "3000000000.0000001, 3000000000.0000001",
        "3000000001, 3000000001",
        "3000000001.00, 3000000001.00",
        "3.0000000001E+9, 3000000000.1",
        "4000000000, 4000000000",
        "1E+10, 10000000000",
        "9999999999999.99, 9999999999999.99",
    )
    fun `rejects an amount above the maximum with readable message`(amount: String, shown: String) {
        // given
        val request = validRequest.copy(amount = BigDecimal(amount))

        // when
        val error = validator.validate(request)

        // then
        assertThat(error).isEqualTo(
            ValidationError(
                "amount",
                shown,
                "Amount $shown is above the maximum. Allowed: up to 3000000000",
                ValidationError.Kind.UNSUPPORTED,
            ),
        )
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["USD", "EUR", "BTC"])
    fun `applies the same maximum whatever the asset`(asset: String) {
        // given
        val request = validRequest.copy(amount = BigDecimal("3000000000.01"), asset = asset)

        // when
        val error = validator.validate(request)

        // then
        assertThat(error?.message).isEqualTo("Amount 3000000000.01 is above the maximum. Allowed: up to 3000000000")
    }

    @Test
    fun `shows a huge amount beyond the plain bound in scientific form`() {
        // given
        val request = validRequest.copy(amount = BigDecimal("1E+999999999"))

        // when
        val error = validator.validate(request)

        // then
        assertThat(error?.value).isEqualTo("1E+999999999")
        assertThat(error?.message).isEqualTo("Amount 1E+999999999 is above the maximum. Allowed: up to 3000000000")
    }

    @Test
    fun `shortens a long amount in the message`() {
        // given
        val raw = "3000000000." + "0".repeat(ValueDisplay.MAX_MESSAGE_LENGTH) + "1"
        val request = validRequest.copy(amount = BigDecimal(raw))

        // when
        val error = validator.validate(request)

        // then
        assertThat(error?.value).isEqualTo(raw)
        assertThat(error?.message).isEqualTo(
            "Amount ${raw.take(ValueDisplay.MAX_MESSAGE_LENGTH)}… is above the maximum. Allowed: up to 3000000000",
        )
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["0", "0.00", "-1", "-3000000000.01", "-1E+999999999"])
    fun `leaves zero and negative amounts to the amount check`(amount: String) {
        // given
        val request = validRequest.copy(amount = BigDecimal(amount))

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
    fun `runs after the amount scale check`() {
        // given
        val type = MaxAmountValidator::class.java

        // when
        val order = type.getAnnotation(Order::class.java)?.value

        // then
        assertThat(order).isEqualTo(ValidationOrder.MAX_AMOUNT)
    }
}
