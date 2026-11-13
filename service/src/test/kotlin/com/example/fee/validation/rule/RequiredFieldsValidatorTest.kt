package com.example.fee.validation.rule

import com.example.fee.contract.FeeRequest
import com.example.fee.validation.ValidationError
import com.example.fee.validation.ValidationOrder
import com.example.fee.validation.validRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.core.annotation.Order
import java.util.stream.Stream

class RequiredFieldsValidatorTest {
    private val validator = RequiredFieldsValidator()

    @Test
    fun `passes a request with every field`() {
        // given
        val request = validRequest

        // when
        val error = validator.validate(request)

        // then
        assertThat(error).isNull()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("withoutField")
    fun `rejects a missing field with a readable message`(field: String, request: FeeRequest) {
        // given
        val input = request

        // when
        val error = validator.validate(input)

        // then
        assertThat(error).isEqualTo(
            ValidationError(field, null, "Field '$field' is required", ValidationError.Kind.INVALID),
        )
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("withBlankField")
    fun `rejects a blank text field and shows the blank value`(field: String, request: FeeRequest) {
        // given
        val input = request

        // when
        val error = validator.validate(input)

        // then
        assertThat(error).isEqualTo(
            ValidationError(field, "  ", "Field '$field' is required but was blank: '  '", ValidationError.Kind.INVALID),
        )
    }

    @Test
    fun `reports the first missing field in contract order`() {
        // given
        val request = FeeRequest()

        // when
        val error = validator.validate(request)

        // then
        assertThat(error?.field).isEqualTo("transaction_id")
    }

    @Test
    fun `reports a later missing field when the earlier ones are present`() {
        // given
        val request = validRequest.copy(state = null, createdAt = null)

        // when
        val error = validator.validate(request)

        // then
        assertThat(error?.field).isEqualTo("state")
    }

    @Test
    fun `runs first among the validators`() {
        // given
        val type = RequiredFieldsValidator::class.java

        // when
        val order = type.getAnnotation(Order::class.java)?.value

        // then
        assertThat(order).isEqualTo(ValidationOrder.REQUIRED_FIELDS)
    }

    companion object {
        @JvmStatic
        fun withoutField(): Stream<Arguments> =
            Stream.of(
                Arguments.of("transaction_id", validRequest.copy(transactionId = null)),
                Arguments.of("amount", validRequest.copy(amount = null)),
                Arguments.of("asset", validRequest.copy(asset = null)),
                Arguments.of("asset_type", validRequest.copy(assetType = null)),
                Arguments.of("type", validRequest.copy(type = null)),
                Arguments.of("state", validRequest.copy(state = null)),
                Arguments.of("created_at", validRequest.copy(createdAt = null)),
            )

        @JvmStatic
        fun withBlankField(): Stream<Arguments> =
            Stream.of(
                Arguments.of("transaction_id", validRequest.copy(transactionId = "  ")),
                Arguments.of("asset", validRequest.copy(asset = "  ")),
                Arguments.of("asset_type", validRequest.copy(assetType = "  ")),
                Arguments.of("type", validRequest.copy(type = "  ")),
                Arguments.of("state", validRequest.copy(state = "  ")),
                Arguments.of("created_at", validRequest.copy(createdAt = "  ")),
            )
    }
}
