package com.example.fee.validation.rule

import com.example.fee.validation.ValidationError
import com.example.fee.validation.ValidationOrder
import com.example.fee.validation.validRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.core.annotation.Order

class AssetTypeValidatorTest {
    private val validator = AssetTypeValidator()

    @Test
    fun `passes FIAT`() {
        // given
        val request = validRequest.copy(assetType = "FIAT")

        // when
        val error = validator.validate(request)

        // then
        assertThat(error).isNull()
    }

    @ParameterizedTest(name = "''{0}''")
    @ValueSource(strings = ["CRYPTO", "fiat", "UNKNOWN"])
    fun `rejects any other asset type with readable message naming the supported one`(assetType: String) {
        // given
        val request = validRequest.copy(assetType = assetType)

        // when
        val error = validator.validate(request)

        // then
        assertThat(error).isEqualTo(
            ValidationError(
                "asset_type",
                assetType,
                "Unsupported asset_type '$assetType'. Supported: 'FIAT'",
                ValidationError.Kind.UNSUPPORTED,
            ),
        )
    }

    @Test
    fun `leaves a missing asset type to the required fields check`() {
        // given
        val request = validRequest.copy(assetType = null)

        // when
        val error = validator.validate(request)

        // then
        assertThat(error).isNull()
    }

    @Test
    fun `runs after the asset check`() {
        // given
        val type = AssetTypeValidator::class.java

        // when
        val order = type.getAnnotation(Order::class.java)?.value

        // then
        assertThat(order).isEqualTo(ValidationOrder.ASSET_TYPE)
    }
}
