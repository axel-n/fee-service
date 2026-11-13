package com.example.fee.validation.rule

import com.example.fee.validation.ValidationError
import com.example.fee.validation.ValidationOrder
import com.example.fee.validation.validRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.core.annotation.Order

class StateValidatorTest {
    private val validator = StateValidator()

    @Test
    fun `passes SETTLED - PENDING FEE`() {
        // given
        val request = validRequest.copy(state = "SETTLED - PENDING FEE")

        // when
        val error = validator.validate(request)

        // then
        assertThat(error).isNull()
    }

    @ParameterizedTest(name = "''{0}''")
    @ValueSource(strings = ["PENDING", "SETTLED", "settled - pending fee", " SETTLED - PENDING FEE", "UNKNOWN"])
    fun `rejects any other state with readable message naming the supported one`(state: String) {
        // given
        val request = validRequest.copy(state = state)

        // when
        val error = validator.validate(request)

        // then
        assertThat(error).isEqualTo(
            ValidationError(
                "state",
                state,
                "Unsupported state '$state'. Supported: 'SETTLED - PENDING FEE'",
                ValidationError.Kind.UNSUPPORTED,
            ),
        )
    }

    @Test
    fun `leaves a missing state to the required fields check`() {
        // given
        val request = validRequest.copy(state = null)

        // when
        val error = validator.validate(request)

        // then
        assertThat(error).isNull()
    }

    @Test
    fun `runs first among the business checks`() {
        // given
        val type = StateValidator::class.java

        // when
        val order = type.getAnnotation(Order::class.java)?.value

        // then
        assertThat(order).isEqualTo(ValidationOrder.STATE)
    }
}
