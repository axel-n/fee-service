package com.example.fee.validation.rule

import com.example.fee.validation.ValidationError
import com.example.fee.validation.ValidationOrder
import com.example.fee.validation.validRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.core.annotation.Order

class AssetValidatorTest {

    private val validator = AssetValidator()

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["USD", "EUR"])
    fun `passes a supported asset`(asset: String) {
        // given
        val request = validRequest.copy(asset = asset)

        // when
        val error = validator.validate(request)

        // then
        assertThat(error).isNull()
    }

    @ParameterizedTest(name = "''{0}''")
    @ValueSource(strings = ["BTC", "GBP", "usd", "USD ", "UNKNOWN"])
    fun `rejects any other asset with readable message naming the supported ones`(asset: String) {
        // given
        val request = validRequest.copy(asset = asset)

        // when
        val error = validator.validate(request)

        // then
        assertThat(error).isEqualTo(
            ValidationError(
                "asset",
                asset,
                "Unsupported asset '$asset'. Supported: 'USD', 'EUR'",
                ValidationError.Kind.UNSUPPORTED,
            ),
        )
    }

    @Test
    fun `leaves a missing asset to the required fields check`() {
        // given
        val request = validRequest.copy(asset = null)

        // when
        val error = validator.validate(request)

        // then
        assertThat(error).isNull()
    }

    @Test
    fun `runs before the amount scale check`() {
        // given
        val type = AssetValidator::class.java

        // when
        val order = type.getAnnotation(Order::class.java)?.value

        // then
        assertThat(order).isEqualTo(ValidationOrder.ASSET)
    }
}
